// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization

import com.intellij.DynamicBundle
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.util.NlsSafe

internal const val MS_STUDIO_BUNDLE: String = "messages.MSStudioBundle"

/**
 * The texts of the MSStudio welcome screen.
 */
internal object MSStudioBundle {
  private val bundle = DynamicBundle(MSStudioBundle::class.java, MS_STUDIO_BUNDLE)

  fun message(key: String, vararg params: Any) = bundle.getMessage(key, *params)

  val actionWelcomeOpenText inline get() = message("action.MSStudio.Welcome.Open.text")

  val actionWelcomeNewText inline get() = message("action.MSStudio.Welcome.New.text")

  val pluginsOpenDependencyGraphText inline get() = message("action.MSStudio.Plugins.OpenDependencyGraph.text")

  val pluginsOpenDependencyGraphDescription inline get() = message("action.MSStudio.Plugins.OpenDependencyGraph.description")

  val hexOpenGeneratorText inline get() = message("action.MSStudio.Hex.OpenGenerator.text")

  val hexOpenGeneratorDescription inline get() = message("action.MSStudio.Hex.OpenGenerator.description")

  val dialogOpenServerTitle inline get() = message("dialog.open.server.title")

  val dialogNewServerTitle inline get() = message("dialog.new.server.title")

  val dialogNewServerMessage inline get() = message("dialog.new.server.message")

  val serverFilesHiddenFilesText inline get() = message("msstudio.serverFiles.hiddenFiles.text")

  fun serverCoreDetectedText(coreName: Any, fileName: Any): String =
    message("msstudio.serverCore.detected.text", coreName, fileName)

  val serverRunText inline get() = message("action.NewUiRunWidget.text")

  val serverRunDescription inline get() = message("action.NewUiRunWidget.description")

  @NlsSafe
  fun serverRunStartText(coreName: Any): String = message("msstudio.serverRun.start.text", coreName)

  @NlsActions.ActionText
  fun serverRunStopText(coreName: Any): String = message("msstudio.serverRun.stop.text", coreName)

  val serverRunStopDescription inline get() = message("msstudio.serverRun.stop.description")

  val serverRunFailedTitle inline get() = message("msstudio.serverRun.failed.title")

  fun serverRunFailedMessage(reason: Any): String = message("msstudio.serverRun.failed.message", reason)

  val serverRunFlagsTitle inline get() = message("msstudio.serverRun.flags.title")

  val serverRunFlagsJvmText inline get() = message("msstudio.serverRun.flags.jvm.text")

  val serverRunFlagsMemoryTitle inline get() = message("msstudio.serverRun.flags.memory.title")

  val serverRunFlagsMemoryInitialText inline get() = message("msstudio.serverRun.flags.memory.initial.text")

  val serverRunFlagsMemoryMaximumText inline get() = message("msstudio.serverRun.flags.memory.maximum.text")

  val serverRunFlagsProgramText inline get() = message("msstudio.serverRun.flags.program.text")

  val serverRunFlagsEmptyText inline get() = message("msstudio.serverRun.flags.empty.text")

  val serverRunFlagsHintText inline get() = message("msstudio.serverRun.flags.hint.text")

  val serverRunFlagsRestoreText inline get() = message("msstudio.serverRun.flags.restore.text")

  val serverLogsRefreshText inline get() = message("msstudio.serverLogs.refresh.text")

  val serverLogsFolderMissing inline get() = message("msstudio.serverLogs.folder.missing")

  val serverLogsDecompressTitle inline get() = message("msstudio.serverLogs.decompress.title")

  val serverLogsDecompressErrorTitle inline get() = message("msstudio.serverLogs.decompress.error.title")

  fun serverLogsDecompressErrorMessage(fileName: Any): String =
    message("msstudio.serverLogs.decompress.error.message", fileName)

  val serverPluginsRefreshText inline get() = message("msstudio.serverPlugins.refresh.text")

  val serverPluginsFolderMissing inline get() = message("msstudio.serverPlugins.folder.missing")

  val serverPluginsEmptyText inline get() = message("msstudio.serverPlugins.empty.text")

  val serverPluginsScanningTitle inline get() = message("msstudio.serverPlugins.scanning.title")

  val serverPluginsDependenciesMissingText inline get() = message("msstudio.serverPlugins.dependencies.missing.text")

  fun serverPluginsDependenciesCycleText(chain: Any): String =
    message("msstudio.serverPlugins.dependencies.cycle.text", chain)

  val serverPluginsEditorFileTypeDescription inline get() = message("msstudio.serverPlugins.editor.fileType.description")

  val serverPluginsEditorName inline get() = message("msstudio.serverPlugins.editor.name")

  val serverPluginsEditorTabTitle inline get() = message("msstudio.serverPlugins.editor.tab.title")

  val serverPluginsEditorZoomInText inline get() = message("msstudio.serverPlugins.editor.zoomIn.text")

  val serverPluginsEditorZoomOutText inline get() = message("msstudio.serverPlugins.editor.zoomOut.text")

  val serverPluginsEditorZoomResetText inline get() = message("msstudio.serverPlugins.editor.zoomReset.text")

  val hexInputTitle inline get() = message("msstudio.hex.input.title")

  val hexColorsTitle inline get() = message("msstudio.hex.colors.title")

  val hexResultTitle inline get() = message("msstudio.hex.result.title")

  val hexFormatTitle inline get() = message("msstudio.hex.format.title")

  val hexFormatLabel inline get() = message("msstudio.hex.format.label")

  val hexFormatAmpersandHex inline get() = message("msstudio.hex.format.ampersandHex")

  val hexFormatSectionHex inline get() = message("msstudio.hex.format.sectionHex")

  val hexFormatAmpersandExpandedHex inline get() = message("msstudio.hex.format.ampersandExpandedHex")

  val hexFormatMiniMessage inline get() = message("msstudio.hex.format.miniMessage")

  val hexFormatJson inline get() = message("msstudio.hex.format.json")

  val hexFormatBbCode inline get() = message("msstudio.hex.format.bbCode")

  val hexColorAddText inline get() = message("msstudio.hex.color.add.text")

  val hexColorRemoveText inline get() = message("msstudio.hex.color.remove.text")

  val hexColorReverseText inline get() = message("msstudio.hex.color.reverse.text")

  val hexColorInvertText inline get() = message("msstudio.hex.color.invert.text")

  val hexColorDuplicateText inline get() = message("msstudio.hex.color.duplicate.text")

  val hexClickToCopyHint inline get() = message("msstudio.hex.clickToCopy.hint")
}
