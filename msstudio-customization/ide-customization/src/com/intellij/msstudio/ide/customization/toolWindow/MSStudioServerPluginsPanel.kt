// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.projectView.impl.ProjectViewDeleteElementProvider
import com.intellij.msstudio.ide.customization.toolWindow.MSStudioServerPluginsCache.PluginInfo
import com.intellij.msstudio.ide.customization.welcomeScreen.MSStudioWelcomeBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.ex.FileSystemTreeImpl
import com.intellij.openapi.fileEditor.FileEditorManager
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
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The panel of the Plugins tool window.
 *
 * It scans the `plugins` folder of the server and shows only the folders of
 * the installed plugins. The version and the name of a plugin come from the
 * Bukkit or Paper manifest inside its jar. The folders stay real tree nodes,
 * so the user can copy, paste, and delete them as usual.
 */
internal class MSStudioServerPluginsPanel(
  private val project: Project,
  parentDisposable: Disposable,
) : JPanel(BorderLayout()), DataProvider, UiDataProvider {
  private val treeHolder = JPanel(BorderLayout())
  private val pluginsCache = MSStudioServerPluginsCache(project)
  private var fileSystemTree: FileSystemTreeImpl? = null
  private var showHiddenFiles = false
  private val copyPasteDelegator = CopyPasteDelegator(project, this)
  private val ideView = PluginsIdeView()
  private val deleteProvider = object : ProjectViewDeleteElementProvider() {
    override fun getSelectedPSIElements(dataContext: DataContext): Array<PsiElement> {
      return selectedPsiElements().toTypedArray()
    }

    override fun hideEmptyMiddlePackages(dataContext: DataContext): Boolean = false
  }

  init {
    add(treeHolder, BorderLayout.CENTER)
    refresh()
    Disposer.register(parentDisposable, Disposable { disposeTree() })
  }

  /**
   * The refresh action for the "..." menu of the tool window.
   */
  fun createRefreshAction(): AnAction {
    return object : AnAction(MSStudioWelcomeBundle.message("msstudio.serverPlugins.refresh.text")), DumbAware {
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
    return object : ToggleAction(MSStudioWelcomeBundle.message("msstudio.serverFiles.hiddenFiles.text")), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

      override fun isSelected(e: AnActionEvent): Boolean = showHiddenFiles

      override fun setSelected(e: AnActionEvent, state: Boolean) {
        showHiddenFiles = state
        fileSystemTree?.showHiddens(state)
      }
    }
  }

  private fun refresh() {
    disposeTree()
    val pluginsDir = findPluginsDir()
    if (pluginsDir == null) {
      showLabel(MSStudioWelcomeBundle.message("msstudio.serverPlugins.folder.missing"))
      return
    }
    val pluginFolders = scanPluginFolders(pluginsDir)
    pluginsCache.cleanup()
    if (pluginFolders.isEmpty()) {
      showLabel(MSStudioWelcomeBundle.message("msstudio.serverPlugins.empty.text"))
      return
    }
    val descriptor = object : FileChooserDescriptor(true, true, false, true) {
      override fun getName(file: VirtualFile): String {
        return displayNameOf(file, pluginsDir) ?: file.presentableName
      }
    }
      .withTreeRootVisible(false)
      .withShowHiddenFiles(showHiddenFiles)
      .withShowFileSystemRoots(false)
      .withRoots(pluginFolders)
    val newTree = FileSystemTreeImpl(project, descriptor)
    fileSystemTree = newTree
    newTree.addOkAction { openSelectedFile(newTree) }
    val popupGroup = ActionManager.getInstance().getAction("ProjectViewPopupMenu") as? ActionGroup
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
   * folder when it holds its own manifest, when its name equals the manifest
   * name of a jar, or when a jar with the same name sits beside it.
   */
  private fun scanPluginFolders(pluginsDir: VirtualFile): List<VirtualFile> {
    val children = pluginsDir.children.filter { it.isValid }
    val jarInfos = children
      .filter { !it.isDirectory && it.extension.equals("jar", ignoreCase = true) }
      .mapNotNull { jar -> pluginsCache.jarInfo(jar) }
    val namesFromJars = jarInfos.mapNotNull { it.name }.toSet()
    return children.filter { child ->
      if (!child.isDirectory) return@filter false
      pluginsCache.folderInfo(child) != null ||
      child.name in namesFromJars ||
      children.any { sibling -> !sibling.isDirectory && sibling.nameWithoutExtension.equals(child.name, ignoreCase = true) &&
                               sibling.extension.equals("jar", ignoreCase = true) }
    }
  }

  /**
   * The tree label of a plugin folder: the folder name, the version in
   * parentheses, and the manifest name when it differs from the folder name.
   */
  private fun displayNameOf(file: VirtualFile, pluginsDir: VirtualFile): String? {
    if (file.parent != pluginsDir) return null
    val ownInfo = pluginsCache.folderInfo(file)
    val siblingJar = pluginsDir.findChild("${file.name}.jar")
    val jarInfo = siblingJar?.let { pluginsCache.jarInfo(it) }
    val version = (jarInfo ?: ownInfo)?.version
    val name = (jarInfo ?: ownInfo)?.name
    val suffix = version?.let { " ($it)" }.orEmpty()
    val alias = name?.takeIf { it.isNotEmpty() && !it.equals(file.name, ignoreCase = true) }?.let { " — $it" }.orEmpty()
    return if (version == null && alias.isEmpty()) null else "${file.name}$suffix$alias"
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

  override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
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
      return getDirectories().singleOrNull()
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
}
