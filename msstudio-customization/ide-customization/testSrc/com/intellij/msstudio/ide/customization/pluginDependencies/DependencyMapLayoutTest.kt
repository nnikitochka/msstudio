// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.pluginDependencies

import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Kind
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Node
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Pt
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Rect
import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependencyMapLayoutTest {
  private fun manifest(name: String, depend: List<String> = emptyList()) =
    PluginManifest(name, "1.0", null, depend, emptyList(), emptyList())

  private fun layoutOf(vararg manifests: PluginManifest, widthOf: (Node) -> Int = { 170 }) =
      DependencyMapLayout(manifests.toList(), widthOf)

  private fun rectOf(layout: DependencyMapLayout, name: String) =
    layout.cards.single { it.node.name == name }.rect

  private fun hasInteriorIntersection(from: Pt, to: Pt, rect: Rect): Boolean {
    if (from.x == to.x) {
      return from.x > rect.left && from.x < rect.right && maxOf(from.y, to.y) > rect.top && minOf(from.y, to.y) < rect.bottom
    }
    if (from.y == to.y) {
      return from.y > rect.top && from.y < rect.bottom && maxOf(from.x, to.x) > rect.left && minOf(from.x, to.x) < rect.right
    }
    error("The router must make orthogonal segments: $from -> $to")
  }

  private fun intersection(from: Pt, to: Pt, otherFrom: Pt, otherTo: Pt): Pt? {
    val vertical = from.x == to.x
    val otherVertical = otherFrom.x == otherTo.x
    return when {
      vertical && otherVertical -> null
      !vertical && !otherVertical -> null
      vertical -> Pt(from.x, otherFrom.y).takeIf {
        it.y in minOf(from.y, to.y)..maxOf(from.y, to.y) && it.x in minOf(otherFrom.x, otherTo.x)..maxOf(otherFrom.x, otherTo.x)
      }
      else -> Pt(otherFrom.x, from.y).takeIf {
        it.x in minOf(from.x, to.x)..maxOf(from.x, to.x) && it.y in minOf(otherFrom.y, otherTo.y)..maxOf(otherFrom.y, otherTo.y)
      }
    }
  }

  private fun segmentsOverlap(firstFrom: Pt, firstTo: Pt, secondFrom: Pt, secondTo: Pt): Boolean = when {
    firstFrom.x == firstTo.x && secondFrom.x == secondTo.x ->
      firstFrom.x == secondFrom.x && maxOf(minOf(firstFrom.y, firstTo.y), minOf(secondFrom.y, secondTo.y)) <
      minOf(maxOf(firstFrom.y, firstTo.y), maxOf(secondFrom.y, secondTo.y))
    firstFrom.y == firstTo.y && secondFrom.y == secondTo.y ->
      firstFrom.y == secondFrom.y && maxOf(minOf(firstFrom.x, firstTo.x), minOf(secondFrom.x, secondTo.x)) <
      minOf(maxOf(firstFrom.x, firstTo.x), maxOf(secondFrom.x, secondTo.x))
    else -> false
  }

  private fun assertClearRoutes(layout: DependencyMapLayout) {
    assertThat(layout.unroutedEdges).isEmpty()
    layout.connectors.forEach { connection ->
      assertThat(connection.points).hasSizeGreaterThanOrEqualTo(2)
      connection.points.zipWithNext().forEach { (from, to) ->
        assertThat(from.x == to.x || from.y == to.y).isTrue()
        layout.cards.forEach { placement ->
          assertThat(hasInteriorIntersection(from, to, placement.rect))
            .`as`("%s -> %s crosses %s", connection.from.name, connection.to.name, placement.node.name)
            .isFalse()
        }
      }
    }
    layout.connectors.forEachIndexed { index, first ->
      layout.connectors.drop(index + 1).forEach { second ->
        first.points.zipWithNext().forEach { (firstFrom, firstTo) ->
          second.points.zipWithNext().forEach { (secondFrom, secondTo) ->
            assertThat(segmentsOverlap(firstFrom, firstTo, secondFrom, secondTo))
              .`as`("%s -> %s %s overlaps %s -> %s %s", first.from.name, first.to.name, first.points,
                    second.from.name, second.to.name, second.points)
              .isFalse()
            intersection(firstFrom, firstTo, secondFrom, secondTo)?.let { point ->
              assertThat(first.bridges + second.bridges).contains(point)
            }
          }
        }
      }
    }
  }

  @Test
  fun `every plugin renders one card`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("C", "Vault")),
      manifest("B", depend = listOf("C")),
      manifest("C"),
    )
    val names = layout.cards.map { it.node.name }
    assertThat(names).containsExactlyInAnyOrder("A", "B", "C", "Vault")
    assertThat(names.distinct()).hasSize(names.size)
  }

  @Test
  fun `every dependency card sits above its dependent`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("B")),
      manifest("B", depend = listOf("C")),
      manifest("C"),
      manifest("D", depend = listOf("C")),
    )
    listOf("A" to "B", "B" to "C", "D" to "C").forEach { (dependent, dependency) ->
      val parent = rectOf(layout, dependent)
      val child = rectOf(layout, dependency)
      assertThat(child.bottom).`as`("%s above %s", dependency, dependent).isLessThan(parent.top)
    }
  }

  @Test
  fun `cards size to their content`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("Wide")),
      manifest("Wide"),
      widthOf = { if (it.name == "Wide") 420 else 130 },
    )
    assertThat(rectOf(layout, "Wide").width).isEqualTo(420)
    assertThat(rectOf(layout, "A").width).isEqualTo(130)
    assertThat(rectOf(layout, "Wide").height).isEqualTo(layout.cardHeight)
  }

  @Test
  fun `arrows stay outside cards and each other`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("B", "C")),
      manifest("B", depend = listOf("D")),
      manifest("C", depend = listOf("D")),
      manifest("D"),
      manifest("E", depend = listOf("B", "D")),
    )
    assertClearRoutes(layout)
  }

  @Test
  fun `arrows route around a wrapped row`() {
    val deps = (1..12).map { "d%02d".format(it) }
    val layout = layoutOf(manifest("P", depend = deps))
    assertThat(layout.cards.count { it.node.kind == Kind.MISSING }).isEqualTo(12)
    assertClearRoutes(layout)
  }

  @Test
  fun `no two cards overlap`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("B", "C")),
      manifest("B", depend = listOf("D")),
      manifest("C", depend = listOf("D")),
      manifest("D"),
      manifest("E", depend = listOf("F")),
      manifest("F"),
      manifest("Solo"),
    )
    val rects = layout.cards.map { it.rect }
    rects.forEachIndexed { index, first ->
      rects.drop(index + 1).forEach { second ->
        val overlap = first.x < second.right && second.x < first.right && first.y < second.bottom && second.y < first.bottom
        assertThat(overlap).`as`("%s vs %s", first, second).isFalse()
      }
    }
  }

  @Test
  fun `a plugin without relations parks in a side column`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("B")),
      manifest("B"),
      manifest("Solo1"),
      manifest("Solo2"),
    )
    val mapRight = maxOf(rectOf(layout, "A").right, rectOf(layout, "B").right)
    assertThat(rectOf(layout, "Solo1").left).isGreaterThan(mapRight)
    assertThat(rectOf(layout, "Solo2").left).isGreaterThan(mapRight)
    assertThat(rectOf(layout, "Solo1").x).isEqualTo(rectOf(layout, "Solo2").x)
    assertThat(rectOf(layout, "Solo1").y).isNotEqualTo(rectOf(layout, "Solo2").y)
  }

  @Test
  fun `a wide row wraps at the line limit`() {
    val deps = (1..12).map { "d%02d".format(it) }
    val layout = layoutOf(manifest("P", depend = deps))
    val missingCards = layout.cards.filter { it.node.kind == Kind.MISSING }.map { it.rect }
    assertThat(missingCards).hasSize(12)
    val lines = missingCards.groupBy { it.bottom }
    assertThat(lines.keys).hasSizeGreaterThan(1)
    lines.values.forEach { line ->
      val span = line.maxOf { it.right } - line.minOf { it.x }
      assertThat(span).isLessThanOrEqualTo(layout.maxLineWidth)
    }
  }

  @Test
  fun `the bounds hold every card and arrow`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("B")),
      manifest("B", depend = listOf("C")),
      manifest("C"),
      manifest("Solo"),
    )
    layout.cards.forEach { placement ->
      assertThat(placement.rect.x).isGreaterThanOrEqualTo(0)
      assertThat(placement.rect.y).isGreaterThanOrEqualTo(0)
      assertThat(placement.rect.right).isLessThanOrEqualTo(layout.bounds.width)
      assertThat(placement.rect.bottom).isLessThanOrEqualTo(layout.bounds.height)
    }
    layout.connectors.flatMap { it.points }.forEach { point ->
      assertThat(point.x).isBetween(0, layout.bounds.width)
      assertThat(point.y).isBetween(0, layout.bounds.height)
    }
  }

  @Test
  fun `a cycle renders each plugin once with a clear alarm arrow`() {
    val layout = layoutOf(
      manifest("A", depend = listOf("B")),
      manifest("B", depend = listOf("A")),
    )
    val names = layout.cards.map { it.node.name }
    assertThat(names).containsExactlyInAnyOrder("A", "B")
    assertThat(names.distinct()).hasSize(names.size)
    assertThat(layout.cards.map { it.node.inCycle }).containsOnly(true)
    assertThat(layout.connectors.any { it.alarm }).isTrue()
    assertClearRoutes(layout)
  }
}
