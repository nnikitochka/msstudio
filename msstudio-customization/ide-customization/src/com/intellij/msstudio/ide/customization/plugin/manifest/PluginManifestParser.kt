// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.manifest

/**
 * The parser of the manifest text.
 *
 * It reads the top-level fields only, and it accepts a block list and an
 * inline list form. The manifests of server plugins hold no deeper
 * structure in these fields, so a hand-written parser is enough.
 */
object PluginManifestParser {
  private val FIELD: Regex = Regex("^(name|version|description|depend|softdepend|loadbefore):[ \\t]*(.*)$")
  private val LIST_ITEM: Regex = Regex("^[ \\t]+-[ \\t]+(.*)$")

  fun parse(text: String): PluginManifest {
    var name: String? = null
    var version: String? = null
    var description: String? = null
    val lists = HashMap<String, MutableList<String>>()
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
      val line = lines[index]
      index++
      val match = FIELD.matchEntire(line) ?: continue
      val key = match.groupValues[1]
      val value = match.groupValues[2].trim()
      when (key) {
        "name" -> name = clean(value)
        "version" -> version = clean(value)
        "description" -> description = clean(value)
        else -> index = parseList(value, lines, index, key, lists)
      }
    }
    return PluginManifest(
      name = name,
      version = version,
      description = description,
      depend = lists["depend"] ?: emptyList(),
      softdepend = lists["softdepend"] ?: emptyList(),
      loadbefore = lists["loadbefore"] ?: emptyList(),
    )
  }

  /**
   * Reads the items of one list field. [index] points at the first line
   * after the field line. The function returns the index of the first
   * line it did not consume.
   */
  private fun parseList(
    value: String,
    lines: List<String>,
    index: Int,
    key: String,
    lists: HashMap<String, MutableList<String>>,
  ): Int {
    val items = mutableListOf<String>()
    var next = index
    if (value.isEmpty()) {
      while (next < lines.size) {
        val item = LIST_ITEM.matchEntire(lines[next]) ?: break
        next++
        val cleaned = clean(item.groupValues[1])
        if (cleaned.isNotEmpty()) items += cleaned
      }
    }
    else if (value.startsWith("[") && value.endsWith("]")) {
      value.substring(1, value.length - 1).split(',').forEach {
        val cleaned = clean(it)
        if (cleaned.isNotEmpty()) items += cleaned
      }
    }
    else {
      val cleaned = clean(value)
      if (cleaned.isNotEmpty()) items += cleaned
    }
    lists[key] = items
    return next
  }

  private fun clean(raw: String): String {
    var value = raw.trim()
    val comment = value.indexOf(" #")
    if (comment >= 0) value = value.substring(0, comment).trim()
    return value.removeSurrounding("\"").removeSurrounding("'")
  }
}
