// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.files

/**
 * The diff of a children list replacement, split into the event classes of
 * `AsyncTreeModel.applyNodeToUiTree`.
 *
 * A node is removed only when the new list does not hold it, and inserted
 * only when the old list does not hold it. Every other node is kept, and a
 * kept node never reports as removed and inserted: its path survives, so the
 * selection and the expanded state survive. This is the smooth update: the
 * rows of a moved folder keep their subtree and their state.
 *
 * The kept nodes report as changed with their new indices when any part of
 * the replacement fires, exactly as the AsyncTreeModel reports the contained
 * children. The layout cache rebinds the moved rows in two passes, because
 * the children at the new indices may collide with the old paths of other
 * children.
 *
 * An identical replacement fires nothing.
 */
internal class ChildrenDiff<T>(
  @JvmField val removedNodes: List<T>,
  @JvmField val removedIndices: IntArray,
  @JvmField val insertedNodes: List<T>,
  @JvmField val insertedIndices: IntArray,
  @JvmField val changedNodes: List<T>,
  @JvmField val changedIndices: IntArray,
)

/**
 * Splits the replacement of [oldChildren] with [newChildren] into the event
 * classes. The nodes compare by identity.
 */
internal fun <T> diffChildren(oldChildren: List<T>, newChildren: List<T>): ChildrenDiff<T> {
  val oldIndices = HashMap<T, Int>()
  oldChildren.forEachIndexed { index, child -> oldIndices[child] = index }
  val newIndices = HashMap<T, Int>()
  newChildren.forEachIndexed { index, child -> newIndices[child] = index }

  val removedNodes = ArrayList<T>()
  oldChildren.forEach { child -> if (!newIndices.containsKey(child)) removedNodes.add(child) }
  val insertedNodes = ArrayList<T>()
  newChildren.forEach { child -> if (!oldIndices.containsKey(child)) insertedNodes.add(child) }

  // The contained children of the AsyncTreeModel: every kept child reports as
  // changed with its new index when the replacement fires at all. A reorder
  // of the kept nodes is such a case: the layout cache rebinds the moved
  // rows, and no path breaks.
  val fired = removedNodes.isNotEmpty() || insertedNodes.isNotEmpty() ||
    newChildren.any { child -> oldIndices.containsKey(child) && oldIndices[child] != newIndices[child] }
  val changedNodes = if (fired) newChildren.filter { oldIndices.containsKey(it) } else emptyList()

  return ChildrenDiff(
    removedNodes, IntArray(removedNodes.size) { oldIndices[removedNodes[it]]!! },
    insertedNodes, IntArray(insertedNodes.size) { newIndices[insertedNodes[it]]!! },
    changedNodes, IntArray(changedNodes.size) { newIndices[changedNodes[it]]!! },
  )
}
