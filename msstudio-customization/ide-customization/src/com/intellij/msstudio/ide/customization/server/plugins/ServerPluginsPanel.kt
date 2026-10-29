// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.plugins

import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.util.DeleteHandler
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.ex.FileSystemTreeImpl
import com.intellij.openapi.fileChooser.tree.FileNode
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.ui.PopupHandler
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.JTree

/**
 * The panel of the Plugins tool window.
 *
 * It scans the `plugins` folder of the server and shows only the folders of
 * the installed plugins. The version and the name of a plugin come from the
 * Bukkit or Paper manifest inside its jar. The folders stay real tree nodes,
 * so the user can copy, paste, and delete them as usual. The scan and the
 * jar hashing run in a background task.
 */
internal class ServerPluginsPanel(
  private val project: Project,
  parentDisposable: Disposable,
  private val pluginsCache: ServerPluginsCache,
) : JPanel(BorderLayout()), DataProvider, UiDataProvider {
  private val treeHolder = JPanel(BorderLayout())
  private var fileSystemTree: FileSystemTreeImpl? = null
  private var pluginsDir: VirtualFile? = null
  private var showHiddenFiles = false
  private val copyPasteDelegator = CopyPasteDelegator(project, this)
  private val ideView = PluginsIdeView()
  private val deleteProvider = DeleteHandler.DefaultDeleteProvider()

  init {
    add(treeHolder, BorderLayout.CENTER)
    refresh()
    Disposer.register(parentDisposable, Disposable { disposeTree() })
  }

  /**
   * The refresh action for the "..." menu of the tool window.
   */
  fun createRefreshAction(): AnAction {
    return object : AnAction(MSStudioBundle.serverPluginsRefreshText), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun actionPerformed(e: AnActionEvent) {
        refresh()
      }
    }
  }

  /**
   * The toggle for the "..." menu of the tool window. It shows or hides hidden files.
   */
  fun createHiddenFilesToggle(): ToggleAction {
    return object : ToggleAction(MSStudioBundle.serverFilesHiddenFilesText), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

      override fun isSelected(e: AnActionEvent): Boolean = showHiddenFiles

      override fun setSelected(e: AnActionEvent, state: Boolean) {
        showHiddenFiles = state
        fileSystemTree?.showHiddens(state)
      }
    }
  }

  private fun refresh() {
    ProgressManager.getInstance().run(
      object : Task.Backgroundable(project, MSStudioBundle.serverPluginsScanningTitle, true) {
        override fun run(indicator: ProgressIndicator) {
          val dir = findPluginsDir()
          val plugins = dir?.let { scanPluginFolders(it, indicator) }
          pluginsCache.cleanup()
          invokeLater {
            if (!project.isDisposed) {
              applyScanResult(dir, plugins ?: emptyList())
            }
          }
        }
      }
    )
  }

  private fun applyScanResult(dir: VirtualFile?, plugins: List<PluginFolder>) {
    pluginsDir = dir
    disposeTree()
    if (dir == null) {
      showLabel(MSStudioBundle.serverPluginsFolderMissing)
      return
    }
    if (plugins.isEmpty()) {
      showLabel(MSStudioBundle.serverPluginsEmptyText)
      return
    }
    val descriptor = FileChooserDescriptor(true, true, false, true)
      .withTreeRootVisible(false)
      .withShowHiddenFiles(showHiddenFiles)
      .withShowFileSystemRoots(false)
      .withRoots(plugins.map(PluginFolder::folder))
    val newTree = FileSystemTreeImpl(project, descriptor)
    newTree.tree.cellRenderer = PluginFolderCellRenderer(plugins.associate { it.folder to it.version })
    fileSystemTree = newTree
    newTree.addOkAction { openSelectedFile(newTree) }
    val popupGroup = ActionManager.getInstance().getAction("MSStudio.Files.Popup") as? ActionGroup
    if (popupGroup != null) {
      PopupHandler.installPopupMenu(newTree.tree, popupGroup, ActionPlaces.PROJECT_VIEW_POPUP)
    }
    treeHolder.add(ScrollPaneFactory.createScrollPane(newTree.tree, true), BorderLayout.CENTER)
    treeHolder.revalidate()
    treeHolder.repaint()
  }

  private fun findPluginsDir(): VirtualFile? {
    val basePath = project.basePath ?: return null
    val url = VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, "$basePath/plugins")
    return VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
  }

  /**
   * Collects the folders of the installed plugins. A folder is a plugin
   * folder when it holds its own manifest or when its name equals the name
   * from a jar manifest.
   */
  private fun scanPluginFolders(pluginsDir: VirtualFile, indicator: ProgressIndicator): List<PluginFolder> {
    val children = pluginsDir.children.filter { it.isValid }
    val jarManifests = children
      .filter { !it.isDirectory && it.extension.equals("jar", ignoreCase = true) }
      .map { jar ->
        indicator.checkCanceled()
        jar to pluginsCache.jarInfo(jar)
      }
    return children.mapNotNull { folder ->
      if (!folder.isDirectory) return@mapNotNull null
      val folderManifest = pluginsCache.folderInfo(folder)
      val jarManifest = jarManifests.firstOrNull { (_, manifest) ->
        manifest?.name.equals(folder.name, ignoreCase = true)
      }?.second
      if (folderManifest == null && jarManifest == null) {
        return@mapNotNull null
      }
      PluginFolder(folder, jarManifest?.version ?: folderManifest?.version)
    }
  }

  private fun showLabel(text: String) {
    treeHolder.add(JBLabel(text, SwingConstants.CENTER), BorderLayout.CENTER)
    treeHolder.revalidate()
    treeHolder.repaint()
  }

  override fun getData(dataId: String): Any? {
    return when {
      CommonDataKeys.VIRTUAL_FILE.`is`(dataId) -> fileSystemTree?.selectedFile
      CommonDataKeys.VIRTUAL_FILE_ARRAY.`is`(dataId) -> fileSystemTree?.selectedFiles
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

  private fun selectedPsiElements(): List<PsiElement> {
    val files = fileSystemTree?.selectedFiles?.filter { it.isValid } ?: return emptyList()
    if (files.isEmpty()) return emptyList()
    return ReadAction.compute<List<PsiElement>, RuntimeException> {
      val psiManager = PsiManager.getInstance(project)
      files.mapNotNull { file ->
        if (file.isDirectory) psiManager.findDirectory(file) else psiManager.findFile(file)
      }
    }
  }

  private inner class PluginsIdeView : IdeView {
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

    override fun getOrChooseDirectory(): PsiDirectory? {
      return directories.singleOrNull()
    }
  }

  private fun openSelectedFile(tree: FileSystemTreeImpl) {
    val file = tree.selectedFile
    if (file != null && !file.isDirectory) {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
  }

  private fun disposeTree() {
    fileSystemTree?.let { Disposer.dispose(it) }
    fileSystemTree = null
    treeHolder.removeAll()
  }

  private class PluginFolderCellRenderer(private val versions: Map<VirtualFile, String?>) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
      tree: JTree,
      value: Any?,
      selected: Boolean,
      expanded: Boolean,
      leaf: Boolean,
      row: Int,
      hasFocus: Boolean,
    ) {
      val node = value as? FileNode ?: return
      val file = node.file
      icon = node.icon
      append(file.presentableName)
      versions[file]?.takeIf { it.isNotEmpty() }?.let { version ->
        append(" ($version)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
  }

  private data class PluginFolder(val folder: VirtualFile, val version: String?)
}
