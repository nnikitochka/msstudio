// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.dependencies

import com.intellij.icons.AllIcons
import com.intellij.msstudio.ide.customization.server.plugins.ServerPluginsCache
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The file type of the plugin dependency tree tab.
 */
object PluginDependenciesFileType : FileType {
  override fun getName(): String = "MSSTUDIO_PLUGIN_DEPENDENCIES"

  override fun getDescription(): String =
    MSStudioBundle.serverPluginsEditorFileTypeDescription

  override fun getDefaultExtension(): String = ""

  override fun getIcon(): Icon = AllIcons.Actions.DependencyAnalyzer

  override fun isBinary(): Boolean = false

  override fun isReadOnly(): Boolean = true
}

/**
 * Opens the plugin dependency cards as an editor tab, beside the file tabs.
 */
class PluginDependenciesEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean {
    return file.fileType === PluginDependenciesFileType
  }

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    return PluginDependenciesEditor(project, file)
  }

  override fun getEditorTypeId(): String = "msstudio-plugin-dependencies"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

  internal class PluginDependenciesEditor(
    private val project: Project,
    private val file: VirtualFile,
  ) : UserDataHolderBase(), FileEditor, DumbAware {
    private val cache = ServerPluginsCache(project)
    private val panel = DependencyCardsPanel(project, cache)

    init {
      val group = DefaultActionGroup(
        panel.createRefreshAction(),
        panel.createZoomInAction(),
        panel.createZoomOutAction(),
        panel.createZoomResetAction(),
      )
      val toolbar = ActionManager.getInstance()
        .createActionToolbar("MSSTUDIO_PLUGIN_DEPENDENCIES", group, true)
      toolbar.targetComponent = panel
      panel.add(toolbar.component, BorderLayout.NORTH)
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocus

    override fun getName(): String = MSStudioBundle.serverPluginsEditorName

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}
  }

  companion object {
    /**
     * Opens the dependency graph tab, or focuses it when it is already open.
     *
     * The file of the tab stays in a registered non-physical file system, so the IDE can reopen the tab
     * by its URL on the next project start.
     */
    fun open(project: Project) {
      FileEditorManager.getInstance(project).openFile(PluginDependenciesGraphFile, true)
    }
  }
}
