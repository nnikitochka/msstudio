// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.screen

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.OpenFileAction
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The Open item of the MSStudio welcome screen. It opens the folder of a Minecraft server as a project.
 */
internal class OpenServerAction : OpenFileAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.text = MSStudioBundle.actionWelcomeOpenText
    e.presentation.icon = AllIcons.Nodes.Folder
  }

  override fun createFileChooserDescriptor(project: Project?, fileToSelect: VirtualFile?): FileChooserDescriptor {
    return FileChooserDescriptorFactory.createSingleFolderDescriptor()
      .withTitle(MSStudioBundle.dialogOpenServerTitle)
  }
}
