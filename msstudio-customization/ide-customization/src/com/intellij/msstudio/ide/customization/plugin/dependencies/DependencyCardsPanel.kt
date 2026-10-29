// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.dependencies

import com.intellij.icons.AllIcons
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.CardPlacement
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Connection
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Kind
import com.intellij.msstudio.ide.customization.plugin.dependencies.DependencyMapLayout.Node
import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifest
import com.intellij.msstudio.ide.customization.server.plugins.ServerPluginsCache
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.ToolTipManager

/**
 * The dependency cards of the installed plugins.
 *
 * The chart grows upward: every trunk, a plugin that nothing depends on,
 * sits at the bottom, and a branch runs from its card up to the card of
 * every dependency. Each arrow uses its own free route. It keeps a gap
 * from each card and from each other arrow. A red dashed card is a
 * missing dependency, and a red card is a member of a `depend` cycle. A
 * plugin without relations parks in a side column at the right, away from
 * the arrows. The canvas is endless: the center of the map sits at zero,
 * the view starts with the map center in the middle of the tab, a drag
 * with the left button pans in every direction, and the mouse wheel zooms
 * to the cursor.
 */
internal class DependencyCardsPanel(
  private val project: Project,
  private val pluginsCache: ServerPluginsCache,
) : JPanel(BorderLayout()) {
  private val chartHolder = JPanel(BorderLayout())
  private var chart: Chart? = null
  private var lastZoom = 1f

  init {
    add(chartHolder, BorderLayout.CENTER)
    refresh()
  }

  /**
   * The refresh action for the component toolbar.
   */
  fun createRefreshAction(): AnAction {
    return object : AnAction(MSStudioBundle.serverPluginsRefreshText), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun actionPerformed(e: AnActionEvent) {
        refresh()
      }
    }
  }

  fun createZoomInAction(): AnAction = zoomAction(MSStudioBundle.serverPluginsEditorZoomInText, AllIcons.General.ZoomIn) { it.zoomIn() }

  fun createZoomOutAction(): AnAction = zoomAction(MSStudioBundle.serverPluginsEditorZoomOutText, AllIcons.General.ZoomOut) { it.zoomOut() }

  fun createZoomResetAction(): AnAction =
    zoomAction(MSStudioBundle.serverPluginsEditorZoomResetText, AllIcons.General.ActualZoom) { it.resetZoom() }

  private fun zoomAction(text: String, icon: Icon, operation: (Chart) -> Unit): AnAction {
    return object : AnAction(text, null, icon), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = chart != null
      }

      override fun actionPerformed(e: AnActionEvent) {
        chart?.let(operation)
      }
    }
  }

  val preferredFocus: JComponent? get() = chart

  private fun refresh() {
    ProgressManager.getInstance().run(
      object : Task.Backgroundable(project, MSStudioBundle.serverPluginsScanningTitle, true) {
        override fun run(indicator: ProgressIndicator) {
          val manifests = scanManifests(indicator)
          pluginsCache.cleanup()
          invokeLater {
            if (!project.isDisposed) {
              applyModel(manifests)
            }
          }
        }
      }
    )
  }

  private fun scanManifests(indicator: ProgressIndicator): List<PluginManifest>? {
    val basePath = project.basePath ?: return null
    val url = VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, "$basePath/plugins")
    val dir = VirtualFileManager.getInstance().refreshAndFindFileByUrl(url) ?: return null
    return dir.children
      .filter { it.isValid && !it.isDirectory && it.extension.equals("jar", ignoreCase = true) }
      .mapNotNull { jar ->
        indicator.checkCanceled()
        pluginsCache.jarInfo(jar)
      }
      .filter { !it.name.isNullOrEmpty() }
  }

  private fun applyModel(manifests: List<PluginManifest>?) {
    chart = null
    chartHolder.removeAll()
    when {
      manifests == null -> showLabel(MSStudioBundle.serverPluginsFolderMissing)
      manifests.isEmpty() -> showLabel(MSStudioBundle.serverPluginsEmptyText)
      else -> {
        val newChart = Chart(manifests, lastZoom)
        newChart.onZoomChanged = { lastZoom = it }
        chart = newChart
        chartHolder.add(newChart, BorderLayout.CENTER)
      }
    }
    chartHolder.revalidate()
    chartHolder.repaint()
  }

  private fun showLabel(text: String) {
    chartHolder.add(JBLabel(text, SwingConstants.CENTER), BorderLayout.CENTER)
  }

  /**
   * The endless canvas of the map. The center of the map is the origin:
   * a map point `L` lands on the screen at
   * `center + pan + (L - mapCenter) * zoom`, so the view can pan in
   * every direction without a bound.
   */
  private class Chart(manifests: List<PluginManifest>, initialZoom: Float) : JComponent() {
    var zoom: Float = initialZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
      private set

    var onZoomChanged: ((Float) -> Unit)? = null

    /** The offset of the view center from the map center, in screen pixels. */
    private var panX = 0f
    private var panY = 0f

    private val nameFont: Font = JBFont.label().asBold()
    private val plainFont: Font = JBFont.label()

    private val layout = DependencyMapLayout(manifests, ::cardWidthOf, JBUI.scale(1f))

    private val mapCenterX: Float get() = layout.bounds.width / 2f
    private val mapCenterY: Float get() = layout.bounds.height / 2f

    /** The press point of a pan, in screen coordinates. */
    private var panOrigin: Point? = null
    private var panStartX = 0f
    private var panStartY = 0f

    /**
     * The width of a card: the icon plus the wider text line, with
     * padding, so the content never gets cut off.
     */
    private fun cardWidthOf(node: Node): Int {
      val nameWidth = getFontMetrics(nameFont).stringWidth(node.name)
      val secondWidth = getFontMetrics(plainFont).stringWidth(secondLineOf(node))
      return JBUI.scale(8) + AllIcons.General.Error.iconWidth + JBUI.scale(6) +
             maxOf(nameWidth, secondWidth) + JBUI.scale(8)
    }

    private fun secondLineOf(node: Node): String {
      return when {
        node.kind == Kind.MISSING -> MSStudioBundle.serverPluginsDependenciesMissingText
        node.cycleChain != null -> MSStudioBundle.serverPluginsDependenciesCycleText(
          node.cycleChain.joinToString(" -> ")
        )
        else -> node.version.orEmpty()
      }
    }

    init {
      isOpaque = true
      ToolTipManager.sharedInstance().registerComponent(this)
      addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          if (e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger) {
            panOrigin = e.locationOnScreen
            panStartX = panX
            panStartY = panY
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
          }
        }

        override fun mouseReleased(e: MouseEvent) {
          panOrigin = null
          cursor = Cursor.getDefaultCursor()
        }
      })
      addMouseMotionListener(object : MouseMotionAdapter() {
        override fun mouseDragged(e: MouseEvent) {
          val origin = panOrigin ?: return
          // The grabbed point of the map stays under the cursor, so the
          // map moves with the mouse by the same delta.
          panX = panStartX + (e.locationOnScreen.x - origin.x)
          panY = panStartY + (e.locationOnScreen.y - origin.y)
          repaint()
        }
      })
      addMouseWheelListener { e ->
        e.consume()
        zoomAt(e.point, if (e.wheelRotation < 0) ZOOM_STEP else 1 / ZOOM_STEP)
      }
    }

    override fun getPreferredSize(): Dimension = Dimension(0, 0)

    fun zoomIn() = zoomAt(Point(width / 2, height / 2), ZOOM_STEP)

    fun zoomOut() = zoomAt(Point(width / 2, height / 2), 1 / ZOOM_STEP)

    fun resetZoom() {
      zoom = 1f
      panX = 0f
      panY = 0f
      onZoomChanged?.invoke(zoom)
      repaint()
    }

    /**
     * Zooms by [factor] and keeps the map point under [anchor] at the
     * same place on the screen.
     */
    private fun zoomAt(anchor: Point, factor: Float) {
      val oldZoom = zoom
      val newZoom = (oldZoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
      if (newZoom == oldZoom) return
      val mapX = mapCenterX + (anchor.x - width / 2f - panX) / oldZoom
      val mapY = mapCenterY + (anchor.y - height / 2f - panY) / oldZoom
      zoom = newZoom
      panX = anchor.x - width / 2f - (mapX - mapCenterX) * newZoom
      panY = anchor.y - height / 2f - (mapY - mapCenterY) * newZoom
      onZoomChanged?.invoke(newZoom)
      repaint()
    }

    private fun toMapPoint(px: Int, py: Int): Point =
      Point(
        Math.round(mapCenterX + (px - width / 2f - panX) / zoom),
        Math.round(mapCenterY + (py - height / 2f - panY) / zoom),
      )

    public override fun getToolTipText(event: MouseEvent): String? {
      val mapPoint = toMapPoint(event.x, event.y)
      val placement = layout.cards.firstOrNull { it.rect.contains(mapPoint.x, mapPoint.y) } ?: return null
      val node = placement.node
      val text = StringBuilder(node.name)
      node.version?.takeIf { it.isNotEmpty() }?.let { text.append(" (").append(it).append(")") }
      when {
        node.kind == Kind.MISSING -> text.append(" — ")
          .append(MSStudioBundle.serverPluginsDependenciesMissingText)
        node.cycleChain != null -> text.append(" — ").append(
          MSStudioBundle.serverPluginsDependenciesCycleText(
                                 node.cycleChain!!.joinToString(" -> ")))
      }
      return text.toString()
    }

    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      val g2 = g as Graphics2D
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.translate((width / 2f + panX).toDouble(), (height / 2f + panY).toDouble())
      g2.scale(zoom.toDouble(), zoom.toDouble())
      g2.translate(-mapCenterX.toDouble(), -mapCenterY.toDouble())
      val plainStroke = BasicStroke(JBUI.scale(1f))
      val alarmStroke = BasicStroke(JBUI.scale(1.5f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                    10f, floatArrayOf(JBUI.scale(5f), JBUI.scale(3f)), 0f)
      layout.connectors.forEach { connection ->
        g2.stroke = if (connection.alarm) alarmStroke else plainStroke
        g2.color = if (connection.alarm) JBColor.RED else JBColor.GRAY
        connection.points.zipWithNext().forEach { (from, to) ->
          paintArrowSegment(g2, from, to, connection.bridges)
        }
        paintArrowHead(g2, connection)
      }
      layout.cards.forEach { paintCard(g2, it) }
    }

    private fun paintArrowSegment(g2: Graphics2D, from: DependencyMapLayout.Pt, to: DependencyMapLayout.Pt,
                                  bridges: List<DependencyMapLayout.Pt>) {
      val gaps = bridges.filter { point -> pointOnSegment(point, from, to) }
      if (gaps.isEmpty()) {
        g2.drawLine(from.x, from.y, to.x, to.y)
        return
      }
      val horizontal = from.y == to.y
      val ordered = gaps.sortedBy { if (horizontal) it.x else it.y }
      var current = from
      ordered.forEach { bridge ->
        val before = bridgeOffset(bridge, horizontal, from, to, -JBUI.scale(4))
        val after = bridgeOffset(bridge, horizontal, from, to, JBUI.scale(4))
        g2.drawLine(current.x, current.y, before.x, before.y)
        current = after
      }
      g2.drawLine(current.x, current.y, to.x, to.y)
    }

    private fun pointOnSegment(point: DependencyMapLayout.Pt, from: DependencyMapLayout.Pt,
                               to: DependencyMapLayout.Pt): Boolean =
      point.x in minOf(from.x, to.x)..maxOf(from.x, to.x) && point.y in minOf(from.y, to.y)..maxOf(from.y, to.y)

    private fun bridgeOffset(point: DependencyMapLayout.Pt, horizontal: Boolean,
                             from: DependencyMapLayout.Pt, to: DependencyMapLayout.Pt, offset: Int):
      DependencyMapLayout.Pt {
      val direction = if (horizontal) Integer.signum(to.x - from.x) else Integer.signum(to.y - from.y)
      return if (horizontal) DependencyMapLayout.Pt(point.x + direction * offset, point.y)
      else DependencyMapLayout.Pt(point.x, point.y + direction * offset)
    }

    private fun paintArrowHead(g2: Graphics2D, connection: Connection) {
      val from = connection.points.getOrNull(connection.points.lastIndex - 1) ?: return
      val to = connection.points.last()
      val dx = (to.x - from.x).toDouble()
      val dy = (to.y - from.y).toDouble()
      val length = Math.hypot(dx, dy)
      if (length < 1) return
      val ux = dx / length
      val uy = dy / length
      val head = JBUI.scale(8).toDouble()
      val half = JBUI.scale(3.5f).toDouble()
      val baseX = to.x - ux * head
      val baseY = to.y - uy * head
      val sideX = -uy * half
      val sideY = ux * half
      g2.fillPolygon(
        intArrayOf(to.x, Math.round(baseX + sideX).toInt(), Math.round(baseX - sideX).toInt()),
        intArrayOf(to.y, Math.round(baseY + sideY).toInt(), Math.round(baseY - sideY).toInt()),
        3,
      )
    }

    private fun paintCard(g2: Graphics2D, placement: CardPlacement) {
      val node = placement.node
      val rect = placement.rect
      val missing = node.kind == Kind.MISSING
      val shape = RoundRectangle2D.Float(rect.x.toFloat(), rect.y.toFloat(),
                                         (rect.width - 1).toFloat(), (rect.height - 1).toFloat(),
                                         JBUI.scale(10).toFloat(), JBUI.scale(10).toFloat())
      g2.color = UIUtil.getListBackground()
      g2.fill(shape)
      g2.color = if (missing || node.inCycle) JBColor.RED else JBColor.border()
      g2.stroke = if (missing) BasicStroke(
        JBUI.scale(1f),
        BasicStroke.CAP_BUTT,
        BasicStroke.JOIN_MITER,
        10f,
        floatArrayOf(JBUI.scale(5f), JBUI.scale(3f)),
        0f
      )
      else BasicStroke(JBUI.scale(1f))

      g2.draw(shape)

      val icon = if (missing || node.inCycle) AllIcons.General.Error else AllIcons.Nodes.Plugin
      val iconX = rect.x + JBUI.scale(8)
      icon.paintIcon(this, g2, iconX, rect.y + (rect.height - icon.iconHeight) / 2)

      val textX = iconX + icon.iconWidth + JBUI.scale(6)
      g2.font = nameFont
      g2.color = if (missing) JBColor.RED else UIUtil.getLabelForeground()
      g2.drawString(node.name, textX, rect.y + JBUI.scale(19))

      val second = secondLineOf(node)
      if (second.isNotEmpty()) {
        g2.font = plainFont
        g2.color = if (missing || node.inCycle) JBColor.RED else UIUtil.getContextHelpForeground()
        g2.drawString(second, textX, rect.y + JBUI.scale(36))
      }
    }

    private companion object {
      const val ZOOM_MIN: Float = 0.25f
      const val ZOOM_MAX: Float = 4f
      const val ZOOM_STEP: Float = 1.25f
    }
  }
}
