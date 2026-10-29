// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.core

import com.intellij.msstudio.ide.customization.server.core.ServerCoreType
import com.intellij.msstudio.ide.customization.server.core.ServerCoreType.ConsoleProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ServerCoreTypeTest {
  @Test
  fun `every known profile stops the server and hides the gui`() {
    val all = listOf(
      ServerCoreType.VANILLA, ServerCoreType.CRAFTBUKKIT, ServerCoreType.SPIGOT, ServerCoreType.PAPER,
      ServerCoreType.PURPUR, ServerCoreType.PUFFERFISH, ServerCoreType.FOLIA, ServerCoreType.FABRIC,
      ServerCoreType.FORGE, ServerCoreType.NEOFORGE, ServerCoreType.MOHIST, ServerCoreType.ARCLIGHT,
    )
    for (type in all) {
      assertThat(type.consoleProfile.stopCommand).isEqualTo("stop")
      assertThat(type.consoleProfile.programArguments).containsExactly("nogui")
    }
  }

  @Test
  fun `a custom core uses the default profile`() {
    val type = ServerCoreType.custom("Leaves")
    assertThat(type.consoleProfile).isEqualTo(ConsoleProfile())
  }

  @Test
  fun `a custom core can set its own profile`() {
    val type = ServerCoreType.custom(
      "Leaves",
      ConsoleProfile(stopCommand = "shutdown", jvmArguments = listOf("-Xmx2G"), programArguments = listOf("nogui", "--custom")),
    )
    assertThat(type.consoleProfile.stopCommand).isEqualTo("shutdown")
    assertThat(type.consoleProfile.jvmArguments).containsExactly("-Xmx2G")
    assertThat(type.consoleProfile.programArguments).containsExactly("nogui", "--custom")
  }

  @Test
  fun `a blank custom name falls back to unknown`() {
    assertThat(ServerCoreType.custom("  ")).isEqualTo(ServerCoreType.UNKNOWN)
  }
}
