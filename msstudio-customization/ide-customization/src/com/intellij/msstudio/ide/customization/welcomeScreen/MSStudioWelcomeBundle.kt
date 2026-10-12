// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.welcomeScreen

import com.intellij.DynamicBundle

internal const val MS_STUDIO_WELCOME_BUNDLE: String = "messages.MSStudioWelcomeBundle"

/**
 * The texts of the MSStudio welcome screen.
 */
internal object MSStudioWelcomeBundle {
  private val bundle = DynamicBundle(MSStudioWelcomeBundle::class.java, MS_STUDIO_WELCOME_BUNDLE)

  fun message(key: String, vararg params: Any): String = bundle.getMessage(key, *params)
}
