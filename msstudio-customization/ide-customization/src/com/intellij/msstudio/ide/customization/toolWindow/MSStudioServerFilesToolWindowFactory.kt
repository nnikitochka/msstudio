// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.ui.content.ContentFactory

/**
 * The factory of the Files tool window.
 *
 * The window is a copy of the Project window, but it shows the files of
 * a folder instead of the project structure.
 */
class MSStudioServerFilesToolWindowFactory : ToolWindowFactory {
  override suspend fun isApplicableAsync(project: Project): Boolean {
    return !WelcomeScreenProjectProvider.isWelcomeScreenProject(project)
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = MSStudioServerFilesPanel(project, toolWindow.disposable)
    val content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
    toolWindow.setAdditionalGearActions(DefaultActionGroup(panel.createShowHiddenFilesToggle()))
  }

  companion object {
    const val TOOL_WINDOW_ID: String = "Files"
  }
}