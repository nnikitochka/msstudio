// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.ProductIcons
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Project folders show a folder glyph instead of the product logo.
 *
 * The platform fallback for non-JetBrains products dresses every project node with the product icon.
 * That fits a 16 px glyph, not a full logo, so MSStudio states its own mapping here.
 */
internal class MSStudioProductIcons : ProductIcons {
  override fun getProductIcon(): Icon =
    IconLoader.getIcon(ApplicationInfoEx.getInstanceEx().smallApplicationSvgIconUrl, javaClass.classLoader)

  override fun getProjectIcon(): Icon = AllIcons.Nodes.Folder

  override fun getProjectNodeIcon(): Icon = AllIcons.Nodes.Folder
}
