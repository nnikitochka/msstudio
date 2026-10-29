// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.minecraftColors

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MinecraftColorParserTest {
  @Test
  fun `parses MiniMessage named and hex colors`() {
    val text = "<red>Red</red> <#123456>hex</#123456>"

    assertThat(previewText(text)).containsExactly(
      "Red" to 0xFF5555,
      " " to null,
      "hex" to 0x123456,
    )
  }

  @Test
  fun `parses legacy section and ampersand colors`() {
    val text = "§aGreen &x&1&2&3&4&5&6Hex &#ABCDEFRgb &rPlain"

    assertThat(previewText(text)).containsExactly(
      "Green " to 0x55FF55,
      "Hex " to 0x123456,
      "Rgb " to 0xABCDEF,
      "Plain" to null,
    )
  }

  @Test
  fun `restores the enclosing MiniMessage color`() {
    val text = "<red>Red <blue>blue</blue> red</red>"

    assertThat(previewText(text)).containsExactly(
      "Red " to 0xFF5555,
      "blue" to 0x5555FF,
      " red" to 0xFF5555,
    )
  }

  @Test
  fun `removes MiniMessage style tags`() {
    val preview = MinecraftColorParser.parsePreview("<red><bold>Hello</bold></red>")

    assertThat(preview.hasFormatting).isTrue()
    assertThat(previewText("<red><bold>Hello</bold></red>")).containsExactly("Hello" to 0xFF5555)
    assertThat(preview.parts.single().decorations).containsExactly(MinecraftDecoration.BOLD)
  }

  @Test
  fun `parses MiniMessage color and decoration aliases`() {
    val preview = MinecraftColorParser.parsePreview("<colour:dark_grey><em><u>Text</u></em></colour>")

    assertThat(previewText("<colour:dark_grey><em><u>Text</u></em></colour>")).containsExactly("Text" to 0x555555)
    assertThat(preview.parts.single().decorations).containsExactlyInAnyOrder(
      MinecraftDecoration.ITALIC,
      MinecraftDecoration.UNDERLINED,
    )
  }

  @Test
  fun `parses legacy decorations and resets them after a color`() {
    val preview = MinecraftColorParser.parsePreview("&lBold &oItalic &cRed")

    assertThat(preview.parts.map(MinecraftPreviewPart::text)).containsExactly("Bold ", "Italic ", "Red")
    assertThat(preview.parts[0].decorations).containsExactly(MinecraftDecoration.BOLD)
    assertThat(preview.parts[1].decorations).containsExactlyInAnyOrder(MinecraftDecoration.BOLD, MinecraftDecoration.ITALIC)
    assertThat(preview.parts[2].decorations).isEmpty()
    assertThat(preview.parts[2].color?.rgb?.and(0xFFFFFF)).isEqualTo(0xFF5555)
  }

  @Test
  fun `renders MiniMessage color transforms`() {
    val preview = MinecraftColorParser.parsePreview("<gradient:red:blue>AB</gradient><rainbow>CD</rainbow><pride:trans>EF</pride>")

    assertThat(preview.parts.joinToString(separator = "") { it.text }).isEqualTo("ABCDEF")
    assertThat(preview.parts.map { it.color }).doesNotContainNull()
  }

  @Test
  fun `parses escaped and MiniMessage line breaks`() {
    val preview = MinecraftColorParser.parsePreview("<green>One\\nTwo<nl>Three<newline>Four<br>Five</green>")

    assertThat(preview.hasFormatting).isTrue()
    assertThat(preview.parts.joinToString(separator = "") { it.text }).isEqualTo("One\nTwo\nThree\nFour\nFive")
    assertThat(preview.parts).allSatisfy { part ->
      assertThat(part.color?.rgb?.and(0xFFFFFF)).isEqualTo(0x55FF55)
    }
  }

  private fun previewText(text: String): List<Pair<String, Int?>> =
    MinecraftColorParser.parsePreview(text).parts.map { part ->
      part.text to part.color?.rgb?.and(0xFFFFFF)
    }
}
