// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.dependencies

import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifest

/**
 * The dependency graph of the installed plugins.
 *
 * The graph reads the collaborator lists of every manifest and marks the
 * problems: a `depend` target that is not installed, a `depend` cycle, and
 * a `softdepend` target that is not installed. The class holds no
 * platform types, so its checks run in plain unit tests.
 */
class DependencyGraph(manifests: List<PluginManifest>) {
  enum class CollaboratorKind { DEPEND, SOFT_DEPEND, LOAD_BEFORE }

  enum class Status { OK, MISSING_DEPEND, MISSING_SOFT_DEPEND, CYCLE }

  data class Collaborator(val name: String, val kind: CollaboratorKind, val status: Status)

  private val byName: Map<String, PluginManifest> =
    manifests.filter { !it.name.isNullOrEmpty() }.associateBy { it.name!! }

  private val dependCycles: Map<String, List<String>> = findDependCycles()

  /**
   * The installed cards, sorted by name.
   */
  fun cards(): List<PluginManifest> = byName.values.sortedBy { it.name }

  /**
   * The collaborators of one card, in the manifest order of each list.
   */
  fun collaboratorsOf(card: PluginManifest): List<Collaborator> {
    return card.depend.map { Collaborator(it, CollaboratorKind.DEPEND, statusOf(it, hard = true)) } +
           card.softdepend.map { Collaborator(it, CollaboratorKind.SOFT_DEPEND, statusOf(it, hard = false)) } +
           card.loadbefore.map { Collaborator(it, CollaboratorKind.LOAD_BEFORE, Status.OK) }
  }

  /**
   * The chain of the `depend` cycle that holds [name], or null. The chain
   * starts and ends with [name].
   */
  fun cycleChainOf(name: String): List<String>? = dependCycles[name]

  private fun statusOf(target: String, hard: Boolean): Status {
    return when {
      target !in byName -> if (hard) Status.MISSING_DEPEND else Status.MISSING_SOFT_DEPEND
      target in dependCycles -> Status.CYCLE
      else -> Status.OK
    }
  }

  private fun findDependCycles(): Map<String, List<String>> {
    val cycles = HashMap<String, List<String>>()
    val state = HashMap<String, Int>()
    val stack = mutableListOf<String>()
    fun visit(name: String) {
      state[name] = VISITING
      stack.add(name)
      for (target in byName[name]!!.depend) {
        when (state[target]) {
          null -> if (target in byName) visit(target)
          VISITING -> recordCycle(target, stack, cycles)
        }
      }
      stack.removeAt(stack.lastIndex)
      state[name] = VISITED
    }
    byName.keys.forEach { if (state[it] == null) visit(it) }
    return cycles
  }

  private fun recordCycle(start: String, stack: List<String>, cycles: HashMap<String, List<String>>) {
    val startIdx = stack.indexOf(start)
    val chain = ArrayList<String>(stack.subList(startIdx, stack.size))
    chain += start
    chain.forEach { member -> cycles.putIfAbsent(member, chain) }
  }

  private companion object {
    const val VISITING: Int = 1
    const val VISITED: Int = 2
  }
}
