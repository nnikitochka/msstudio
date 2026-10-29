// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.run

import com.intellij.icons.AllIcons
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.msstudio.ide.customization.server.core.ServerCoreService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Runs or stops the detected server.
 */
internal class ServerRunAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val service = ServerRunService.getInstance(project)
    if (service.isActive) service.stop()
    else service.start()
  }

  override fun update(event: AnActionEvent) {
    val project = event.project
    if (project == null || project.isDisposed) {
      event.presentation.isEnabledAndVisible = false
      return
    }

    val core = ServerCoreService.getInstance(project).detectedCore
    val service = ServerRunService.getInstance(project)
    if (service.isActive) {
      event.presentation.text = MSStudioBundle.serverRunStopText(core?.presentableName() ?: project.name)
      event.presentation.description = MSStudioBundle.serverRunStopDescription
      event.presentation.icon = AllIcons.Actions.Suspend
      event.presentation.isEnabled = !service.isStopping
    }
    else {
      event.presentation.text = core?.let { MSStudioBundle.serverRunStartText(it.presentableName()) }
                                ?: MSStudioBundle.serverRunText
      event.presentation.description = MSStudioBundle.serverRunDescription
      event.presentation.icon = AllIcons.Actions.Execute
      event.presentation.isEnabled = core != null
    }
    event.presentation.isVisible = true
  }
}
