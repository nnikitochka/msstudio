// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.core

import com.intellij.msstudio.ide.customization.server.core.ServerCoreDetector
import com.intellij.msstudio.ide.customization.server.core.ServerCoreType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ServerCoreDetectorTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `returns null when the folder holds no jar`() {
    Files.writeString(tempDir.resolve("server.properties"), "motd=test\n")
    assertThat(ServerCoreDetector.detect(tempDir)).isNull()
  }

  @Test
  fun `finds a paper core and its version`() {
    writeJar("paper-1.21.1-132.jar", mapOf(
      "io/papermc/paper/Some.class" to byteArrayOf(1),
      "org/bukkit/craftbukkit/Main.class" to byteArrayOf(1),
    ), "Implementation-Version: 1.21.1-132-abc (MC: 1.21.1)\n")
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.type).isEqualTo(ServerCoreType.PAPER)
    assertThat(core?.minecraftVersion).isEqualTo("1.21.1")
    assertThat(core?.fileName).isEqualTo("paper-1.21.1-132.jar")
  }

  @Test
  fun `finds a vanilla core from version json`() {
    writeJar("server.jar", mapOf(
      "net/minecraft/server/Main.class" to byteArrayOf(1),
      "version.json" to """{"id": "1.20.4", "name": "1.20.4"}""".toByteArray(),
    ), null)
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.type).isEqualTo(ServerCoreType.VANILLA)
    assertThat(core?.minecraftVersion).isEqualTo("1.20.4")
  }

  @Test
  fun `prefers the jar named in a launch script`() {
    writeJar("server.jar", mapOf(
      "net/minecraft/server/Main.class" to byteArrayOf(1),
      "version.json" to """{"id": "1.20.4"}""".toByteArray(),
    ), null)
    writeJar("paper-1.21.1-10.jar", mapOf(
      "io/papermc/paper/Some.class" to byteArrayOf(1),
    ), "Implementation-Version: 1.21.1-10 (MC: 1.21.1)\n")
    Files.writeString(tempDir.resolve("start.sh"), "java -Xmx2G -jar server.jar nogui\n")
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.fileName).isEqualTo("server.jar")
    assertThat(core?.type).isEqualTo(ServerCoreType.VANILLA)
  }

  @Test
  fun `falls back to the file name when the jar holds no markers`() {
    writeJar("forge-1.20.1-47.2.0.jar", emptyMap(), null)
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.type).isEqualTo(ServerCoreType.FORGE)
    assertThat(core?.minecraftVersion).isEqualTo("1.20.1")
  }

  @Test
  fun `reads a custom core name from the manifest title`() {
    writeJar("custom-1.21.jar", mapOf(
      "net/dragon/Main.class" to byteArrayOf(1),
    ), "Implementation-Title: DragonCore\n")
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.type).isEqualTo(ServerCoreType.custom("DragonCore"))
    assertThat(core?.type?.known).isFalse()
    assertThat(core?.type?.presentableName).isEqualTo("DragonCore")
  }

  @Test
  fun `derives a custom core name from the file name`() {
    writeJar("dragon-core-1.21.jar", emptyMap(), null)
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.type?.known).isFalse()
    assertThat(core?.type?.presentableName).isEqualTo("Dragon Core")
    assertThat(core?.minecraftVersion).isEqualTo("1.21")
  }

  @Test
  fun `keeps unknown when the file name holds no words`() {
    writeJar("server.jar", emptyMap(), null)
    val core = ServerCoreDetector.detect(tempDir)
    assertThat(core?.type).isEqualTo(ServerCoreType.UNKNOWN)
  }

  @Test
  fun `custom types match by id`() {
    assertThat(ServerCoreType.custom("Paper")).isEqualTo(ServerCoreType.PAPER)
    assertThat(ServerCoreType.custom("  ")).isEqualTo(ServerCoreType.UNKNOWN)
  }

  private fun writeJar(name: String, entries: Map<String, ByteArray>, manifestVersion: String?) {
    val file = tempDir.resolve(name)
    ZipOutputStream(Files.newOutputStream(file)).use { zip ->
      if (manifestVersion != null) {
        zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
        zip.write("Manifest-Version: 1.0\n$manifestVersion".toByteArray())
        zip.closeEntry()
      }
      entries.forEach { (entryName, content) ->
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content)
        zip.closeEntry()
      }
    }
  }
}
