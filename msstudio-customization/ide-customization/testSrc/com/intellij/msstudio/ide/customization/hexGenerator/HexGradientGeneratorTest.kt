// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.hexGenerator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HexGradientGeneratorTest {
  @Test
  fun `loads the bundled Monocraft font`() {
    assertThat(MonocraftFont.font.family).isEqualTo("Monocraft")
  }

  @Test
  fun `generates a MiniMessage gradient`() {
    val output = HexGradientGenerator.generate(
      HexGeneratorState(
        text = "ABC",
        colors = listOf(
          GradientStop(HexColor(255, 0, 0), 0),
          GradientStop(HexColor(0, 0, 255), 100),
        ),
      ),
    )

    assertThat(output).isEqualTo("<#FF0000>A<#800080>B<#0000FF>C")
  }

  @Test
  fun `generates each supported color format`() {
    val state = HexGeneratorState(text = "A", colors = listOf(GradientStop(HexColor(0x12, 0x34, 0x56), 0)))

    assertThat(HexGradientGenerator.generate(state.copy(format = MinecraftTextFormat.AMPERSAND_HEX))).isEqualTo("&#123456A")
    assertThat(HexGradientGenerator.generate(state.copy(format = MinecraftTextFormat.SECTION_HEX))).isEqualTo("§x§1§2§3§4§5§6A")
    assertThat(HexGradientGenerator.generate(state.copy(format = MinecraftTextFormat.AMPERSAND_EXPANDED_HEX))).isEqualTo("&x&1&2&3&4&5&6A")
    assertThat(HexGradientGenerator.generate(state.copy(format = MinecraftTextFormat.MINI_MESSAGE))).isEqualTo("<#123456>A")
    assertThat(HexGradientGenerator.generate(state.copy(format = MinecraftTextFormat.BB_CODE))).isEqualTo("[COLOR=#123456]A[/COLOR]")
  }

  @Test
  fun `keeps a code point intact`() {
    val output = HexGradientGenerator.generate(
      HexGeneratorState(text = "😀", colors = listOf(GradientStop(HexColor(1, 2, 3), 0))),
    )

    assertThat(output).isEqualTo("<#010203>😀")
  }

  @Test
  fun `reverses inverts and duplicates stops`() {
    val stops = listOf(GradientStop(HexColor(0, 0, 0), 0), GradientStop(HexColor(255, 255, 255), 100))

    assertThat(HexGradientGenerator.reverse(stops).map(GradientStop::color)).containsExactly(HexColor(255, 255, 255), HexColor(0, 0, 0))
    assertThat(HexGradientGenerator.invert(stops).map(GradientStop::color)).containsExactly(HexColor(255, 255, 255), HexColor(0, 0, 0))
    assertThat(HexGradientGenerator.duplicate(stops).map(GradientStop::position)).containsExactly(0, 50, 50, 100)
  }
}
