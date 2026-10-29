// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Finds the server core jar in a server folder.
 *
 * It scans the top-level jars of the folder. It prefers the jar named
 * in a launch script, such as `start.sh`. It reads the jar content
 * to find the core type and the game version.
 */
object ServerCoreDetector {
  private val JAR_SUFFIX: Regex = Regex("\\.jar$", RegexOption.IGNORE_CASE)
  private val MC_VERSION: Regex = Regex("(\\d+\\.\\d+(?:\\.\\d+)?)")
  private val MC_MARKER: Regex = Regex("MC:\\s*(\\d+\\.\\d+(?:\\.\\d+)?)")
  private val FILENAME_MC_VERSION: Regex = Regex("(?:mc\\.?|minecraft.?|\\-)(\\d+\\.\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
  private val VERSION_JSON_ID: Regex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
  private val LAUNCH_JAR: Regex = Regex("(?:\\-jar\\s+|\\s)([\\w.\\-]+\\.jar)", RegexOption.IGNORE_CASE)
  private val LAUNCH_SCRIPT_NAMES: Set<String> = setOf("start.sh", "start.bat", "run.sh", "run.bat", "launch.sh", "launch.bat")
  private val FILE_NAME_PARTS: Regex = Regex("[-_.\\s]+")
  private val GENERIC_FILE_TOKENS: Set<String> = setOf(
    "server", "minecraft", "mc", "launcher", "launch", "installer", "universal", "bootstrap", "final", "release", "snapshot"
  )

  /**
   * Returns the best core candidate in [serverDir], or null when the folder holds no jar.
   */
  fun detect(serverDir: Path): ServerCore? {
    val jars = listTopLevelJars(serverDir)
    if (jars.isEmpty()) return null
    val launched = readLaunchedJarNames(serverDir)
    val candidates = jars.mapNotNull { jar ->
      readCandidate(jar, jar.name in launched)
    }
    if (candidates.isEmpty()) return null
    return candidates.maxWithOrNull(compareBy({ it.score }, { it.core.file }))?.core
  }

  private fun listTopLevelJars(serverDir: Path): List<Path> {
    return try {
      if (!Files.isDirectory(serverDir)) return emptyList()
      Files.list(serverDir).use { stream ->
        stream.filter { it.isRegularFile() && JAR_SUFFIX.containsMatchIn(it.name) }.toList()
      }
    }
    catch (_: IOException) {
      emptyList()
    }
  }

  private fun readLaunchedJarNames(serverDir: Path): Set<String> {
    val names = HashSet<String>()
    for (script in LAUNCH_SCRIPT_NAMES) {
      val file = serverDir.resolve(script)
      if (!file.isRegularFile()) continue
      try {
        val text = Files.readString(file)
        LAUNCH_JAR.findAll(text).forEach { match ->
          names.add(match.groupValues[1])
        }
      }
      catch (_: IOException) {
      }
    }
    return names
  }

  private fun readCandidate(jar: Path, launched: Boolean): ScoredCore? {
    val snapshot = readJarSnapshot(jar) ?: JarSnapshot(emptySet(), false, null, null, null)
    val fileName = jar.name
    var type = typeFromEntries(snapshot.entries)
    var typeBonus = if (type != null) 4 else 0
    if (type == null) {
      snapshot.manifestTitle?.let(ServerCoreType::custom)
        ?.takeIf { it != ServerCoreType.UNKNOWN }
        ?.let {
          type = it
          typeBonus = 4
        }
    }
    if (type == null) {
      type = typeFromFileName(fileName)
      if (type != null) typeBonus = 4
    }
    val resolved = type ?: customNameFromFileName(fileName) ?: ServerCoreType.UNKNOWN
    val minecraftVersion = snapshot.minecraftVersion ?: versionFromFileName(fileName)
    val score = scoreOf(typeBonus, snapshot, minecraftVersion, launched)
    val size = runCatching { jar.fileSize() }.getOrDefault(0L)
    val core = ServerCore(jar, fileName, resolved, minecraftVersion, snapshot.coreVersion)
    return ScoredCore(core, score * 1_000_000_000L + size)
  }

  private fun scoreOf(typeBonus: Int, snapshot: JarSnapshot, minecraftVersion: String?, launched: Boolean): Long {
    var score = typeBonus.toLong()
    if (snapshot.hasVersionJson) score += 2
    if (minecraftVersion != null) score += 2
    if (snapshot.coreVersion != null) score += 1
    if (launched) score += 4
    return score
  }

  private fun typeFromEntries(entries: Set<String>): ServerCoreType? {
    val hasBukkit = entries.any { it.startsWith("org/bukkit/craftbukkit/") }
    val hasForge = entries.any { it.startsWith("net/minecraftforge/") } || "META-INF/mods.toml" in entries
    if (entries.any { it.startsWith("com/mohistmc/") } || entries.any { it.startsWith("red/mohist/") }) return ServerCoreType.MOHIST
    if (entries.any { it.startsWith("io/izzel/arclight/") }) return ServerCoreType.ARCLIGHT
    if (entries.any { it.startsWith("net/neoforged/") } || "META-INF/neoforge.mods.toml" in entries) return ServerCoreType.NEOFORGE
    if (entries.any { it.startsWith("net/fabricmc/") }) return ServerCoreType.FABRIC
    if (hasForge && hasBukkit) return null
    if (hasForge) return ServerCoreType.FORGE
    if (entries.any { it.startsWith("org/purpurmc/") } || entries.any { it.startsWith("net/pl3x/") }) return ServerCoreType.PURPUR
    if (entries.any { it.startsWith("gg/pufferfish/") }) return ServerCoreType.PUFFERFISH
    if (entries.any { it.startsWith("io/papermc/paper/threadedregions/") }) return ServerCoreType.FOLIA
    if (entries.any { it.startsWith("io/papermc/") } || entries.any { it.startsWith("com/destroystokyo/paper/") }) return ServerCoreType.PAPER
    if (entries.any { it.startsWith("org/spigotmc/") }) return ServerCoreType.SPIGOT
    if (hasBukkit) return ServerCoreType.CRAFTBUKKIT
    if (entries.any { it.startsWith("net/minecraft/server/") } || entries.any { it.startsWith("net/minecraft/data/") }) {
      return ServerCoreType.VANILLA
    }
    return null
  }

  private fun typeFromFileName(fileName: String): ServerCoreType? {
    val lower = fileName.lowercase()
    return when {
      "mohist" in lower -> ServerCoreType.MOHIST
      "arclight" in lower -> ServerCoreType.ARCLIGHT
      "neoforge" in lower -> ServerCoreType.NEOFORGE
      "fabric" in lower -> ServerCoreType.FABRIC
      "purpur" in lower -> ServerCoreType.PURPUR
      "pufferfish" in lower -> ServerCoreType.PUFFERFISH
      "folia" in lower -> ServerCoreType.FOLIA
      "paper" in lower -> ServerCoreType.PAPER
      "spigot" in lower -> ServerCoreType.SPIGOT
      "bukkit" in lower -> ServerCoreType.CRAFTBUKKIT
      "craftbukkit" in lower -> ServerCoreType.CRAFTBUKKIT
      "forge" in lower -> ServerCoreType.FORGE
      "vanilla" in lower -> ServerCoreType.VANILLA
      else -> null
    }
  }

  /**
   * Derives a custom core name from the jar file name. It drops the game
   * version, the build numbers, and generic words such as `server`.
   * It returns null when no meaningful word remains.
   */
  private fun customNameFromFileName(fileName: String): ServerCoreType? {
    val base = JAR_SUFFIX.replace(fileName, "")
    val withoutVersions = base.replace(MC_VERSION, " ")
    val tokens = withoutVersions.split(FILE_NAME_PARTS)
      .map { it.trim() }
      .filter { it.isNotEmpty() && it.none(Char::isDigit) && it.lowercase() !in GENERIC_FILE_TOKENS }
    if (tokens.isEmpty()) return null
    val presentable = tokens.joinToString(" ") { it.replaceFirstChar { part -> part.uppercase() } }
    return ServerCoreType.custom(presentable)
  }

  private fun versionFromFileName(fileName: String): String? {
    return FILENAME_MC_VERSION.find(fileName)?.groupValues?.get(1) ?: MC_VERSION.find(fileName)?.groupValues?.get(1)
  }

  private fun readJarSnapshot(jar: Path): JarSnapshot? {
    return try {
      ZipFile(jar.toFile()).use { zip ->
        val zipEntries = Collections.list(zip.entries())
        val entries = HashSet<String>(zipEntries.size)
        zipEntries.forEach { entries.add(it.name) }
        val versionJson = zip.getEntry("version.json")?.let { entry ->
          zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
        val manifest = zip.getEntry("META-INF/MANIFEST.MF")?.let { entry ->
          runCatching {
            zip.getInputStream(entry).use { Manifest(it) }
          }.getOrNull()
        }
        val pomVersion = zipEntries
          .firstOrNull { it.name.startsWith("META-INF/maven/") && it.name.endsWith("/pom.properties") }
          ?.let { entry ->
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
          }?.let(::pomVersionOf)
        val manifestVersion = manifest?.mainAttributes?.getValue("Implementation-Version")
        val manifestTitle = manifest?.mainAttributes?.getValue("Implementation-Title")?.trim()?.takeIf { it.isNotEmpty() }
        val minecraftVersion = versionJson?.let(VERSION_JSON_ID::find)?.groupValues?.get(1)
                               ?: manifestVersion?.let(MC_MARKER::find)?.groupValues?.get(1)
                               ?: pomVersion?.let(MC_VERSION::find)?.groupValues?.get(1)
        JarSnapshot(entries, versionJson != null, minecraftVersion, manifestVersion?.trim()?.takeIf { it.isNotEmpty() }, manifestTitle)
      }
    }
    catch (_: IOException) {
      null
    }
  }

  private fun pomVersionOf(text: String): String? {
    return text.lineSequence()
      .firstOrNull { it.trimStart().startsWith("version=") }
      ?.substringAfter("=")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  private data class JarSnapshot(
    val entries: Set<String>,
    val hasVersionJson: Boolean,
    val minecraftVersion: String?,
    val coreVersion: String?,
    val manifestTitle: String?,
  )

  private data class ScoredCore(val core: ServerCore, val score: Long)
}
