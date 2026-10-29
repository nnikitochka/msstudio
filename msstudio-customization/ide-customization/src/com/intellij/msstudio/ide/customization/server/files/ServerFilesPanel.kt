// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.files

import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.PsiCopyPasteManager
import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDManager
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.DnDSource
import com.intellij.ide.dnd.DroppedFileCopy
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.ide.util.DeleteHandler
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileElement
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.TreePathUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.refactoring.copy.CopyHandler
import com.intellij.refactoring.move.MoveHandler
import java.awt.BorderLayout
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import java.awt.dnd.DnDConstants
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import java.awt.Color
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

/**
 * The panel of the Server Files tool window.
 *
 * It shows the files of a folder as a plain file system tree. The initial
 * folder is the base directory of the project. The tree data comes from
 * [ServerFilesTreeModel], which copies the update architecture of the
 * Project view.
 */
internal class ServerFilesPanel(
  private val project: Project,
  private val rootDisposable: Disposable,
) : JPanel(BorderLayout()), DataProvider, UiDataProvider {
  private val tree = Tree()
  private val treeHolder = JPanel(BorderLayout())
  private var treeModel: ServerFilesTreeModel? = null
  private var asyncTreeModel: AsyncTreeModel? = null
  private var currentFolder: VirtualFile? = null
  private var showHiddenFiles = false
  private val copyPasteDelegator = CopyPasteDelegator(project, this)
  private val ideView = ServerFilesIdeView()
  private val deleteProvider = DeleteHandler.DefaultDeleteProvider()

  init {
    add(treeHolder, BorderLayout.CENTER)

    tree.isRootVisible = false
    tree.showsRootHandles = true
    configureTree(tree)
    treeHolder.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)

    showFolder(project.basePath)
  }

  /**
   * The toggle for the "..." menu of the tool window. It shows or hides hidden files.
   */
  fun createShowHiddenFilesToggle(): ToggleAction {
    return object : ToggleAction(MSStudioBundle.serverFilesHiddenFilesText), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

      override fun isSelected(e: AnActionEvent): Boolean = showHiddenFiles

      override fun setSelected(e: AnActionEvent, state: Boolean) {
        showHiddenFiles = state
        treeModel?.setHiddenFilesShown(state)
      }
    }
  }

  private fun showFolder(path: String?) {
    currentFolder = path?.let { findFolder(it) }
    rebuildTree()
  }

  private fun findFolder(path: String): VirtualFile? {
    val url = VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, path)
    return VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
  }

  private fun rebuildTree() {
    asyncTreeModel?.let { Disposer.dispose(it) }
    val descriptor = FileChooserDescriptorFactory.multiFilesOrDirs()
      .withTreeRootVisible(false)
      .withShowHiddenFiles(showHiddenFiles)
    if (currentFolder != null) descriptor.withRoots(currentFolder)
    val model = ServerFilesTreeModel(project, descriptor, currentFolder, rootDisposable)
    val asyncModel = AsyncTreeModel(model, rootDisposable)
    treeModel = model
    asyncTreeModel = asyncModel
    tree.model = asyncModel
  }

  private fun configureTree(tree: Tree) {
    tree.selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
    tree.cellRenderer = ServerFilesTreeCellRenderer()
    TreeUtil.installActions(tree)
    registerOpenActions(tree)
    installPopupMenu(tree)
    TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
    enableDnD(tree)
  }

  /**
   * Installs the open action on the Enter key and the double click.
   * The Enter key also toggles a selected folder.
   */
  private fun registerOpenActions(tree: Tree) {
    tree.registerKeyboardAction(
      ActionListener { performOpenAction(tree, true) },
      KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
      JComponent.WHEN_FOCUSED
    )
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        performOpenAction(tree, false)
        return true
      }
    }.installOn(tree)
  }

  /**
   * Opens the selected file, or toggles the selected folder when requested.
   */
  private fun performOpenAction(tree: Tree, toggleNodeState: Boolean) {
    val path = tree.selectionPath ?: return
    val file = treeModel?.getFileOf(path) ?: return
    if (file.isDirectory) {
      if (toggleNodeState) {
        if (tree.isExpanded(path)) {
          tree.collapsePath(path)
        }
        else {
          tree.expandPath(path)
        }
      }
    }
    else {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
  }

  private fun installPopupMenu(tree: Tree) {
    val popupGroup = ActionManager.getInstance().getAction("MSStudio.Files.Popup") as? ActionGroup
    if (popupGroup != null) {
      PopupHandler.installPopupMenu(tree, popupGroup, ActionPlaces.PROJECT_VIEW_POPUP)
    }
  }

  /**
   * Enables drag and drop on the tree with the logic of the Project view.
   *
   * The drag source and the drop target both live on the tree. A drop onto a folder row moves or copies the dragged files
   * into that folder. A drop onto a file row targets the folder of the file.
   *
   * One extension over the Project view: the empty space below the rows also accepts drops,
   * and it targets the base folder of the tree.
   */
  private fun enableDnD(tree: Tree) {
    if (ApplicationManager.getApplication().isHeadlessEnvironment) return
    val dropTarget = object : DnDNativeTarget {
      override fun update(event: DnDEvent): Boolean {
        val actionId = event.action?.actionId ?: return false
        if (actionId != DnDConstants.ACTION_COPY && actionId != DnDConstants.ACTION_MOVE && actionId != DnDConstants.ACTION_COPY_OR_MOVE) {
          return false
        }
        val target = event.point?.let(::targetPathAt) ?: return false
        if (targetDirectory(target) == null || !hasDroppableFiles(event)) {
          return false
        }
        val bounds = tree.getPathBounds(target) ?: emptyAreaBelowRows() ?: return false
        event.setHighlighting(RelativeRectangle(tree, bounds), DnDEvent.DropTargetHighlightingType.RECTANGLE)
        event.setDropPossible(true)
        return false
      }

      override fun drop(event: DnDEvent) {
        val point = event.point ?: return
        val target = targetPathAt(point) ?: return
        val directory = targetDirectory(target) ?: return
        val wrapper = event.attachedObject as? TransferableWrapper
        val elements = wrapper?.psiElements?.filter { it.isValid }?.toTypedArray()
        if (!elements.isNullOrEmpty()) {
          if (event.action?.actionId == DnDConstants.ACTION_COPY) {
            CopyHandler.doCopy(elements, directory)
          }
          else {
            MoveHandler.doMove(project, elements, directory, null, null)
          }
          return
        }
        val paths = FileCopyPasteUtil.getPathListFromAttachedObject(event.attachedObject)
        if (paths.isNotEmpty()) {
          copyDroppedPaths(paths, directory)
          return
        }
        val files = FileCopyPasteUtil.getFileListFromAttachedObject(event.attachedObject)
        val sourceElements = files.mapNotNull { file ->
          StandardFileSystems.local().refreshAndFindFileByPath(file.path)?.let(::findPsiElement)
        }.toTypedArray()
        if (sourceElements.isNotEmpty()) {
          CopyHandler.doCopy(sourceElements, directory)
        }
      }

      /**
       * Returns the free area below the last visible row. It holds no rows, so a drop
       * there targets the tree root. The highlighting covers this area only, so the
       * row highlighting and it never overlap.
       */
      private fun emptyAreaBelowRows(): Rectangle? {
        val visible = tree.getVisibleRect()
        val lastBounds = tree.getPathForRow(tree.rowCount - 1)?.let { tree.getPathBounds(it) } ?: return visible
        val top = lastBounds.y + lastBounds.height
        val bottom = visible.y + visible.height
        return if (top < bottom) Rectangle(visible.x, top, visible.width, bottom - top) else null
      }

      /**
       * Returns the row under the point, or the tree root when the point is
       * outside every row.
       */
      private fun targetPathAt(point: Point): TreePath? {
        val path = tree.getClosestPathForLocation(point.x, point.y)
        if (path != null) {
          val bounds = tree.getPathBounds(path)
          if (bounds != null && bounds.y <= point.y && point.y < bounds.y + bounds.height) return path
        }
        return rootPath()
      }

      /**
       * Returns the path of the tree root. The root row is invisible, so the
       * path comes from the parent of the first row.
       */
      private fun rootPath(): TreePath? {
        tree.getPathForRow(0)?.parentPath?.let { return it }
        val root = tree.model?.root ?: return null
        return TreePath(root)
      }

      private fun targetDirectory(path: TreePath): PsiDirectory? {
        val file = treeModel?.getFileOf(path)
        val folder = file ?: currentFolder?.takeIf { path.getPathCount() == 1 }
        val element = folder?.let(::findPsiElement)
        return when (element) {
          is PsiDirectory -> element
          else -> element?.containingFile?.containingDirectory
        }
      }

      private fun hasDroppableFiles(event: DnDEvent): Boolean {
        val elements = (event.attachedObject as? TransferableWrapper)?.psiElements
        return !elements.isNullOrEmpty() ||
               FileCopyPasteUtil.getPathListFromAttachedObject(event.attachedObject).isNotEmpty() ||
               FileCopyPasteUtil.isFileListFlavorAvailable(event)
      }

      private fun copyDroppedPaths(paths: List<Path>, directory: PsiDirectory) {
        val destination = directory.virtualFile.toNioPathOrNull() ?: return
        DroppedFileCopy.copyInBackground(project, destination, paths)
      }
    }
    val dragSource = object : DnDSource {
      override fun canStartDragging(action: DnDAction, dragOrigin: Point): Boolean {
        if (action.actionId and DnDConstants.ACTION_COPY_OR_MOVE == 0) return false
        return !tree.selectionPaths.isNullOrEmpty()
      }

      override fun startDragging(action: DnDAction, dragOrigin: Point): DnDDragStartBean {
        val psiElements = selectedPsiElements().toTypedArray()
        val paths = tree.selectionPaths
        return DnDDragStartBean(object : TransferableWrapper {
          override fun asFileList(): List<File>? =
            ReadAction.computeBlocking<List<File>?, RuntimeException> { PsiCopyPasteManager.asFileList(psiElements) }

          override fun getTreePaths(): Array<TreePath>? = paths

          override fun getTreeNodes(): Array<TreeNode>? = paths?.let { TreePathUtil.toTreeNodes(*it) }

          override fun getPsiElements(): Array<PsiElement>? = psiElements
        })
      }

      override fun createDraggedImage(action: DnDAction, dragOrigin: Point, bean: DnDDragStartBean): Pair<Image, Point>? {
        return null
      }
    }
    val dndManager = DnDManager.getInstance()
    dndManager.registerSource(dragSource, tree, rootDisposable)
    dndManager.registerTarget(dropTarget, tree, rootDisposable)
  }

  override fun getData(dataId: String): Any? {
    return when {
      CommonDataKeys.VIRTUAL_FILE.`is`(dataId) -> selectedFiles().firstOrNull()
      CommonDataKeys.VIRTUAL_FILE_ARRAY.`is`(dataId) -> selectedFiles().toTypedArray()
      LangDataKeys.IDE_VIEW.`is`(dataId) -> ideView
      PlatformDataKeys.COPY_PROVIDER.`is`(dataId) -> copyPasteDelegator.copyProvider
      PlatformDataKeys.CUT_PROVIDER.`is`(dataId) -> copyPasteDelegator.cutProvider
      PlatformDataKeys.PASTE_PROVIDER.`is`(dataId) -> copyPasteDelegator.pasteProvider
      PlatformDataKeys.DELETE_ELEMENT_PROVIDER.`is`(dataId) -> deleteProvider
      CommonDataKeys.PROJECT.`is`(dataId) -> project
      else -> null
    }
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink.lazy(CommonDataKeys.PSI_ELEMENT) { selectedPsiElements().singleOrNull() }
    sink.lazy(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY) {
      selectedPsiElements().toTypedArray().takeIf { it.isNotEmpty() }
    }
  }

  private fun selectedFiles(): List<VirtualFile> {
    val model = treeModel ?: return emptyList()
    return tree.selectionPaths
      ?.mapNotNull { model.getFileOf(it) }
      ?.filter { it.isValid }
      ?: emptyList()
  }

  private fun selectedPsiElements(): List<PsiElement> {
    val files = selectedFiles()
    if (files.isEmpty()) return emptyList()
    return files.mapNotNull { findPsiElement(it) }
  }

  private fun findPsiElement(file: VirtualFile): PsiElement? {
    return ReadAction.compute<PsiElement?, RuntimeException> {
      val psiManager = PsiManager.getInstance(project)
      if (file.isDirectory) psiManager.findDirectory(file) else psiManager.findFile(file)
    }
  }

  private inner class ServerFilesIdeView : IdeView {
    override fun getDirectories(): Array<PsiDirectory> {
      return ReadAction.compute<Array<PsiDirectory>, RuntimeException> {
        selectedPsiElements().mapNotNull { element ->
          when (element) {
            is PsiDirectory -> element.takeIf { it.isValid }
            else -> element.containingFile?.containingDirectory?.takeIf { it.isValid }
          }
        }.distinct().toTypedArray()
      }
    }

    override fun getOrChooseDirectory(): PsiDirectory? = directories.singleOrNull()
  }

  /**
   * The renderer of the tree. It reads the live state of the file, so a
   * renamed or invalidated file always shows the current name and state.
   *
   * The icon comes from the node. The update pipeline computed it once, and
   * the renderer paints that instance on every paint. A fresh icon request
   * on every paint would create a new deferred icon, because every VFS
   * batch clears the icon cache, and the rows would flicker.
   */
  private class ServerFilesTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
      tree: JTree,
      value: Any,
      selected: Boolean,
      expanded: Boolean,
      leaf: Boolean,
      row: Int,
      hasFocus: Boolean,
    ) {
      val node = value as? Node ?: return
      val file = node.file ?: return
      var style = SimpleTextAttributes.STYLE_PLAIN
      var color: Color? = null
      if (!file.isValid) style = style or SimpleTextAttributes.STYLE_STRIKEOUT
      val hidden = FileElement.isFileHidden(file)
      if (hidden) color = JBColor.GRAY
      val icon = node.icon
      this.icon = if (hidden && icon != null) IconLoader.getTransparentIcon(icon) else icon
      append(file.getName(), SimpleTextAttributes(style, color))
    }
  }
}
