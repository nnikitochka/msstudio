// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.hexGenerator

import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.DefaultListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.StyleConstants

internal class HexGeneratorPanel {
  private val textField = JTextPane().apply {
    text = "MSStudio"
    font = MonocraftFont.font
  }
  private val outputArea = JBTextArea(5, 0).apply {
    font = MonocraftFont.font
    isEditable = false
    lineWrap = true
    wrapStyleWord = false
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    toolTipText = MSStudioBundle.hexClickToCopyHint
  }
  private val inputScroll = JBScrollPane(textField).apply {
    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
  }
  private val outputScroll = JBScrollPane(outputArea).apply {
    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
  }
  private val formatBox = JComboBox(MinecraftTextFormat.entries.toTypedArray()).apply {
    renderer = messageRenderer { (it as? MinecraftTextFormat)?.messageKey }
    selectedItem = MinecraftTextFormat.MINI_MESSAGE
  }
  private val colorStopsPanel = JPanel(GridBagLayout())
  private var stops = HexGeneratorState().colors
  private var updateScheduled = false
  private var isUpdatingInputStyle = false

  val component: JComponent = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(8)
    add(createMainPanel(), BorderLayout.CENTER)
  }

  init {
    installListeners()
    renderColorStops()
    updateOutput()
  }

  private fun createMainPanel(): JComponent {
    val inputPanel = JPanel(BorderLayout()).apply {
      border = BorderFactory.createTitledBorder(MSStudioBundle.hexInputTitle)
      add(inputScroll, BorderLayout.CENTER)
    }
    val formatPanel = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
      border = BorderFactory.createTitledBorder(MSStudioBundle.hexFormatTitle)
      add(JBLabel(MSStudioBundle.hexFormatLabel))
      add(formatBox)
    }
    val colorsPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      border = BorderFactory.createTitledBorder(MSStudioBundle.hexColorsTitle)
      add(colorStopsPanel, BorderLayout.CENTER)
      add(createColorActions(), BorderLayout.SOUTH)
    }
    val resultPanel = JPanel(BorderLayout()).apply {
      border = BorderFactory.createTitledBorder(MSStudioBundle.hexResultTitle)
      add(outputScroll, BorderLayout.CENTER)
    }
    val textStack = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(inputPanel)
      add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        add(formatPanel, BorderLayout.WEST)
        add(resultPanel, BorderLayout.CENTER)
      })
    }
    val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
      add(textStack, BorderLayout.NORTH)
      add(colorsPanel, BorderLayout.CENTER)
    }
    return JBScrollPane(content).apply {
      border = JBUI.Borders.empty()
      horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
  }

  private fun createColorActions(): JComponent = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
    add(JButton(MSStudioBundle.hexColorAddText).apply {
      addActionListener { stops = stops + GradientStop(HexColor(255, 255, 255), 100); renderColorStops(); updateOutput() }
    })
    add(JButton(MSStudioBundle.hexColorReverseText).apply {
      addActionListener { stops = HexGradientGenerator.reverse(stops); renderColorStops(); updateOutput() }
    })
    add(JButton(MSStudioBundle.hexColorInvertText).apply {
      addActionListener { stops = HexGradientGenerator.invert(stops); renderColorStops(); updateOutput() }
    })
    add(JButton(MSStudioBundle.hexColorDuplicateText).apply {
      addActionListener { stops = HexGradientGenerator.duplicate(stops); renderColorStops(); updateOutput() }
    })
  }

  private fun renderColorStops() {
    colorStopsPanel.removeAll()
    stops.forEachIndexed { index, stop ->
      val colorPanel = ColorPanel().apply {
        selectedColor = stop.color.toAwtColor()
        addActionListener {
          selectedColor?.let { color -> updateStop(index, HexColor(color.red, color.green, color.blue), null) }
        }
      }
      val positionField = JBTextField(stop.position.toString(), 4).apply {
        document.addDocumentListener(listener { text.toIntOrNull()?.takeIf { it in 0..100 }?.let { updateStop(index, null, it) } })
      }
      colorStopsPanel.add(colorPanel, constraints(0, index, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
      colorStopsPanel.add(positionField, constraints(1, index, fill = GridBagConstraints.NONE))
      colorStopsPanel.add(JButton(MSStudioBundle.hexColorRemoveText).apply {
        isEnabled = stops.size > 1
        addActionListener { stops = stops.toMutableList().also { it.removeAt(index) }; renderColorStops(); updateOutput() }
      }, constraints(2, index, fill = GridBagConstraints.NONE))
    }
    colorStopsPanel.revalidate()
    colorStopsPanel.repaint()
  }

  private fun updateStop(index: Int, color: HexColor?, position: Int?) {
    val existing = stops[index]
    stops = stops.toMutableList().also { it[index] = GradientStop(color ?: existing.color, position ?: existing.position) }
    updateOutput()
  }

  private fun installListeners() {
    textField.document.addDocumentListener(listener { if (!isUpdatingInputStyle) scheduleUpdate() })
    formatBox.addActionListener { updateOutput() }
    outputArea.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(event: MouseEvent) {
        CopyPasteManager.getInstance().setContents(StringSelection(outputArea.text))
      }
    })
  }

  private fun scheduleUpdate() {
    if (!updateScheduled) {
      updateScheduled = true
      SwingUtilities.invokeLater {
        updateScheduled = false
        updateOutput()
      }
    }
  }

  private fun updateOutput() {
    val generatorState = state()
    outputArea.text = HexGradientGenerator.generate(generatorState)
    renderInput(generatorState)
    fitTextHeights()
  }

  private fun fitTextHeights() {
    SwingUtilities.invokeLater {
      fitScroll(textField, inputScroll)
      fitScroll(outputArea, outputScroll)
    }
  }

  private fun fitScroll(view: JComponent, scroll: JBScrollPane) {
    val lineHeight = view.getFontMetrics(view.font).height
    val minHeight = lineHeight + JBUI.scale(14)
    val maxHeight = lineHeight * 6 + JBUI.scale(14)
    val needed = view.preferredSize.height + JBUI.scale(6)
    scroll.preferredSize = java.awt.Dimension(scroll.preferredSize.width, needed.coerceIn(minHeight, maxHeight))
    scroll.revalidate()
  }

  private fun renderInput(generatorState: HexGeneratorState) {
    val document = textField.styledDocument
    val caret = textField.caret
    val dot = caret.dot.coerceIn(0, document.length)
    val mark = caret.mark.coerceIn(0, document.length)
    isUpdatingInputStyle = true
    try {
      val colors = HexGradientGenerator.generateColors(
        generatorState.text.codePointCount(0, generatorState.text.length),
        generatorState.colors,
      )
      var offset = 0
      for ((index, codePoint) in generatorState.text.codePoints().toArray().withIndex()) {
        val length = Character.charCount(codePoint)
        val attributes = textField.addStyle("input-color-$offset", null).apply {
          StyleConstants.setForeground(this, colors[index].toAwtColor())
        }
        document.setCharacterAttributes(offset, length, attributes, true)
        offset += length
      }
    }
    finally {
      isUpdatingInputStyle = false
      caret.dot = mark
      caret.moveDot(dot)
    }
  }

  private fun state(): HexGeneratorState = HexGeneratorState(
    text = textField.text,
    colors = stops,
    format = formatBox.selectedItem as MinecraftTextFormat,
  )
}

