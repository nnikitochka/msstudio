// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.welcomeScreen

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/**
 * The New item of the MSStudio welcome screen. It starts the creation of a Minecraft server.
 *
 * The server creation wizard is not implemented yet: the action shows a placeholder message.
 */
internal class MSStudioNewServerAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    Messages.showInfoMessage(
      e.project,
      MSStudioWelcomeBundle.message("dialog.new.server.message"),
      MSStudioWelcomeBundle.message("dialog.new.server.title"),
    )
  }
}
