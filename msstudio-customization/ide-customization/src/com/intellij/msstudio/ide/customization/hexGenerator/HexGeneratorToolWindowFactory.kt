// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.hexGenerator

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.ui.content.ContentFactory

class HexGeneratorToolWindowFactory : ToolWindowFactory {
  override suspend fun isApplicableAsync(project: Project): Boolean = !WelcomeScreenProjectProvider.isWelcomeScreenProject(project)

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = HexGeneratorPanel()
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel.component, null, false))
  }

  companion object {
    const val TOOL_WINDOW_ID: String = "Hex"
  }
}
