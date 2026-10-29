// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.yaml.material

import com.intellij.msstudio.ide.customization.server.core.ServerCore
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Holds the material names of the detected server core.
 *
 * A Bukkit-based core keeps the material list in the `org/bukkit/Material`
 * enum of the core jar. The registry reads the enum constants from the jar
 * without loading the class. A core without the enum falls back to the
 * bundled list of the latest known version.
 */
object MaterialRegistry {
  const val MATERIAL_ENTRY: String = "org/bukkit/Material.class"

  private const val MATERIAL_DESCRIPTOR: String = "Lorg/bukkit/Material;"
  private const val FALLBACK_RESOURCE: String = "/materials/materials.txt"

  private val cache = ConcurrentHashMap<String, CachedMaterials>()

  /**
   * Returns the material names of [core]. It returns the bundled list
   * when the core is null or its jar holds no material enum.
   */
  fun getMaterials(core: ServerCore?): List<String> {
    if (core == null) return loadFallback()
    val jar = core.file
    val size = runCatching { Files.size(jar) }.getOrDefault(-1L)
    val modified = runCatching { Files.getLastModifiedTime(jar).toMillis() }.getOrDefault(-1L)
    val key = jar.toAbsolutePath().toString()
    cache[key]?.takeIf { it.size == size && it.modified == modified }?.let { return it.materials }
    val fromJar = readFromJar(jar)
    val result = if (fromJar.isNotEmpty()) fromJar else loadFallback()
    cache[key] = CachedMaterials(size, modified, result)
    return result
  }

  /**
   * Drops the stored lists. Tests use it to reread changed jars.
   */
  fun clearCache() {
    cache.clear()
  }

  private fun readFromJar(jar: Path): List<String> {
    return try {
      ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry(MATERIAL_ENTRY) ?: return emptyList()
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        parseMaterialClass(bytes)
      }
    }
    catch (_: IOException) {
      emptyList()
    }
  }

  /**
   * Returns the enum constant names of the given `Material` class bytes.
   * It keeps only the fields with the enum descriptor and drops `$VALUES`.
   */
  fun parseMaterialClass(bytes: ByteArray): List<String> {
    val names = ArrayList<String>()
    try {
      ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitField(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          value: Any?,
        ): FieldVisitor? {
          if (descriptor == MATERIAL_DESCRIPTOR && !name.startsWith("$")) {
            names.add(name)
          }
          return super.visitField(access, name, descriptor, signature, value)
        }
      }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    }
    catch (e: Exception) {
      thisLogger().warn("Cannot parse the Material class.", e)
      return emptyList()
    }
    return names
  }

  private fun loadFallback(): List<String> {
    return try {
      MaterialRegistry::class.java.getResourceAsStream(FALLBACK_RESOURCE)?.bufferedReader()?.use { reader ->
        reader.lineSequence()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }
          .toList()
      } ?: emptyList()
    }
    catch (_: IOException) {
      emptyList()
    }
  }

  private data class CachedMaterials(val size: Long, val modified: Long, val materials: List<String>)
}
