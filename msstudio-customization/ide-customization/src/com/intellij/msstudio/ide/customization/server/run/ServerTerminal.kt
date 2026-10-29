// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import org.jetbrains.annotations.Nls
import java.io.IOException
import java.nio.charset.Charset
import javax.swing.JComponent

/**
 * A terminal emulator that shows the console of a server process.
 *
 * [JBTerminalWidget] renders the console exactly like the built-in
 * JetBrains terminal: the same scroll bar, colors, font, and hyperlinks.
 * The process runs in a pseudo-terminal, and the server drives the console
 * itself: it prints its own prompt, colors, history, and completions.
 */
internal class ServerTerminal(
  project: Project,
  parent: Disposable,
  process: Process,
  title: @Nls String,
  charset: Charset,
) {
  private val widget = JBTerminalWidget(project, JBTerminalSystemSettingsProviderBase(), parent)

  /** The Swing component of the terminal. */
  val component: JComponent
    get() = widget

  /** The component that receives the keyboard focus of the terminal. */
  val focusableComponent: JComponent
    get() = widget.terminalPanel

  init {
    widget.start(ServerProcessTtyConnector(process, title, charset))
  }

  /** Stops the terminal session and releases its resources. */
  fun close() {
    widget.close()
  }
}

/**
 * A TTY connector over the process of a server.
 *
 * It sends the terminal size to the pseudo-terminal. The server formats its
 * output for that size.
 */
private class ServerProcessTtyConnector(process: Process, private val name: String, charset: Charset) :
  ProcessTtyConnector(process, charset) {

  override fun getName(): String = name

  override fun resize(termSize: TermSize) {
    val process = process as? PtyProcess ?: return
    process.setWinSize(WinSize(termSize.columns, termSize.rows))
  }
}
