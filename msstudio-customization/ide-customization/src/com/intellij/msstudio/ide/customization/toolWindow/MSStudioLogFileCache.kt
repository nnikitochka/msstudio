// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.toolWindow

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists

/**
 * The cache of decompressed server logs.
 *
 * The cache lives in the `.idea` directory of the project, so it survives
 * restarts. An archive decompresses only when the user opens it. The copy
 * stays valid while its source archive is unchanged. Stale copies go away
 * on cleanup.
 */
internal class MSStudioLogFileCache(private val project: Project) {
  private val dir: Path? = cacheDirFor(project)
  private val entries = ConcurrentHashMap<String, CacheEntry>()

  init {
    dir?.let { Files.createDirectories(it) }
  }

  /**
   * Returns the decompressed copy of [source], decompressing it when the cache has no fresh copy.
   */
  @Throws(IOException::class)
  fun decompressed(source: VirtualFile, indicator: ProgressIndicator): VirtualFile {
    val dir = requireNotNull(dir) { "The project has no directory" }
    val key = source.path
    entries[key]?.let { entry ->
      if (entry.cached.exists() && entry.sourceTimeStamp == source.timeStamp && entry.sourceLength == source.length) {
        findCached(entry.cached)?.let { return it }
      }
      else {
        entries.remove(key, entry)
      }
    }
    return decompress(dir, source, indicator)
  }

  /**
   * Drops the entries whose source archive is gone, with their copies, and deletes orphan copies.
   */
  fun cleanup() {
    val iterator = entries.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (!Files.exists(Path.of(entry.key)) || !entry.value.cached.exists()) {
        try {
          Files.deleteIfExists(entry.value.cached)
        }
        catch (_: IOException) {
        }
        iterator.remove()
      }
    }
    val cached = entries.values.map { it.cached.fileName.toString() }.toSet()
    val dir = dir ?: return
    try {
      Files.list(dir).use { stream ->
        stream.forEach { path ->
          if (path.fileName.toString() !in cached) {
            Files.deleteIfExists(path)
          }
        }
      }
    }
    catch (_: IOException) {
    }
  }

  @Throws(IOException::class)
  private fun decompress(dir: Path, source: VirtualFile, indicator: ProgressIndicator): VirtualFile {
    val cachedName = uniqueName(dir, source.name.removeSuffix(".gz"))
    val cached = dir.resolve(cachedName)
    indicator.text = source.name
    var total = 0L
    GZIPInputStream(source.inputStream).use { gzip ->
      Files.newOutputStream(cached).use { out ->
        val buffer = ByteArray(8192)
        while (true) {
          indicator.checkCanceled()
          val read = gzip.read(buffer)
          if (read < 0) break
          total += read
          if (total > MAX_DECOMPRESSED_BYTES) {
            Files.deleteIfExists(cached)
            throw IOException("Decompressed log exceeds $MAX_DECOMPRESSED_BYTES bytes")
          }
          out.write(buffer, 0, read)
          if (source.length > 0) {
            indicator.fraction = total.toDouble() / (source.length * DECOMPRESSED_TO_COMPRESSED_RATIO)
          }
        }
      }
    }
    entries[source.path] = CacheEntry(cached, source.timeStamp, source.length)
    return findCached(cached) ?: throw IOException("Decompressed log is missing")
  }

  private fun findCached(cached: Path): VirtualFile? {
    val url = VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, cached.toString())
    return VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
  }

  private fun uniqueName(dir: Path, baseName: String): String {
    val taken = entries.values.map { it.cached.fileName.toString() }.toSet()
    if (baseName !in taken && !dir.resolve(baseName).exists()) return baseName
    var index = 1
    while (true) {
      val candidate = "$baseName-$index"
      if (candidate !in taken && !dir.resolve(candidate).exists()) return candidate
      index++
    }
  }

  private data class CacheEntry(
    val cached: Path,
    val sourceTimeStamp: Long,
    val sourceLength: Long,
  )

  companion object {
    const val CACHE_DIR_NAME: String = "msstudio-logs-cache"
    private const val MAX_DECOMPRESSED_BYTES: Long = 256 * 1024 * 1024
    private const val DECOMPRESSED_TO_COMPRESSED_RATIO: Double = 10.0

    fun cacheDirFor(project: Project): Path? {
      return project.basePath?.let { Path.of(it, ".idea", CACHE_DIR_NAME) }
    }
  }
}