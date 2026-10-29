// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.core

import java.nio.file.Path

/**
 * The server core found in a server folder.
 *
 * [minecraftVersion] holds the game version, such as `1.21.1`.
 * [coreVersion] holds the full build version when the jar reports it.
 * [fileName] holds the name of the jar file.
 */
data class ServerCore(
  val file: Path,
  val fileName: String,
  val type: ServerCoreType,
  val minecraftVersion: String?,
  val coreVersion: String?,
) {
  /**
   * Returns the short label for the UI, such as `Paper 1.21.1`.
   */
  fun presentableName(): String {
    val version = minecraftVersion?.let { " $it" } ?: ""
    return "${type.presentableName}$version"
  }
}
