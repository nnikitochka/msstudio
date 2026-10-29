// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.dependencies

import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileWithAssignedFileType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Non-physical VFS for the plugin dependency graph tab.
 *
 * The editor splitters persist editor tabs by file URL and reopen them with [VirtualFileManager.findFileByUrl].
 * A light file of the stock `mock://` file system cannot be found by URL, so a plain light file makes the IDE
 * log "No file exists" on every start and then drop the tab. A static path in a registered file system keeps
 * the tab restorable.
 */
internal class PluginDependenciesFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
  override fun getProtocol(): String = PROTOCOL

  override fun findFileByPath(path: String): VirtualFile? = PluginDependenciesGraphFile.takeIf { path == PLUGIN_DEPENDENCIES_PATH }

  override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

  override fun refresh(asynchronous: Boolean) {}

  companion object {
    const val PROTOCOL: String = "msstudio-plugin-deps"
    const val PLUGIN_DEPENDENCIES_PATH: String = "/plugin-dependencies"
  }
}

/**
 * The identity of the plugin dependency graph editor tab.
 *
 * The content lives in the editor, not in the file: the file is empty and read-only.
 * The displayed name comes from the localization bundle, so the tab follows the active language.
 */
internal object PluginDependenciesGraphFile : VirtualFile(), VirtualFileWithAssignedFileType {
  private val vfsInstance: PluginDependenciesFileSystem by lazy {
    VirtualFileManager.getInstance().getFileSystem(PluginDependenciesFileSystem.PROTOCOL) as PluginDependenciesFileSystem
  }

  override fun getName(): @NlsSafe String = MSStudioBundle.serverPluginsEditorTabTitle

  override fun getFileSystem(): PluginDependenciesFileSystem = vfsInstance

  override fun getAssignedFileType(): PluginDependenciesFileType = PluginDependenciesFileType

  override fun getPath(): String = PluginDependenciesFileSystem.PLUGIN_DEPENDENCIES_PATH

  override fun isDirectory(): Boolean = false

  override fun isWritable(): Boolean = false

  override fun isValid(): Boolean = true

  override fun getParent(): VirtualFile? = null

  override fun getChildren(): Array<VirtualFile> = EMPTY_ARRAY

  override fun getModificationStamp(): Long = 0

  override fun getTimeStamp(): Long = 0

  override fun getLength(): Long = 0

  override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

  override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
    throw UnsupportedOperationException("The plugin dependency graph file is read-only")

  override fun contentsToByteArray(): ByteArray = ByteArray(0)

  override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {}

  override fun rename(requestor: Any?, newName: @NlsSafe String): Unit =
    throw UnsupportedOperationException("The plugin dependency graph file is read-only")

  override fun delete(requestor: Any?): Unit =
    throw UnsupportedOperationException("The plugin dependency graph file is read-only")

  override fun move(requestor: Any?, newParent: VirtualFile): Unit =
    throw UnsupportedOperationException("The plugin dependency graph file is read-only")
}
