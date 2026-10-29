// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.logs

import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The panel of the Logs tool window.
 *
 * It lists the log files of the server folder, newest first. Plain logs open
 * in the editor directly. Archived logs decompress on open into the session
 * cache and open as plain files.
 */
internal class ServerLogsPanel(
  private val project: Project,
  parentDisposable: Disposable,
) : JPanel(BorderLayout()) {
  private val treeHolder = JPanel(BorderLayout())
  private var tree: Tree? = null
  private val logCache = LogFileCache(project)

  init {
    add(treeHolder, BorderLayout.CENTER)
    refresh()
  }

  /**
   * The refresh action for the "..." menu of the tool window.
   */
  fun createRefreshAction(): AnAction {
    return object : AnAction(MSStudioBundle.serverLogsRefreshText), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun actionPerformed(e: AnActionEvent) {
        refresh()
      }
    }
  }

  private fun refresh() {
    logCache.cleanup()
    val logsDir = logsDir()
    treeHolder.removeAll()
    if (logsDir == null) {
      treeHolder.add(JBLabel(
        MSStudioBundle.serverLogsFolderMissing,
        SwingConstants.CENTER
      ), BorderLayout.CENTER)
    }
    else {
      val newTree = createTree(logsDir)
      tree = newTree
      treeHolder.add(ScrollPaneFactory.createScrollPane(newTree, true), BorderLayout.CENTER)
    }
    treeHolder.revalidate()
    treeHolder.repaint()
  }

  private fun logsDir(): VirtualFile? {
    val basePath = project.basePath ?: return null
    val url = VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, "$basePath/logs")
    return VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
  }

  private fun createTree(logsDir: VirtualFile): Tree {
    val files = logsDir.children
      .filter { it.isValid && !it.isDirectory && isLogFile(it.name) }
      .sortedWith(compareByDescending<VirtualFile> { it.timeStamp }.thenBy { it.name })
    val root = DefaultMutableTreeNode(null)
    for (file in files) {
      root.add(DefaultMutableTreeNode(file))
    }
    val newTree = Tree(DefaultTreeModel(root))
    newTree.isRootVisible = false
    newTree.cellRenderer = LogCellRenderer()
    TreeSpeedSearch.installOn(newTree, false) { path ->
      ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? VirtualFile)?.name
    }
    object : DoubleClickListener() {
      override fun onDoubleClick(e: MouseEvent): Boolean {
        openSelected()
        return true
      }
    }.installOn(newTree)
    newTree.registerKeyboardAction(
      { openSelected() },
      KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
      WHEN_FOCUSED
    )
    return newTree
  }

  private fun openSelected() {
    val node = tree?.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
    val file = node.userObject as? VirtualFile ?: return
    if (!file.isValid || file.isDirectory) return
    if (isArchive(file.name)) {
      openArchive(file)
    }
    else {
      openReadOnly(file)
    }
  }

  private fun openArchive(file: VirtualFile) {
    ProgressManager.getInstance().run(
      object : Task.Backgroundable(project, MSStudioBundle.serverLogsDecompressTitle, true) {
        override fun run(indicator: ProgressIndicator) {
          try {
            val cached = logCache.decompressed(file, indicator)
          invokeLater {
            if (!project.isDisposed) {
              openReadOnly(cached)
            }
          }
        }
        catch (e: Exception) {
          invokeLater {
            if (!project.isDisposed) {
              Messages.showErrorDialog(
                project,
                MSStudioBundle.serverLogsDecompressErrorMessage( file.name),
                MSStudioBundle.serverLogsDecompressErrorTitle
              )
            }
          }
        }
      }
    })
  }

  private fun openReadOnly(file: VirtualFile) {
    FileEditorManager.getInstance(project).openFile(file, true).forEach { editor ->
      (editor as? TextEditor)?.editor?.document?.setReadOnly(true)
    }
  }

  private class LogCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
      tree: JTree,
      value: Any?,
      selected: Boolean,
      expanded: Boolean,
      leaf: Boolean,
      row: Int,
      hasFocus: Boolean
    ) {
      val file = (value as? DefaultMutableTreeNode)?.userObject as? VirtualFile ?: return
      icon = FileTypeManager.getInstance().getFileTypeByFileName(file.name.removeSuffix(".gz")).icon
      appendDisplayName(file.name)
    }

    private fun appendDisplayName(name: String) {
      val match = LOG_FILE_NAME.matchEntire(name)
      if (match == null) {
        append(name)
        return
      }
      append("${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}")
      val number = match.groupValues[4]
      if (number.isNotEmpty()) {
        append(" ($number)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
  }

  companion object {
    private val LOG_FILE_NAME: Regex = Regex("""(\d{4})-(\d{2})-(\d{2})(?:-(\d+))?\.log(?:\.gz)?""",
                                             RegexOption.IGNORE_CASE)
    private fun isLogFile(name: String): Boolean {
      return name.endsWith(".log", ignoreCase = true) || name.endsWith(".log.gz", ignoreCase = true)
    }

    private fun isArchive(name: String): Boolean {
      return name.endsWith(".gz", ignoreCase = true)
    }
  }
}
