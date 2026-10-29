// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.hexGenerator

import java.awt.Color
import kotlin.math.roundToInt

internal data class HexColor(val red: Int, val green: Int, val blue: Int) {
  init {
    require(red in 0..255)
    require(green in 0..255)
    require(blue in 0..255)
  }

  fun toAwtColor(): Color = Color(red, green, blue)

  fun toHex(): String = "#%02X%02X%02X".format(red, green, blue)

  fun inverted(): HexColor = HexColor(255 - red, 255 - green, 255 - blue)

  companion object {
    fun parse(value: String): HexColor? {
      val digits = value.removePrefix("#")
      if (digits.length != 6 || !digits.all(Char::isDigitOrHex)) return null
      return HexColor(
        digits.substring(0, 2).toInt(16),
        digits.substring(2, 4).toInt(16),
        digits.substring(4, 6).toInt(16),
      )
    }
  }
}

private fun Char.isDigitOrHex(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

internal data class GradientStop(
  val color: HexColor,
  val position: Int,
) {
  init {
    require(position in 0..100)
  }
}

internal enum class MinecraftTextFormat(
  val messageKey: String,
) {
  AMPERSAND_HEX("msstudio.hex.format.ampersandHex"),
  SECTION_HEX("msstudio.hex.format.sectionHex"),
  AMPERSAND_EXPANDED_HEX("msstudio.hex.format.ampersandExpandedHex"),
  MINI_MESSAGE("msstudio.hex.format.miniMessage"),
  JSON("msstudio.hex.format.json"),
  BB_CODE("msstudio.hex.format.bbCode"),
}

internal data class HexGeneratorState(
  val text: String = "MSStudio",
  val colors: List<GradientStop> = listOf(
    GradientStop(HexColor(0x54, 0xDA, 0xF4), 0),
    GradientStop(HexColor(0x54, 0x5E, 0xB6), 100),
  ),
  val format: MinecraftTextFormat = MinecraftTextFormat.MINI_MESSAGE,
)

internal object HexGradientGenerator {
  fun generate(state: HexGeneratorState): String {
    val characters = state.text.toCodePointStrings()
    if (characters.isEmpty()) return ""
    val colors = generateColors(characters.size, state.colors)
    return when (state.format) {
      MinecraftTextFormat.AMPERSAND_HEX -> formatLegacy(characters, colors, '&', compact = true)
      MinecraftTextFormat.SECTION_HEX -> formatLegacy(characters, colors, '§', compact = false)
      MinecraftTextFormat.AMPERSAND_EXPANDED_HEX -> formatLegacy(characters, colors, '&', compact = false)
      MinecraftTextFormat.MINI_MESSAGE -> formatMiniMessage(characters, colors)
      MinecraftTextFormat.JSON -> formatJson(characters, colors)
      MinecraftTextFormat.BB_CODE -> formatBbCode(characters, colors)
    }
  }

  fun generateColors(characterCount: Int, stops: List<GradientStop>): List<HexColor> {
    if (characterCount <= 0) return emptyList()
    return List(characterCount) { index -> colorAt(stops, colorPosition(index, characterCount)) }
  }

  fun reverse(stops: List<GradientStop>): List<GradientStop> =
    stops.map { it.copy(position = 100 - it.position) }.sortedBy(GradientStop::position)

  fun invert(stops: List<GradientStop>): List<GradientStop> = stops.map { it.copy(color = it.color.inverted()) }

  fun duplicate(stops: List<GradientStop>): List<GradientStop> =
    stops.flatMap { stop -> listOf(stop.copy(position = stop.position / 2), stop.copy(position = 50 + stop.position / 2)) }
      .sortedBy(GradientStop::position)

  private fun colorPosition(index: Int, total: Int): Int {
    if (total <= 1) return 0
    return index * 100 / (total - 1)
  }

  private fun colorAt(stops: List<GradientStop>, position: Int): HexColor {
    val sortedStops = stops.sortedBy(GradientStop::position)
    if (sortedStops.isEmpty()) return HexColor(255, 255, 255)
    if (sortedStops.size == 1 || position <= sortedStops.first().position) return sortedStops.first().color
    if (position >= sortedStops.last().position) return sortedStops.last().color
    val rightIndex = sortedStops.indexOfFirst { it.position >= position }
    val start = sortedStops[rightIndex - 1]
    val end = sortedStops[rightIndex]
    val fraction = (position - start.position).toDouble() / (end.position - start.position)
    return interpolateRgb(start.color, end.color, fraction)
  }

  private fun interpolateRgb(start: HexColor, end: HexColor, fraction: Double): HexColor = HexColor(
    (start.red + (end.red - start.red) * fraction).roundToInt(),
    (start.green + (end.green - start.green) * fraction).roundToInt(),
    (start.blue + (end.blue - start.blue) * fraction).roundToInt(),
  )

  private fun formatLegacy(
    characters: List<String>,
    colors: List<HexColor>,
    marker: Char,
    compact: Boolean,
  ): String = buildString {
    for ((index, character) in characters.withIndex()) {
      append(if (compact) "$marker${colors[index].toHex()}" else expandedHex(colors[index], marker))
      append(character)
    }
  }

  private fun formatMiniMessage(characters: List<String>, colors: List<HexColor>): String = buildString {
    for ((index, character) in characters.withIndex()) {
      append("<${colors[index].toHex()}>")
      append(character)
    }
  }

  private fun formatJson(characters: List<String>, colors: List<HexColor>): String = buildString {
    append("{\"text\":\"\",\"extra\":[")
    characters.forEachIndexed { index, character ->
      if (index > 0) append(',')
      append("{\"text\":\"")
      append(character.jsonEscaped())
      append("\",\"color\":\"")
      append(colors[index].toHex())
      append("\"}")
    }
    append("]}")
  }

  private fun formatBbCode(characters: List<String>, colors: List<HexColor>): String = buildString {
    for ((index, character) in characters.withIndex()) {
      append("[COLOR=${colors[index].toHex()}]")
      append(character)
      append("[/COLOR]")
    }
  }

  private fun expandedHex(color: HexColor, marker: Char): String = buildString {
    append(marker)
    append('x')
    for (digit in color.toHex().removePrefix("#")) {
      append(marker)
      append(digit)
    }
  }
}

private fun String.jsonEscaped(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

private fun String.toCodePointStrings(): List<String> =
  codePoints().toArray().map(Character::toString)
