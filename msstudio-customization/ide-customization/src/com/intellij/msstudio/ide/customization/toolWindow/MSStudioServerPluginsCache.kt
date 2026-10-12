// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by an Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * The cache of the plugin jars of the server.
 *
 * A Bukkit or Paper plugin keeps its manifest inside the jar. The manifest
 * is read only when the jar hash changes. The cache lives in the `.idea`
 * directory of the project, so it survives restarts.
 */
internal class MSStudioServerPluginsCache(project: Project) {
  private val cacheDir: Path? = project.basePath?.let { Path.of(it, ".idea", CACHE_DIR_NAME) }
  private val jars = ConcurrentHashMap<String, JarEntry>()
  private val folderManifests = ConcurrentHashMap<String, FolderEntry>()

  init {
    load()
  }

  /**
   * The manifest data of a plugin: the display name and the version.
   */
  data class PluginInfo(val name: String?, val version: String?)

  /**
   * Returns the manifest data of [jar]. The jar hashes first. An unchanged
   * size and modification time reuses the stored hash and manifest data.
   * A new hash rereads the manifest from the jar.
   */
  fun jarInfo(jar: VirtualFile): PluginInfo? {
    val size = jar.length
    val modified = jar.timeStamp
    jars[jar.path]?.let { entry ->
      if (entry.size == size && entry.modified == modified) return entry.info
    }
    val path = Path.of(jar.path)
    val sha256 = sha256Of(path) ?: return null
    jars[jar.path]?.let { entry ->
      if (entry.sha256 == sha256) {
        jars[jar.path] = entry.copy(size = size, modified = modified)
        return entry.info
      }
    }
    val info = readEmbeddedManifest(path)
    jars[jar.path] = JarEntry(size, modified, sha256, info)
    return info
  }

  /**
   * Reads `name` and `version` from a `plugin.yml` or `paper-plugin.yml`
   * file placed directly in [folder]. Such a folder is itself a plugin.
   */
  fun folderInfo(folder: VirtualFile): PluginInfo? {
    val manifest = MANIFEST_FILE_NAMES.firstNotNullOfOrNull { folder.findChild(it) } ?: return null
    if (!manifest.isValid || manifest.isDirectory) return null
    folderManifests[manifest.path]?.let { entry ->
      if (entry.modified == manifest.timeStamp && entry.length == manifest.length) return entry.info
    }
    val info = runCatching { parseManifest(manifest.inputStream.bufferedReader().use { it.readText() }) }.getOrNull()
    folderManifests[manifest.path] = FolderEntry(manifest.timeStamp, manifest.length, info)
    return info
  }

  /**
   * Drops the entries whose files are gone and stores the cache on disk.
   */
  fun cleanup() {
    jars.keys.removeIf { !Files.exists(Path.of(it)) }
    folderManifests.keys.removeIf { !Files.exists(Path.of(it)) }
    val dir = cacheDir ?: return
    try {
      Files.createDirectories(dir)
      val lines = buildList {
        jars.forEach { (path, entry) ->
          add(listOf("jar", path, entry.size, entry.modified, entry.sha256,
                     entry.info?.name.orEmpty(), entry.info?.version.orEmpty()).joinToString("\t"))
        }
        folderManifests.forEach { (path, entry) ->
          add(listOf("folder", path, entry.modified, entry.length,
                     entry.info?.name.orEmpty(), entry.info?.version.orEmpty()).joinToString("\t"))
        }
      }
      Files.write(dir.resolve(CACHE_FILE_NAME), lines)
    }
    catch (_: IOException) {
    }
  }

  private fun load() {
    val dir = cacheDir ?: return
    val file = dir.resolve(CACHE_FILE_NAME)
    if (!Files.exists(file)) return
    try {
      Files.readAllLines(file).forEach { line ->
        val parts = line.split('\t')
        when (parts.firstOrNull()) {
          "jar" -> if (parts.size == 7) {
            jars[parts[1]] = JarEntry(parts[2].toLong(), parts[3].toLong(), parts[4],
                                      PluginInfo(parts[5].ifEmpty { null }, parts[6].ifEmpty { null }))
          }
          "folder" -> if (parts.size == 6) {
            folderManifests[parts[1]] = FolderEntry(parts[2].toLong(), parts[3].toLong(),
                                                    PluginInfo(parts[4].ifEmpty { null }, parts[5].ifEmpty { null }))
          }
        }
      }
    }
    catch (_: Exception) {
    }
  }

  private fun sha256Of(path: Path): String? {
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
      }
      digest.digest().joinToString("") { "%02x".format(it) }
    }
    catch (_: IOException) {
      null
    }
  }

  private fun readEmbeddedManifest(jar: Path): PluginInfo? {
    return try {
      ZipFile(jar.toFile()).use { zip ->
        val entry = MANIFEST_FILE_NAMES.firstNotNullOfOrNull { zip.getEntry(it) } ?: return null
        zip.getInputStream(entry).bufferedReader().use { it.readText() }
      }.let(::parseManifest)
    }
    catch (_: IOException) {
      null
    }
  }

  private fun parseManifest(text: String): PluginInfo? {
    var name: String? = null
    var version: String? = null
    text.lineSequence().forEach { line ->
      val match = FIELD.matchEntire(line) ?: return@forEach
      val value = cleanValue(match.groupValues[2])
      when (match.groupValues[1]) {
        "name" -> name = value
        "version" -> version = value
      }
    }
    return PluginInfo(name, version)
  }

  private fun cleanValue(raw: String): String {
    var value = raw.trim()
    val comment = value.indexOf(" #")
    if (comment >= 0) value = value.substring(0, comment).trim()
    return value.removeSurrounding("\"").removeSurrounding("'")
  }

  private data class JarEntry(val size: Long, val modified: Long, val sha256: String, val info: PluginInfo?)

  private data class FolderEntry(val modified: Long, val length: Long, val info: PluginInfo?)

  companion object {
    const val CACHE_DIR_NAME: String = "msstudio-plugins-cache"
    private const val CACHE_FILE_NAME: String = "plugins.tsv"
    private val MANIFEST_FILE_NAMES: List<String> = listOf("paper-plugin.yml", "plugin.yml")
    private val FIELD: Regex = Regex("^(name|version):[ \\t]*(.*)$")
  }
}
