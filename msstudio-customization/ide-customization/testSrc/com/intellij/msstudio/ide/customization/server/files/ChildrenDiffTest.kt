// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChildrenDiffTest {
  @Test
  fun `an identical list reports nothing`() {
    val a = Any()
    val b = Any()
    val diff = diffChildren(listOf(a, b), listOf(a, b))
    assertThat(diff.removedNodes).isEmpty()
    assertThat(diff.insertedNodes).isEmpty()
    assertThat(diff.changedNodes).isEmpty()
  }

  @Test
  fun `an inserted node shifts the kept nodes as changed`() {
    val a = Any()
    val b = Any()
    val c = Any()
    val diff = diffChildren(listOf(a, c), listOf(a, b, c))
    assertThat(diff.removedNodes).isEmpty()
    assertThat(diff.insertedNodes).containsExactly(b)
    assertThat(diff.insertedIndices).containsExactly(1)
    assertThat(diff.changedNodes).containsExactly(a, c)
    assertThat(diff.changedIndices).containsExactly(0, 2)
  }

  @Test
  fun `a deleted node shifts the kept nodes as changed`() {
    val a = Any()
    val b = Any()
    val c = Any()
    val diff = diffChildren(listOf(a, b, c), listOf(a, c))
    assertThat(diff.removedNodes).containsExactly(b)
    assertThat(diff.removedIndices).containsExactly(1)
    assertThat(diff.insertedNodes).isEmpty()
    assertThat(diff.changedNodes).containsExactly(a, c)
    assertThat(diff.changedIndices).containsExactly(0, 1)
  }

  @Test
  fun `a rotation keeps the archived node and inserts the new log`() {
    // a log rotation: latest.log becomes an archive, and a new latest.log appears.
    // The rename keeps the file instance, and the archive sorts before the logs
    // again, so the old listing [a, b, latest] becomes [archive, latest, a, b].
    val archive = Any()
    val latestNew = Any()
    val a = Any()
    val b = Any()
    val diff = diffChildren(listOf(a, b, archive), listOf(archive, latestNew, a, b))
    assertThat(diff.removedNodes).isEmpty()
    assertThat(diff.insertedNodes).containsExactly(latestNew)
    assertThat(diff.insertedIndices).containsExactly(1)
    // every kept node reports as changed at its new index: no path breaks, and
    // the subtree state of the kept nodes survives
    assertThat(diff.changedNodes).containsExactly(archive, a, b)
    assertThat(diff.changedIndices).containsExactly(0, 2, 3)
  }

  @Test
  fun `a reorder reports the kept nodes as changed without a removal`() {
    // a rename of a kept node reorders the list; the kept nodes must not
    // report as removed and inserted, or the tree would tear their rows down
    val a = Any()
    val folder = Any()
    val renamed = Any()
    val diff = diffChildren(listOf(a, folder, renamed), listOf(a, renamed, folder))
    assertThat(diff.removedNodes).isEmpty()
    assertThat(diff.insertedNodes).isEmpty()
    assertThat(diff.changedNodes).containsExactly(a, renamed, folder)
    assertThat(diff.changedIndices).containsExactly(0, 1, 2)
  }

  @Test
  fun `a deletion and an insertion keep the order`() {
    val a = Any()
    val b = Any()
    val c = Any()
    val d = Any()
    val diff = diffChildren(listOf(a, b, c), listOf(a, c, d))
    assertThat(diff.removedNodes).containsExactly(b)
    assertThat(diff.removedIndices).containsExactly(1)
    assertThat(diff.insertedNodes).containsExactly(d)
    assertThat(diff.insertedIndices).containsExactly(2)
    assertThat(diff.changedNodes).containsExactly(a, c)
    assertThat(diff.changedIndices).containsExactly(0, 1)
  }
}
