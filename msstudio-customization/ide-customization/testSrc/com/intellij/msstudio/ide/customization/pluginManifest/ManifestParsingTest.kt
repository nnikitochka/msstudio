// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.pluginManifest

import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifestParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManifestParsingTest {
  @Test
  fun `reads scalar fields with quotes and comments`() {
    val manifest = PluginManifestParser.parse(
      """
      name: 'EssentialsX'
      version: "2.20.1"
      description: Essential commands # the core suite
      main: com.earth2me.essentials.Essentials
      """.trimIndent()
    )
    assertThat(manifest.name).isEqualTo("EssentialsX")
    assertThat(manifest.version).isEqualTo("2.20.1")
    assertThat(manifest.description).isEqualTo("Essential commands")
  }

  @Test
  fun `reads an inline list`() {
    val manifest = PluginManifestParser.parse(
      """
      name: A
      depend: [Vault, LuckPerms]
      softdepend: 'PlaceholderAPI'
      """.trimIndent()
    )
    assertThat(manifest.depend).containsExactly("Vault", "LuckPerms")
    assertThat(manifest.softdepend).containsExactly("PlaceholderAPI")
  }

  @Test
  fun `reads a block list and the field after it`() {
    val manifest = PluginManifestParser.parse(
      """
      name: A
      depend:
        - Vault
        - 'LuckPerms'
      version: 1.0
      """.trimIndent()
    )
    assertThat(manifest.depend).containsExactly("Vault", "LuckPerms")
    assertThat(manifest.version).isEqualTo("1.0")
  }

  @Test
  fun `lists are empty when the manifest holds none`() {
    val manifest = PluginManifestParser.parse("name: A\nversion: 1.0\n")
    assertThat(manifest.depend).isEmpty()
    assertThat(manifest.softdepend).isEmpty()
    assertThat(manifest.loadbefore).isEmpty()
  }
}
