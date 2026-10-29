// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.yaml.material

import com.intellij.msstudio.ide.customization.server.core.ServerCore
import com.intellij.msstudio.ide.customization.server.core.ServerCoreType
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MaterialRegistryTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `reads the enum constants of the core jar`() {
    val jar = tempDir.resolve("paper-1.21.1-132.jar")
    writeJar(jar, mapOf(MaterialRegistry.MATERIAL_ENTRY to materialClassBytes("STICK", "STONE", "DIAMOND_SWORD")))
    val core = ServerCore(jar, jar.fileName.toString(), ServerCoreType.PAPER, "1.21.1", null)
    assertThat(MaterialRegistry.getMaterials(core)).containsExactly("STICK", "STONE", "DIAMOND_SWORD")
  }

  @Test
  fun `falls back to the bundled list without the enum`() {
    val jar = tempDir.resolve("server.jar")
    writeJar(jar, mapOf("net/minecraft/server/Main.class" to byteArrayOf(1)))
    val core = ServerCore(jar, jar.fileName.toString(), ServerCoreType.VANILLA, "1.21.1", null)
    val materials = MaterialRegistry.getMaterials(core)
    assertThat(materials).contains("STONE", "DIAMOND_SWORD")
  }

  @Test
  fun `returns the bundled list without a core`() {
    assertThat(MaterialRegistry.getMaterials(null)).contains("STONE", "DIAMOND_SWORD")
  }

  private fun writeJar(jar: Path, entries: Map<String, ByteArray>) {
    ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
      entries.forEach { (name, content) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
      }
    }
  }

  private fun materialClassBytes(vararg constants: String): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM,
                 "org/bukkit/Material", "Ljava/lang/Enum<Lorg/bukkit/Material;>;", "java/lang/Enum", null)
    for (name in constants) {
      writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                        name, "Lorg/bukkit/Material;", null, null)
    }
    writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
                      "\$VALUES", "[Lorg/bukkit/Material;", null, null)
    writer.visitEnd()
    return writer.toByteArray()
  }
}