private fun constraints(
  x: Int,
  y: Int,
  weightx: Double = 0.0,
  weighty: Double = 0.0,
  width: Int = 1,
  height: Int = 1,
  fill: Int = GridBagConstraints.NONE,
  anchor: Int = GridBagConstraints.CENTER,
): GridBagConstraints = GridBagConstraints().apply {
  gridx = x
  gridy = y
  gridwidth = width
  gridheight = height
  this.weightx = weightx
  this.weighty = weighty
  this.fill = fill
  this.anchor = anchor
  insets = JBUI.insets(JBUI.scale(2), JBUI.scale(2), JBUI.scale(2), JBUI.scale(2))
}

private fun listener(onChange: () -> Unit): DocumentListener = object : DocumentListener {
  override fun insertUpdate(event: DocumentEvent) = onChange()

  override fun removeUpdate(event: DocumentEvent) = onChange()

  override fun changedUpdate(event: DocumentEvent) = onChange()
}

private fun messageRenderer(messageKey: (Any?) -> String?): DefaultListCellRenderer = object : DefaultListCellRenderer() {
  override fun getListCellRendererComponent(
    list: JList<*>?,
    value: Any?,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ) = super.getListCellRendererComponent(
    list,
    messageKey(value)?.let(MSStudioBundle::message) ?: value,
    index,
    isSelected,
    cellHasFocus,
  )
}
