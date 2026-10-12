// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.projectView.impl.ProjectViewDeleteElementProvider
import com.intellij.msstudio.ide.customization.welcomeScreen.MSStudioWelcomeBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
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
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The panel of the Server Files tool window.
 *
 * It shows the files of a folder as a plain file system tree. The initial
 * folder is the base directory of the project.
 */
internal class MSStudioServerFilesPanel(
  private val project: Project,
  parentDisposable: Disposable,
) : JPanel(BorderLayout()), DataProvider, UiDataProvider {
  private val treeHolder = JPanel(BorderLayout())
  private var fileSystemTree: FileSystemTreeImpl? = null
  private var currentFolder: VirtualFile? = null
  private var showHiddenFiles = false
  private val copyPasteDelegator = CopyPasteDelegator(project, this)
  private val ideView = ServerFilesIdeView()
  private val deleteProvider = object : ProjectViewDeleteElementProvider() {
    override fun getSelectedPSIElements(dataContext: DataContext): Array<PsiElement> {
      return selectedPsiElements().toTypedArray()
    }

    override fun hideEmptyMiddlePackages(dataContext: DataContext): Boolean = false
  }

  init {
    add(treeHolder, BorderLayout.CENTER)

    showFolder(project.basePath)

    Disposer.register(parentDisposable, Disposable { disposeTree() })
  }

  /**
   * The toggle for the "..." menu of the tool window. It shows or hides hidden files.
   */
  fun createShowHiddenFilesToggle(): ToggleAction {
    return object : ToggleAction(MSStudioWelcomeBundle.message("msstudio.serverFiles.hiddenFiles.text")), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

      override fun isSelected(e: AnActionEvent): Boolean = showHiddenFiles

      override fun setSelected(e: AnActionEvent, state: Boolean) {
        showHiddenFiles = state
        fileSystemTree?.showHiddens(state)
      }
    }
  }

  private fun showFolder(path: String?) {
    val folder = path?.let {
      val url = VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, it)
      VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
    }
    currentFolder = folder
    rebuildTree(folder)
  }

  private fun rebuildTree(folder: VirtualFile?) {
    disposeTree()
    val descriptor = FileChooserDescriptorFactory.multiFilesOrDirs()
      .withTreeRootVisible(false)
      .withShowHiddenFiles(showHiddenFiles)
    if (folder != null) descriptor.withRoots(folder)
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
