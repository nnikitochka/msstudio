// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.manifest

/**
 * The fields of a Bukkit or Paper plugin manifest that MSStudio reads.
 */
data class PluginManifest(
  val name: String?,
  val version: String?,
  val description: String?,
  val depend: List<String>,
  val softdepend: List<String>,
  val loadbefore: List<String>,
)
