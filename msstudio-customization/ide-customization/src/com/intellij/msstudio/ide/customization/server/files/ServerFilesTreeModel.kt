// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.files

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tree.project.ProjectFileNodeUpdater
import com.intellij.util.concurrency.Invoker
import com.intellij.util.concurrency.InvokerSupplier
import com.intellij.util.ui.tree.AbstractTreeModel
import java.util.LinkedHashSet
import javax.swing.Icon
import javax.swing.tree.TreePath

/**
 * The file tree model of the Server Files tool window.
 *
 * The model copies the update architecture of the Project view. An updater collects
 * the changed files in a short window ([ProjectFileNodeUpdater]). Then the model
 * reloads only the affected folders and reports one structural event on the EDT.
 * A node keeps its identity while its file exists, so the tree keeps the selection
 * and the expanded paths.
 */
internal class ServerFilesTreeModel(
  private val project: Project,
  private val descriptor: FileChooserDescriptor,
  rootFolder: VirtualFile?,
  parentDisposable: Disposable,
) : AbstractTreeModel(), InvokerSupplier {
  private val invoker = Invoker.forBackgroundThreadWithReadAction(this)
  private val updater = object : ProjectFileNodeUpdater(project, invoker) {
    override fun updateStructure(fromRoot: Boolean, updatedFiles: Set<VirtualFile>) {
      if (disposed) return
      TRACE.info("[MSFiles] update: fromRoot=$fromRoot, files=${updatedFiles.size}")
      if (fromRoot) {
        reloadAll()
      }
      else {
        refreshPresentationsOf(updatedFiles)
        reloadFoldersOf(updatedFiles)
      }
    }
  }
  private val root = FolderNode(rootFolder, null)

  init {
    try {
      root.children = root.computeChildren(descriptor)
    }
    catch (ignored: ProcessCanceledException) {
      // a write action holds the read access, the first tree query retries
    }
    Disposer.register(parentDisposable, this)
  }

  override fun getInvoker(): Invoker = invoker

  override fun getRoot(): Any = root

  override fun isLeaf(any: Any): Boolean {
    val node = any as? Node ?: return true
    return !node.isDirectory
  }

  override fun getChildCount(parent: Any): Int {
    val node = parent as? Node ?: return 0
    node.ensureLoaded(descriptor)
    return node.children.size
  }

  override fun getChild(parent: Any, index: Int): Any? {
    val node = parent as? Node ?: return null
    return node.children.getOrNull(index)
  }

  override fun getIndexOfChild(parent: Any, child: Any): Int {
    val parentNode = parent as? Node ?: return -1
    val node = child as? Node ?: return -1
    if (node.parent !== parentNode) return -1
    return parentNode.children.indexOf(node)
  }

  fun setHiddenFilesShown(value: Boolean) {
    invoker.invoke {
      if (disposed || value == descriptor.isShowHiddenFiles) return@invoke
      descriptor.withShowHiddenFiles(value)
      reloadAll()
    }
  }

  /**
   * Returns the file of the innermost node on the path. It is the value of
   * the last element, or the base folder for the root path.
   */
  fun getFileOf(path: TreePath?): VirtualFile? {
    var node = path?.lastPathComponent as? Node ?: return null
    while (node.file == null) {
      node = node.parent ?: return null
    }
    return node.file
  }

  /**
   * Reloads the children of every loaded folder. The hidden files switch
   * needs the full pass.
   */
  private fun reloadAll() {
    val folders = LinkedHashSet<FolderNode>()
    collectFolders(root, folders)
    folders.forEach(::applyReload)
  }

  /**
   * Reduces the given files to the folders of the tree and reloads each
   * folder once. Updates of the files outside the loaded tree do nothing.
   */
  private fun reloadFoldersOf(files: Set<VirtualFile>) {
    val folders = LinkedHashSet<FolderNode>()
    files.forEach { file ->
      folders.addAll(ownFoldersOf(file))
    }
    folders.forEach(::applyReload)
  }

  /**
   * Refreshes the presentation of the affected nodes. A file tree stores the
   * deferred icon in the node, because the renderer must not create one per
   * paint. Like the Project view, the refresh compares the state of the
   * presentation and skips the node when it did not change. An icon instance
   * alone cannot prove a change: every VFS batch clears the icon cache, and
   * a fresh deferred icon instance is not a presentation change.
   */
  private fun refreshPresentationsOf(files: Set<VirtualFile>) {
    val nodes = LinkedHashSet<Node>()
    files.forEach { file ->
      if (!file.isValid) return@forEach
      val writable = file.isWritable
      // a content write keeps the writability, so the presentation of the
      // node is equal and the icon stays untouched
      findNode(file)?.takeIf { it.icon == null || it.knownIsWritable != writable }?.let(nodes::add)
    }
    nodes.forEach { node ->
      val file = node.file ?: return@forEach
      val icon = descriptor.getIcon(file)
      TRACE.info("[MSFiles] presentation refresh: ${file.name}")
      ApplicationManager.getApplication().invokeLater {
        if (disposed || node.file !== file || node.icon === icon) return@invokeLater
        node.knownIsWritable = file.isWritable
        node.icon = icon
        val parent = node.parent ?: return@invokeLater
        val index = parent.children.indexOf(node)
        if (index < 0) return@invokeLater
        treeNodesChanged(pathTo(parent), intArrayOf(index), arrayOf(node))
      }
    }
  }

  /**
   * Returns the folders a changed file affects. A changed folder gets its own
   * children and the parent list, because a rename of the folder may reorder
   * the parent list. A changed file only moves its own folder.
   */
  private fun ownFoldersOf(file: VirtualFile): List<FolderNode> {
    var current: VirtualFile? = file
    while (current != null && !current.isValid) {
      current = current.parent
    }
    val node = if (current == null) null else findNode(current) ?: return emptyList()
    return when (node) {
      is FolderNode -> if (node.parent is FolderNode) listOf(node, node.parent as FolderNode) else listOf(node)
      else -> listOfNotNull(node?.parent as? FolderNode)
    }
  }

  /**
   * Finds the node of the file on the loaded part of the tree, or `null`
   * when the file was never loaded.
   */
  private fun findNode(file: VirtualFile): Node? {
    if (root.file === file || root.file?.getUrl() == file.url) return root
    val queue = ArrayDeque<Node>()
    queue.addAll(root.children)
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (node.file === file || node.file?.getUrl() == file.url) return node
      queue.addAll(node.children)
    }
    return null
  }

  private fun collectFolders(node: Node, into: LinkedHashSet<FolderNode>) {
    if (node !is FolderNode || !node.isLoaded) return
    into.add(node)
    node.children.forEach { collectFolders(it, into) }
  }

  /**
    * Rebuilds the children list of the folder and reports one structural event.
   *
    * The listing runs on the background invoker. The new list is installed
    * before the event, so the async wrapper reads one complete state.
    * An identical listing fires no event at all.
   */
  private fun applyReload(folder: FolderNode) {
    if (disposed) return
    val parentPath = pathTo(folder)
    val newChildren = folder.computeChildren(descriptor)

    ApplicationManager.getApplication().invokeLater {
      if (disposed) return@invokeLater
      val diff = diffChildren(folder.children, newChildren)
      TRACE.info(
        "[MSFiles] folder=${folder.file?.getName()}, removed=${diff.removedNodes.size}, " +
        "inserted=${diff.insertedNodes.size}, changed=${diff.changedNodes.size}"
      )
      if (diff.removedNodes.isEmpty() && diff.insertedNodes.isEmpty() && diff.changedNodes.isEmpty()) return@invokeLater

      // Install the complete list before the event. AsyncTreeModel reads it
      // as one state and does not render an intermediate list.
      folder.children = newChildren
      treeStructureChanged(parentPath, null, null)
    }
  }

  /**
   * Builds the path from the root node down to the node.
   */
  private fun pathTo(node: Node): TreePath {
    val elements = ArrayList<Any>()
    var each: Node? = node
    while (each != null) {
      elements.add(each)
      each = each.parent
    }
    elements.reverse()
    return TreePath(elements.toTypedArray())
  }

  private companion object {
    /** The temporary diagnostic of the tree updates; remove after the blink is found. */
    private val TRACE = logger<ServerFilesTreeModel>()
  }
}

