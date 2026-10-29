// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.pluginDependencies

import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyGraph
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyGraph.CollaboratorKind
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyGraph.Status
import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependencyGraphTest {
  private fun manifest(
    name: String,
    depend: List<String> = emptyList(),
    softdepend: List<String> = emptyList(),
    loadbefore: List<String> = emptyList(),
  ) = PluginManifest(name, "1.0", null, depend, softdepend, loadbefore)

  @Test
  fun `an installed depend is ok`() {
    val graph = DependencyGraph(listOf(manifest("A", depend = listOf("B")), manifest("B")))
    val collaborators = graph.collaboratorsOf(graph.cards().single { it.name == "A" })
    assertThat(collaborators.single().status).isEqualTo(Status.OK)
  }

  @Test
  fun `a missing depend is an error`() {
    val graph = DependencyGraph(listOf(manifest("A", depend = listOf("B"))))
    val collaborators = graph.collaboratorsOf(graph.cards().single())
    assertThat(collaborators.single().status).isEqualTo(Status.MISSING_DEPEND)
    assertThat(collaborators.single().kind).isEqualTo(CollaboratorKind.DEPEND)
  }

  @Test
  fun `a missing softdepend is a soft mark`() {
    val graph = DependencyGraph(listOf(manifest("A", softdepend = listOf("B"))))
    val collaborators = graph.collaboratorsOf(graph.cards().single())
    assertThat(collaborators.single().status).isEqualTo(Status.MISSING_SOFT_DEPEND)
  }

  @Test
  fun `a depend cycle is reported as a chain in edge order`() {
    val graph = DependencyGraph(
        listOf(
            manifest("A", depend = listOf("B")),
            manifest("B", depend = listOf("C")),
            manifest("C", depend = listOf("A")),
        )
    )
    assertThat(graph.cycleChainOf("A")).containsExactly("A", "B", "C", "A")
    assertThat(graph.cycleChainOf("B")).isNotNull
    assertThat(graph.cycleChainOf("C")).isNotNull
  }

  @Test
  fun `a self depend is a cycle of two names`() {
    val graph = DependencyGraph(listOf(manifest("A", depend = listOf("A"))))
    assertThat(graph.cycleChainOf("A")).containsExactly("A", "A")
  }

  @Test
  fun `a depend on a missing plugin forms no cycle`() {
    val graph = DependencyGraph(listOf(manifest("A", depend = listOf("B"))))
    assertThat(graph.cycleChainOf("A")).isNull()
  }

  @Test
  fun `cards are sorted by name`() {
    val graph = DependencyGraph(listOf(manifest("B"), manifest("A")))
    assertThat(graph.cards().map { it.name }).containsExactly("A", "B")
  }
}
