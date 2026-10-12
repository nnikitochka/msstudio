// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.ui.content.ContentFactory

class MSStudioServerPluginsToolWindowFactory : ToolWindowFactory {
  override suspend fun isApplicableAsync(project: Project): Boolean = !WelcomeScreenProjectProvider.isWelcomeScreenProject(project)

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = MSStudioServerPluginsPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    toolWindow.setAdditionalGearActions(DefaultActionGroup(panel.createRefreshAction(), panel.createHiddenFilesToggle()))
  }

  companion object {
    const val TOOL_WINDOW_ID = "Plugins"
  }
}
