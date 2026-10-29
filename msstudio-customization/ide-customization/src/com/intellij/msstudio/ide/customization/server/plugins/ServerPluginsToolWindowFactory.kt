// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.plugins

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.ui.content.ContentFactory

/**
 * The factory of the Plugins tool window.
 */
class ServerPluginsToolWindowFactory : ToolWindowFactory {
  override suspend fun isApplicableAsync(project: Project): Boolean {
    return !WelcomeScreenProjectProvider.isWelcomeScreenProject(project)
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val cache = ServerPluginsCache(project)
    val foldersPanel = ServerPluginsPanel(project, toolWindow.disposable, cache)
    val contentFactory = ContentFactory.getInstance()
    toolWindow.contentManager.addContent(contentFactory.createContent(
      foldersPanel,
      null,
      false
    ))
    toolWindow.setTitleActions(listOf(ActionManager.getInstance().getAction(OpenPluginDependencyGraphAction.ACTION_ID)))
    toolWindow.setAdditionalGearActions(DefaultActionGroup(foldersPanel.createRefreshAction()))
  }
}
