// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Finds the server core when a server folder opens as a project.
 *
 * It runs once after the project startup. It stores the result
 * in the project core service for the tool windows.
 */
internal class ServerCoreStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    ServerCoreService.getInstance(project).refresh()
  }
}