/**
 * The base of the nodes. The tree holds [FolderNode] for the directories and
 * [LeafNode] for the files. A node keeps its [file], so the presentation reads
 * the live state of the file and never stores a stale name.
 *
 * The file instance is the input of one listing. Some file systems return a
 * fresh instance on every listing, so the node refreshes its [file] reference
 * on every reload and matches the children of the next listing by the URL of
 * the file. The node itself keeps its identity, so the paths, the selection,
 * and the expanded state survive.
 *
 * The icon lives in the node and not in the renderer. The update path
 * computes an icon when the node changes. The renderer paints that instance,
 * and its deferred evaluation runs once. A fresh icon request on every paint
 * would create a new deferred icon, because every VFS batch clears the icon
 * cache.
 */
internal abstract class Node(
  @JvmField @Volatile var file: VirtualFile?,
  @JvmField val parent: Node?,
) {
  @Volatile var children: List<Node> = emptyList()
  @Volatile var isLoaded: Boolean = false

  /** The icon the renderer paints. Only the update pipeline assigns it. */
  @JvmField @Volatile var icon: Icon? = null

  /**
   * The writability of the file at the moment the icon was computed. The
   * locked layer of the icon depends on it. A content write does not change
   * it, so the presentation of the node stays equal on a write, and the
   * update path skips the node, exactly as the Project view does.
   */
  @JvmField @Volatile var knownIsWritable: Boolean? = null

  val isDirectory: Boolean
    get() {
      val current = file
      return current != null && current.isDirectory
    }

  /**
   * Builds and returns the children, but keeps the tree state unchanged.
   * The caller puts the result into [children] at a safe moment. The files
   * inherit none.
   */
  open fun computeChildren(descriptor: FileChooserDescriptor): List<Node> {
    isLoaded = true
    return emptyList()
  }

  /**
   * Loads the children between the calls, so the first expansion shows them.
   * A pending write action blocks the read access, and then the next query
   * retries.
   */
  open fun ensureLoaded(descriptor: FileChooserDescriptor) {
    if (!isLoaded) {
      try {
        children = computeChildren(descriptor)
      }
      catch (ignored: ProcessCanceledException) {
      }
    }
  }
}

