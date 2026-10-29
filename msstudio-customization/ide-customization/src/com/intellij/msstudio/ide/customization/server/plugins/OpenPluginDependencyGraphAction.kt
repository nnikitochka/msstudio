// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.plugins

import com.intellij.msstudio.ide.customization.plugin.dependencies.PluginDependenciesEditorProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class OpenPluginDependencyGraphAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.let(PluginDependenciesEditorProvider::open)
  }

  companion object {
    const val ACTION_ID = "MSStudio.Plugins.OpenDependencyGraph"
  }
}
