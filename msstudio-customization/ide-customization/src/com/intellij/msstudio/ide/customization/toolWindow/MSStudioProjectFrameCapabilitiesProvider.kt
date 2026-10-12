// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ProjectFrameCapabilitiesProvider
import com.intellij.openapi.wm.ex.ProjectFrameCapability
import com.intellij.openapi.wm.ex.ProjectFrameUiPolicy
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider

/**
 * The frame capabilities of an MSStudio project.
 *
 * A server folder opens in the Files tool window instead of the Project view.
 * The welcome screen keeps the Project view.
 */
internal class MSStudioProjectFrameCapabilitiesProvider : ProjectFrameCapabilitiesProvider {
  override fun getCapabilities(project: Project): Set<ProjectFrameCapability> {
    if (WelcomeScreenProjectProvider.isWelcomeScreenProject(project)) {
      return emptySet()
    }
    return setOf(ProjectFrameCapability.SUPPRESS_PROJECT_VIEW)
  }

  override fun getUiPolicy(project: Project, capabilities: Set<ProjectFrameCapability>): ProjectFrameUiPolicy? {
    if (ProjectFrameCapability.WELCOME_EXPERIENCE in capabilities) {
      return null
    }
    return ProjectFrameUiPolicy(startupToolWindowIdToActivate = MSStudioServerFilesToolWindowFactory.TOOL_WINDOW_ID)
  }
}