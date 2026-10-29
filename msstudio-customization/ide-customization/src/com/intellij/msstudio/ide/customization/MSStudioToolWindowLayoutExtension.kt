// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization

import com.intellij.toolWindow.DefaultToolWindowLayoutBuilder
import com.intellij.toolWindow.DefaultToolWindowLayoutExtension

/**
 * Shapes the default tool window layout.
 *
 * Adds the MSStudio tool windows and removes unwanted tool windows from the layout.
 */
internal class MSStudioToolWindowLayoutExtension : DefaultToolWindowLayoutExtension {
  override fun buildV1Layout(builder: DefaultToolWindowLayoutBuilder) {
    addMSStudioToolWindows(builder)
  }

  override fun buildV2Layout(builder: DefaultToolWindowLayoutBuilder) {
    addMSStudioToolWindows(builder)
  }

  private fun addMSStudioToolWindows(builder: DefaultToolWindowLayoutBuilder) {
    builder.left.addOrUpdate("Files") { seedIntoExistingLayout() }
    builder.left.addOrUpdate("Plugins") { seedIntoExistingLayout() }
    builder.bottom.addOrUpdate("Hex") { seedIntoExistingLayout() }
    builder.right.addOrUpdate("Logs") { seedIntoExistingLayout() }
    builder.removeAll { it.id == "Structure" || it.id == "Services" || it.id == "Coverage" || it.id == "Hierarchy" }
  }
}
