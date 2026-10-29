// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.run

import com.intellij.msstudio.ide.customization.server.core.ServerCore
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * The start parameters of the server for the project.
 *
 * The service stores the user rows of the JVM options and of the program
 * arguments. Every row is one argument. A missing row list means the defaults
 * of the core profile. Each argument may hold spaces, so the rows hold them
 * without quoting. The memory sizes are separate adjusters. A null memory
 * size means the defaults of the core profile. The memory flags replace the
 * same flags of the user rows, so a launch holds each flag once.
 */
@Service(Service.Level.PROJECT)
@State(name = "MSStudioServerRunFlags", storages = [Storage("msstudio-server-run-flags.xml")])
internal class ServerRunFlagService : PersistentStateComponent<ServerRunFlagService.State?> {
  data class State(
    var jvmArgumentList: List<String>? = null,
    var programArgumentList: List<String>? = null,
    var initialMemoryMb: Int? = null,
    var maximumMemoryMb: Int? = null,
    var initialMemoryUnit: String? = null,
    var maximumMemoryUnit: String? = null,
  )

  private var state = State()

  override fun getState(): State = state

  override fun loadState(loaded: State) {
    state = loaded
  }

  /**
   * Saves the JVM option rows. An empty list means the defaults of the core profile.
   */
  fun setJvmArguments(args: List<String>) {
    state.jvmArgumentList = cleanArguments(args)
  }

  /**
   * Saves the program argument rows. An empty list means the defaults of the core profile.
   */
  fun setProgramArguments(args: List<String>) {
    state.programArgumentList = cleanArguments(args)
  }

  /**
   * Saves the memory sizes and their units of the adjusters. A null initial
   * size means an unset initial heap. A null maximum size means the enabled
   * default of 2 GB. A stored 0 means a maximum size that the user turned
   * off. The unit is "MB" or "GB"; it is null for an off adjuster.
   */
  fun setMemory(initialMb: Int?, maximumMb: Int?, initialUnit: String?, maximumUnit: String?) {
    state.initialMemoryMb = initialMb?.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)
    state.maximumMemoryMb = when {
      maximumMb == null -> null
      maximumMb == DISABLED_MEMORY_MB -> DISABLED_MEMORY_MB
      else -> maximumMb.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)
    }
    state.initialMemoryUnit = initialMb?.let { cleanUnit(initialUnit) }
    state.maximumMemoryUnit = maximumMb?.let { cleanUnit(maximumUnit) }
  }

  /**
   * Holds the initial heap size in MB: the user value, or null for an unset size.
   */
  fun initialMemoryMb(): Int? = state.initialMemoryMb

  /**
   * Holds the maximum heap size in MB: the user value, or null for the default.
   * The value 0 means a size that the user turned off.
   */
  fun maximumMemoryMb(): Int? = state.maximumMemoryMb

  /**
   * Holds the saved unit of the initial heap adjuster: "MB", "GB", or null.
   */
  fun initialMemoryUnit(): String? = state.initialMemoryUnit

  /**
   * Holds the saved unit of the maximum heap adjuster: "MB", "GB", or null.
   */
  fun maximumMemoryUnit(): String? = state.maximumMemoryUnit

  /**
   * Holds the JVM options of a launch: the memory flags, then the user rows.
   * The user rows lose their own memory flags, so each flag appears once.
   */
  fun jvmArguments(core: ServerCore?): List<String> {
    val rows = argumentsOrDefaults(state.jvmArgumentList, core?.type?.consoleProfile?.jvmArguments, emptyList())
    return memoryFlags() + rows.filter { !it.isMemoryFlag() }
  }

  /**
   * Holds the program arguments of a launch: the user rows, or the defaults of the core profile.
   */
  fun programArguments(core: ServerCore?): List<String> =
    argumentsOrDefaults(state.programArgumentList, core?.type?.consoleProfile?.programArguments, DEFAULT_PROGRAM_ARGUMENTS)

  fun reset() {
    state = State()
  }

  private fun cleanArguments(args: List<String>): List<String>? =
    args.map { it.trim() }
      .filter { it.isNotEmpty() }
      .takeIf { it.isNotEmpty() }

  private fun argumentsOrDefaults(user: List<String>?, defaults: List<String>?, fallback: List<String>): List<String> =
    user?.takeIf { it.isNotEmpty() } ?: defaults ?: fallback

  private fun memoryFlags(): List<String> {
    val flags = mutableListOf<String>()
    state.initialMemoryMb?.let { flags += "-Xms${it}m" }
    val maximumMb = state.maximumMemoryMb ?: DEFAULT_MAXIMUM_MEMORY_MB
    if (maximumMb > 0) {
      flags += "-Xmx${maximumMb}m"
    }
    return flags
  }

  private fun String.isMemoryFlag(): Boolean = startsWith("-Xms") || startsWith("-Xmx")

  private fun cleanUnit(unit: String?): String? =
    if (unit == MEMORY_UNIT_MB || unit == MEMORY_UNIT_GB) unit else MEMORY_UNIT_MB

  companion object {
    val DEFAULT_PROGRAM_ARGUMENTS: List<String> = listOf("nogui")

    const val MIN_MEMORY_MB: Int = 128

    const val MAX_MEMORY_MB: Int = 65536

    const val MEMORY_STEP_MB: Int = 128

    const val DEFAULT_MAXIMUM_MEMORY_MB: Int = 2048

    const val DISABLED_MEMORY_MB: Int = 0

    const val MEMORY_UNIT_MB: String = "MB"

    const val MEMORY_UNIT_GB: String = "GB"

    fun getInstance(project: Project): ServerRunFlagService = project.getService(ServerRunFlagService::class.java)
  }
}
