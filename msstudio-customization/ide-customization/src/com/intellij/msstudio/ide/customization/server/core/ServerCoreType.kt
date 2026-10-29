// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.core

/**
 * The type of a Minecraft server core.
 *
 * Well-known cores are constants. Custom cores use [custom] with
 * the name from the jar manifest or the jar file name.
 *
 * [consoleProfile] holds the launch settings of the core. The console is a
 * terminal emulator, so the core drives its prompt, colors, history, and
 * completions itself.
 */
class ServerCoreType private constructor(
  val id: String,
  val presentableName: String,
  val consoleProfile: ConsoleProfile,
  val known: Boolean,
) {
  override fun equals(other: Any?): Boolean = other is ServerCoreType && other.id == id

  override fun hashCode(): Int = id.hashCode()

  override fun toString(): String = presentableName

  /**
   * The launch settings of a server core.
   *
   * [stopCommand] holds the console command that stops the core without data
   * loss. [jvmArguments] and [programArguments] hold the arguments of the
   * java process of the core.
   */
  data class ConsoleProfile(
    val stopCommand: String = DEFAULT_STOP_COMMAND,
    val jvmArguments: List<String> = emptyList(),
    val programArguments: List<String> = listOf("nogui"),
  ) {
    companion object {
      const val DEFAULT_STOP_COMMAND: String = "stop"
    }
  }

  companion object {
    val VANILLA: ServerCoreType = known("vanilla", "Vanilla")
    val CRAFTBUKKIT: ServerCoreType = known("craftbukkit", "CraftBukkit")
    val SPIGOT: ServerCoreType = known("spigot", "Spigot")
    val PAPER: ServerCoreType = known("paper", "Paper")
    val PURPUR: ServerCoreType = known("purpur", "Purpur")
    val PUFFERFISH: ServerCoreType = known("pufferfish", "Pufferfish")
    val FOLIA: ServerCoreType = known("folia", "Folia")
    val FABRIC: ServerCoreType = known("fabric", "Fabric")
    val FORGE: ServerCoreType = known("forge", "Forge")
    val NEOFORGE: ServerCoreType = known("neoforge", "NeoForge")
    val MOHIST: ServerCoreType = known("mohist", "Mohist")
    val ARCLIGHT: ServerCoreType = known("arclight", "Arclight")
    val UNKNOWN: ServerCoreType = ServerCoreType("unknown", "Unknown", ConsoleProfile(), false)
    private val WHITESPACE: Regex = Regex("\\s+")

    /**
     * Returns a custom core type with [name] and the default profile.
     * It returns [UNKNOWN] for a blank name.
     */
    fun custom(name: String): ServerCoreType = custom(name, ConsoleProfile())

    /**
     * Returns a custom core type with [name] and [profile].
     * It returns [UNKNOWN] for a blank name.
     */
    fun custom(name: String, profile: ConsoleProfile): ServerCoreType {
      val clean = name.trim()
      if (clean.isEmpty()) return UNKNOWN
      return ServerCoreType(clean.lowercase().replace(WHITESPACE, "-"), clean, profile, false)
    }

    private fun known(id: String, presentableName: String): ServerCoreType {
      return ServerCoreType(id, presentableName, ConsoleProfile(), true)
    }
  }
}
