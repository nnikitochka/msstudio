// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.plugins

import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifest
import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifestParser
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
 * directory of the project, so it survives restarts. The cache file holds
 * paths relative to the server folder, so a moved server keeps its cache.
 */
internal class ServerPluginsCache(project: Project) {
  private val serverDir: Path? = project.basePath?.let(Path::of)
  private val cacheDir: Path? = serverDir?.let { it.resolve(".idea").resolve(CACHE_DIR_NAME) }
  private val jars = ConcurrentHashMap<String, JarEntry>()
  private val folderManifests = ConcurrentHashMap<String, FolderEntry>()

  init {
    load()
  }

  /**
   * Returns the manifest of [jar]. The jar hashes first. An unchanged size
   * and modification time reuses the stored hash and manifest. A new hash
   * rereads the manifest from the jar.
   */
  fun jarInfo(jar: VirtualFile): PluginManifest? {
    val size = jar.length
    val modified = jar.timeStamp
    jars[jar.path]?.let { entry ->
      if (entry.size == size && entry.modified == modified) return entry.manifest
    }
    val path = Path.of(jar.path)
    val sha256 = sha256Of(path) ?: return null
    jars[jar.path]?.let { entry ->
      if (entry.sha256 == sha256) {
        jars[jar.path] = entry.copy(size = size, modified = modified)
        return entry.manifest
      }
    }
    val manifest = readEmbeddedManifest(path)
    jars[jar.path] = JarEntry(size, modified, sha256, manifest)
    return manifest
  }

  /**
   * Reads the manifest of a `plugin.yml` or `paper-plugin.yml` file placed
   * directly in [folder]. Such a folder is itself a plugin.
   */
  fun folderInfo(folder: VirtualFile): PluginManifest? {
    val manifestFile = MANIFEST_FILE_NAMES.firstNotNullOfOrNull { folder.findChild(it) } ?: return null
    if (!manifestFile.isValid || manifestFile.isDirectory) return null
    folderManifests[manifestFile.path]?.let { entry ->
      if (entry.modified == manifestFile.timeStamp && entry.length == manifestFile.length) return entry.manifest
    }
    val manifest = runCatching {
      PluginManifestParser.parse(manifestFile.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
    folderManifests[manifestFile.path] = FolderEntry(manifestFile.timeStamp, manifestFile.length, manifest)
    return manifest
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
          add(listOf(
            "jar", stored(path), entry.size, entry.modified, entry.sha256,
            entry.manifest?.name.stored(), entry.manifest?.version.stored(), entry.manifest?.description.stored(),
            entry.manifest?.depend.stored(), entry.manifest?.softdepend.stored(), entry.manifest?.loadbefore.stored(),
          ).joinToString("\t"))
        }
        folderManifests.forEach { (path, entry) ->
          add(listOf(
            "folder", stored(path),
            entry.manifest?.name.stored(), entry.manifest?.version.stored(), entry.manifest?.description.stored(),
            entry.manifest?.depend.stored(), entry.manifest?.softdepend.stored(), entry.manifest?.loadbefore.stored(),
          ).joinToString("\t"))
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
          "jar" -> if (parts.size == 11) {
            jars[absolute(parts[1])] = JarEntry(
              parts[2].toLong(), parts[3].toLong(), parts[4],
              PluginManifest(parts[5].field(), parts[6].field(), parts[7].field(),
                                     parts[8].list(), parts[9].list(), parts[10].list()),
            )
          }
          "folder" -> if (parts.size == 9) {
            folderManifests[absolute(parts[1])] = FolderEntry(
              0L, 0L,
              PluginManifest(parts[2].field(), parts[3].field(), parts[4].field(),
                                     parts[5].list(), parts[6].list(), parts[7].list()),
            )
          }
        }
      }
    }
    catch (_: Exception) {
    }
  }

  /**
   * Converts an absolute path to its stored form, relative to the server
   * folder when the path lies inside it.
   */
  private fun stored(path: String): String {
    val base = serverDir ?: return path
    val full = Path.of(path)
    return if (full.startsWith(base)) base.relativize(full).toString() else path
  }

  private fun absolute(stored: String): String {
    val base = serverDir ?: return stored
    return if (stored.startsWith("/")) stored else base.resolve(stored).toString()
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

  private fun readEmbeddedManifest(jar: Path): PluginManifest? {
    return try {
      ZipFile(jar.toFile()).use { zip ->
        val entry = MANIFEST_FILE_NAMES.firstNotNullOfOrNull { zip.getEntry(it) } ?: return null
        zip.getInputStream(entry).bufferedReader().use { it.readText() }
      }.let(PluginManifestParser::parse)
    }
    catch (_: IOException) {
      null
    }
  }

  private data class JarEntry(val size: Long, val modified: Long, val sha256: String, val manifest: PluginManifest?)

  private data class FolderEntry(val modified: Long, val length: Long, val manifest: PluginManifest?)

  companion object {
    const val CACHE_DIR_NAME: String = "msstudio-plugins-cache"
    private const val CACHE_FILE_NAME: String = "plugins.tsv"
    private val MANIFEST_FILE_NAMES: List<String> = listOf("paper-plugin.yml", "plugin.yml")
  }
}

private fun String?.stored(): String = this?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""

private fun String.field(): String? = ifEmpty { null }

private fun String.list(): List<String> = if (isEmpty()) emptyList() else split('|')

private fun List<String>?.stored(): String = this?.joinToString("|") ?: ""
