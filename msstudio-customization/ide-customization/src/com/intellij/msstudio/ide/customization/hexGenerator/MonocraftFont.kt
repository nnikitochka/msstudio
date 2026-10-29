// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.hexGenerator

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Font

internal object MonocraftFont {
  private const val RESOURCE_PATH = "/fonts/Monocraft-Regular.ttf"
  private const val FONT_SIZE = 16f

  val font: Font by lazy {
    val fallback = JBFont.create(JBUI.Fonts.label()).deriveFont(JBUI.scale(FONT_SIZE))
    val resource = MonocraftFont::class.java.getResource(RESOURCE_PATH)
    if (resource == null) {
      thisLogger().warn("Resource missing: $RESOURCE_PATH")
      return@lazy fallback
    }

    try {
      resource.openStream().use { stream -> Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(JBUI.scale(FONT_SIZE)) }
    } catch (error: Exception) {
      thisLogger().warn("Cannot load the Monocraft font.", error)
      fallback
    }
  }
}
