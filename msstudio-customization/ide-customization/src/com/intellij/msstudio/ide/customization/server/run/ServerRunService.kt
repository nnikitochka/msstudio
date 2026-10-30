// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.msstudio.ide.customization.server.core.ServerCore
import com.intellij.msstudio.ide.customization.server.core.ServerCoreService
import com.intellij.msstudio.ide.customization.server.core.ServerCoreType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Starts the detected server and shows its console in the Server tool window.
 *
 * The console is a terminal emulator. The server runs in a pseudo-terminal
 * and drives the console itself: it prints its own prompt, colors, history,
 * and completions. The profile of the core holds the launch settings and the
 * stop command. A pseudo-terminal can fail to start. Then the server runs
 * over plain pipes.
 */
@Service(Service.Level.PROJECT)
internal class ServerRunService(private val project: Project) : Disposable {
  private val lock = Any()

  @Volatile
  private var process: Process? = null

  @Volatile
  private var activeProfile: ServerCoreType.ConsoleProfile? = null

  @Volatile
  private var starting = false

  @Volatile
  private var stopping = false

  val isActive: Boolean
    get() = starting || process?.isAlive == true

  val isStopping: Boolean
    get() = stopping

  fun start() {
    val core = ServerCoreService.getInstance(project).detectedCore ?: return
    synchronized(lock) {
      if (isActive) return
      starting = true
      stopping = false
    }
    updateActions()

    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        // The console mode is for a console view: it disables the echo and
        // splits the error stream. A terminal emulation needs neither. The
        // kernel starts the pty with a zero size, so the launch gives the
        // default size of the emulator.
        val commandLine = PtyCommandLine(createCommandLine(core))
          .withConsoleMode(false)
          .withInitialColumns(INITIAL_COLUMNS)
          .withInitialRows(INITIAL_ROWS)
        val newProcess = commandLine.createProcess()

        synchronized(lock) {
          starting = false
          process = newProcess
          activeProfile = core.type.consoleProfile
        }
        watchTermination(newProcess)
        updateActions()

        invokeLater {
          if (project.isDisposed) {
            newProcess.destroyForcibly()
          }
          else {
            showConsole(core, newProcess)
          }
        }
      }
      catch (e: ExecutionException) {
        synchronized(lock) {
          starting = false
        }
        updateActions()
        showStartError(e)
      }
    }
  }

  /**
   * Stops the running server. The stop command of its core goes to the input
   * of the terminal, because a graceful stop saves the data of the server.
   * The process is destroyed when it does not stop in time.
   */
  fun stop() {
    val activeProcess = process ?: return
    val stopCommand = activeProfile?.stopCommand ?: return
    stopping = true
    updateActions()
    ApplicationManager.getApplication().executeOnPooledThread {
      sendStopCommand(activeProcess, stopCommand)
      if (!activeProcess.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        activeProcess.destroyForcibly()
      }
    }
  }

  override fun dispose() {
    process?.destroyForcibly()
  }

  private fun createCommandLine(core: ServerCore): GeneralCommandLine {
    val flags = ServerRunFlagService.getInstance(project)
    val command = mutableListOf(javaExecutable())
    command += flags.jvmArguments(core)
    command += listOf("-jar", core.file.toString())
    command += flags.programArguments(core)
    return GeneralCommandLine(command)
      .withWorkingDirectory(core.file.parent)
      .withCharset(Charsets.UTF_8)
  }

  /**
   * Returns the java executable of the Project SDK. No Project SDK, or an SDK
   * without a Java home, gives the java command from PATH.
   */
  private fun javaExecutable(): String {
    val home = ProjectRootManager.getInstance(project).projectSdk?.homePath
    val name = if (SystemInfo.isWindows) "java.exe" else "java"
    val executable = home?.let { File(it, "bin/$name") }
    return executable?.path?.takeIf { executable.isFile } ?: "java"
  }

  private fun showConsole(core: ServerCore, serverProcess: Process) {
    val terminal = ServerTerminal(project, this, serverProcess, core.presentableName(), Charsets.UTF_8)

    val manager = ToolWindowManager.getInstance(project)
    val toolWindow = manager.getToolWindow(SERVER_TOOL_WINDOW_ID)
      ?: manager.registerToolWindow(SERVER_TOOL_WINDOW_ID) {
        icon = AllIcons.Actions.Execute
        anchor = ToolWindowAnchor.BOTTOM
        canCloseContent = true
      }
    val content = ContentFactory.getInstance().createContent(terminal.component, core.presentableName(), true)
    content.setPreferredFocusableComponent(terminal.focusableComponent)
    toolWindow.contentManager.addContent(content)
    toolWindow.contentManager.setSelectedContent(content)
    toolWindow.activate(null)
  }

  private fun watchTermination(finished: Process) {
    ApplicationManager.getApplication().executeOnPooledThread {
      finished.waitFor()
      synchronized(lock) {
        if (process === finished) {
          process = null
          activeProfile = null
          stopping = false
        }
      }
      updateActions()
    }
  }

  private fun sendStopCommand(serverProcess: Process, stopCommand: String) {
    try {
      val input = serverProcess.outputStream
      input.write((stopCommand + "\n").toByteArray())
      input.flush()
    }
    catch (e: IOException) {
      serverProcess.destroyForcibly()
    }
  }

  private fun showStartError(error: ExecutionException) {
    invokeLater {
      if (!project.isDisposed) {
        Messages.showErrorDialog(
          project,
          MSStudioBundle.serverRunFailedMessage(error.message ?: error.javaClass.simpleName),
          MSStudioBundle.serverRunFailedTitle,
        )
      }
    }
  }

  private fun updateActions() {
    ActivityTracker.getInstance().inc()
  }

  companion object {
    private const val SERVER_TOOL_WINDOW_ID: String = "Server"

    private const val INITIAL_COLUMNS: Int = 80

    private const val INITIAL_ROWS: Int = 25

    private const val STOP_TIMEOUT_MILLIS: Long = 20_000

    fun getInstance(project: Project): ServerRunService = project.getService(ServerRunService::class.java)
  }
}