internal class FolderNode(
  file: VirtualFile?,
  parent: Node?,
) : Node(file, parent) {
  private companion object {
    /** The temporary diagnostic of the listing; remove after the blink is found. */
    private val TRACE = logger<FolderNode>()
  }

  override fun computeChildren(descriptor: FileChooserDescriptor): List<Node> {
    val folder = file
    if (folder == null || !folder.isDirectory) {
      isLoaded = true
      return emptyList()
    }
    val fresh: Array<VirtualFile> = ReadAction.compute<Array<VirtualFile>?, RuntimeException> {
      folder.getChildren()
    } ?: VirtualFile.EMPTY_ARRAY
    val visible = fresh
      .filter { descriptor.isFileVisible(it, descriptor.isShowHiddenFiles) }
      .sortedWith { one, two ->
        if (one.isDirectory != two.isDirectory) {
          if (one.isDirectory) -1 else 1
        }
        else StringUtil.naturalCompare(one.getName(), two.getName())
      }
    val oldChildren = children
    var reused = 0
    val newChildren = visible.map { target ->
      val old = oldChildren.firstOrNull { it.file === target || it.file?.getUrl() == target.url }
      if (old == null) {
        val node = when {
          target.isDirectory -> FolderNode(target, this)
          else -> LeafNode(target, this)
        }
        // a new node paints with the insertion event, so its icon applies at once
        node.icon = descriptor.getIcon(target)
        node.knownIsWritable = target.isWritable
        node
      }
      else {
        // the file wrapper may change on every listing; the node keeps the
        // fresh instance, so the renderer and the update path read the live one
        old.file = target
        reused++
        old
      }
    }
    TRACE.info(
      "[MSFiles] listing: ${folder.getName()}, children=${newChildren.size}, reused=$reused, " +
      "fs=${folder.fileSystem.javaClass.simpleName}, " +
      "sample=${visible.take(3).joinToString { "${it.getName()}@${Integer.toHexString(System.identityHashCode(it))}" }}"
    )
    isLoaded = true
    return newChildren
  }
}

internal class LeafNode(
  file: VirtualFile,
  parent: Node?,
) : Node(file, parent)
