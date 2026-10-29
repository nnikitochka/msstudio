// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.run

import com.intellij.execution.process.LocalPtyOptions
import com.intellij.execution.process.LocalProcessServiceImpl
import com.pty4j.PtyProcess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The PTY behavior of the server console.
 *
 * A real console echoes the typed text and merges the error stream. The
 * console mode of [LocalPtyOptions] provides the opposite behavior for a
 * console view. The tests hold the contract of both modes.
 */
class ServerPtyConsoleTest {
  @Test
  fun `a fresh terminal size is not zero`() {
    val process = startPtyProcess(command = listOf("sleep", "2"), consoleMode = false, columns = 80, rows = 25)
    val size = (process as PtyProcess).winSize
    assertThat(size.columns).isEqualTo(80)
    assertThat(size.rows).isEqualTo(25)
  }

  @Test
  fun `a terminal emulation echoes the typed text`() {
    val output = probe(command = listOf("sleep", "3"), consoleMode = false, input = "echo-marker\n")
    assertThat(output).contains("echo-marker")
  }

  @Test
  fun `a console mode does not echo the typed text`() {
    val output = probe(command = listOf("sleep", "3"), consoleMode = true, input = "echo-marker\n")
    assertThat(output).doesNotContain("echo-marker")
  }

  @Test
  fun `a terminal emulation merges the error stream into the output`() {
    val process = startPtyProcess(command = listOf("bash", "-c", "echo out-marker; echo err-marker 1>&2"), consoleMode = false)
    val output = readAll(process.inputStream)
    process.waitFor(5, TimeUnit.SECONDS)
    assertThat(output).contains("out-marker").contains("err-marker")
  }

  @Test
  fun `a console mode keeps the error stream separate`() {
    val process = startPtyProcess(command = listOf("bash", "-c", "echo out-marker; echo err-marker 1>&2"), consoleMode = true)
    val output = readAll(process.inputStream)
    val error = readAll(process.errorStream)
    process.waitFor(5, TimeUnit.SECONDS)
    assertThat(output).contains("out-marker").doesNotContain("err-marker")
    assertThat(error).contains("err-marker")
  }

  @Test
  fun `an interactive shell shows the prompt and the typed command`() {
    val process = startPtyProcess(command = listOf("bash", "--noprofile", "--norc", "-i"), consoleMode = false)
    val output = StringBuilder()
    readAsync(process.inputStream, output)
    Thread.sleep(500)
    process.outputStream.write("echo probe-ok\n".toByteArray())
    process.outputStream.flush()
    waitUntil(output, "probe-ok")
    process.outputStream.write("exit\n".toByteArray())
    process.outputStream.flush()
    process.waitFor(5, TimeUnit.SECONDS)
    dump(output, "interactive bash")
    assertThat(output.toString()).contains("probe-ok")
  }
}

private fun probe(command: List<String>, consoleMode: Boolean, input: String): String {
  val process = startPtyProcess(command, consoleMode)
  val output = StringBuilder()
  readAsync(process.inputStream, output)
  Thread.sleep(500)
  process.outputStream.write(input.toByteArray())
  process.outputStream.flush()
  process.waitFor(6, TimeUnit.SECONDS)
  dump(output, command.joinToString(" "))
  return output.toString()
}

private fun startPtyProcess(command: List<String>, consoleMode: Boolean, columns: Int = -1, rows: Int = -1): java.lang.Process {
  val options = LocalPtyOptions.defaults()
    .builder()
    .consoleMode(consoleMode)
    .initialColumns(columns)
    .initialRows(rows)
    .build()
  val env = mapOf(
    "TERM" to "xterm-256color",
    "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
    "HOME" to (System.getenv("HOME") ?: "/tmp"),
  )
  return LocalProcessServiceImpl().startPtyProcess(command, null, env, options, false)
}

private fun readAll(stream: InputStream): String {
  val bytes = stream.readBytes()
  return String(bytes, Charsets.UTF_8)
}

private fun readAsync(stream: InputStream, sink: StringBuilder): Thread {
  val reader = Thread {
    try {
      val buffer = ByteArray(4096)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        synchronized(sink) {
          sink.append(String(buffer, 0, read, Charsets.UTF_8))
        }
      }
    }
    catch (e: Exception) {
      // The stream ends with the process.
    }
  }
  reader.isDaemon = true
  reader.start()
  return reader
}

private fun waitUntil(sink: StringBuilder, part: String) {
  val deadline = System.currentTimeMillis() + 10_000
  while (System.currentTimeMillis() < deadline) {
    synchronized(sink) {
      if (sink.contains(part)) return
    }
    Thread.sleep(100)
  }
  assertTrue(false, "The text did not appear in 10 seconds: $part")
}

private fun dump(sink: StringBuilder, name: String) {
  val text = synchronized(sink) { sink.toString() }
    .replace("\r", "<CR>")
    .replace("\u001B", "<ESC>")
  println("--- output of $name")
  println(text)
}
