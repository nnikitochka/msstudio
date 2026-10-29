// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.minecraftColors

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

internal class MinecraftMessageFoldingBuilder : FoldingBuilderEx(), DumbAware {
  override fun buildFoldRegions(root: PsiElement, document: com.intellij.openapi.editor.Document, quick: Boolean): Array<FoldingDescriptor> {
    val descriptors = ArrayList<FoldingDescriptor>()
    PsiTreeUtil.processElements(root) { element ->
      if (element.isPreviewableMessageValue() && '\n' !in element.text) {
        val preview = MinecraftColorParser.parsePreview(element.text.unwrapYamlQuotes())
        if (preview.hasFormatting && preview.parts.isNotEmpty()) {
          descriptors.add(FoldingDescriptor(element.node, element.textRange, null, FOLD_PLACEHOLDER))
        }
      }
      true
    }
    return descriptors.toTypedArray()
  }

  override fun getPlaceholderText(node: ASTNode): String = FOLD_PLACEHOLDER

  override fun isCollapsedByDefault(node: ASTNode): Boolean = true
}

internal class MinecraftPropertiesMessageFoldingBuilder : FoldingBuilderEx(), DumbAware {
  override fun buildFoldRegions(root: PsiElement, document: com.intellij.openapi.editor.Document, quick: Boolean): Array<FoldingDescriptor> {
    val descriptors = ArrayList<FoldingDescriptor>()
    PsiTreeUtil.processElements(root) { element ->
      if (element.isPropertiesValue() && '\n' !in element.text) {
        val preview = MinecraftColorParser.parsePreview(element.text)
        if (preview.hasFormatting && preview.parts.isNotEmpty()) {
          descriptors.add(FoldingDescriptor(element.node, element.textRange, null, FOLD_PLACEHOLDER))
        }
      }
      true
    }
    return descriptors.toTypedArray()
  }

  override fun getPlaceholderText(node: ASTNode): String = FOLD_PLACEHOLDER

  override fun isCollapsedByDefault(node: ASTNode): Boolean = true
}

internal class MinecraftMessagePreviewEditorListener : EditorFactoryListener {
  override fun editorCreated(event: EditorFactoryEvent) {
    val editor = event.editor
    if (editor.isDisposed || editor.document !is com.intellij.openapi.editor.Document) return

    val controller = MinecraftMessagePreviewController(editor)
    editor.putUserData(CONTROLLER_KEY, controller)
    controller.install()
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    val controller = event.editor.getUserData(CONTROLLER_KEY)
    event.editor.putUserData(CONTROLLER_KEY, null)
    controller?.let(Disposer::dispose)
  }
}

private val CONTROLLER_KEY = Key<MinecraftMessagePreviewController>("MinecraftMessagePreviewController")

private class MinecraftMessagePreviewController(private val editor: Editor) : Disposable {
  private val inlays = ArrayList<Inlay<*>>()
  private var caretUpdateScheduled = false

  fun install() {
    val foldingModel = editor.foldingModel as? FoldingModelEx ?: return
    foldingModel.addListener(object : FoldingListener {
      override fun onFoldProcessingEnd() = update()
    }, this)
    editor.document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) = update()
    }, this)
    editor.caretModel.addCaretListener(object : CaretListener {
      override fun caretPositionChanged(event: CaretEvent) = schedulePreviewUpdate()
    }, this)
    ApplicationManager.getApplication().invokeLater { if (!editor.isDisposed) update() }
  }

  override fun dispose() {
    removeInlays()
  }

  private fun update() {
    if (editor.isDisposed) return

    removeInlays()
    val documentText = editor.document.charsSequence
    for (fold in editor.foldingModel.allFoldRegions) {
      if (!fold.isValid || fold.isExpanded || fold.placeholderText != FOLD_PLACEHOLDER) continue

      val value = documentText.subSequence(fold.startOffset, fold.endOffset)
      val preview = MinecraftColorParser.parsePreview(value.unwrapYamlQuotes())
      if (!preview.hasFormatting || preview.parts.isEmpty()) continue

      val previewLines = preview.parts.toPreviewLines()
      val renderer = MinecraftMessageRenderer(
        previewLines,
        editor.previewPanelWidth(previewLines),
        value.yamlQuote(),
        editor.syntaxForegroundAt(fold.startOffset),
        editor.syntaxForegroundAt(fold.endOffset - 1),
      )
      editor.inlayModel.addInlineElement(
        fold.endOffset,
        InlayProperties().relatesToPrecedingText(true).showWhenFolded(true),
        renderer.firstLineRenderer(),
      )?.let(inlays::add)
      renderer.remainingLinesRenderer(fold.startOffset)?.let { remainingLinesRenderer ->
        editor.inlayModel.addBlockElement(
          fold.endOffset,
          InlayProperties().relatesToPrecedingText(true).showWhenFolded(true),
          remainingLinesRenderer,
        )?.let(inlays::add)
      }
    }
  }

  private fun removeInlays() {
    for (inlay in inlays) {
      if (inlay.isValid) inlay.dispose()
    }
    inlays.clear()
  }

  private fun schedulePreviewUpdate() {
    if (caretUpdateScheduled) return

    caretUpdateScheduled = true
    ApplicationManager.getApplication().invokeLater {
      caretUpdateScheduled = false
      if (!editor.isDisposed) {
        updatePreviewAt(editor.caretModel.offset)
      }
    }
  }

  private fun updatePreviewAt(offset: Int) {
    val foldAtCaret = editor.foldingModel.getCollapsedRegionAtOffset(offset)
                      ?: editor.foldingModel.allFoldRegions.firstOrNull { !it.isExpanded && it.endOffset == offset }
    val foldsToCollapse = editor.foldingModel.allFoldRegions.filter {
      it.isValid &&
      it.isExpanded &&
      it.placeholderText == FOLD_PLACEHOLDER &&
      offset !in it.startOffset..it.endOffset
    }
    val shouldExpandFoldAtCaret = foldAtCaret?.placeholderText == FOLD_PLACEHOLDER
    if (foldsToCollapse.isEmpty() && !shouldExpandFoldAtCaret) return

    editor.foldingModel.runBatchFoldingOperation {
      for (expandedFold in foldsToCollapse) {
        expandedFold.isExpanded = false
      }
      if (shouldExpandFoldAtCaret) {
        foldAtCaret.isExpanded = true
      }
    }
  }
}

private class MinecraftMessageRenderer(
  private val lines: List<List<MinecraftPreviewPart>>,
  private val panelWidth: Int,
  private val quote: Char?,
  private val openingQuoteForeground: Color?,
  private val closingQuoteForeground: Color?,
  private val showOpeningQuote: Boolean = true,
  private val showClosingQuote: Boolean = true,
  private val sourceOffset: Int? = null,
  private val startsPanel: Boolean = true,
  private val endsPanel: Boolean = true,
) : EditorCustomElementRenderer {
  fun firstLineRenderer(): MinecraftMessageRenderer = MinecraftMessageRenderer(
    listOf(lines.first()),
    panelWidth,
    quote,
    openingQuoteForeground,
    closingQuoteForeground,
    showOpeningQuote = true,
    showClosingQuote = lines.size == 1,
    startsPanel = true,
    endsPanel = lines.size == 1,
  )

  fun remainingLinesRenderer(sourceOffset: Int): MinecraftMessageRenderer? =
    lines.drop(1).takeIf { it.isNotEmpty() }?.let { remainingLines ->
      MinecraftMessageRenderer(
        remainingLines,
        panelWidth,
        quote,
        openingQuoteForeground,
        closingQuoteForeground,
        showOpeningQuote = false,
        showClosingQuote = true,
        sourceOffset = sourceOffset,
        startsPanel = false,
        endsPanel = true,
      )
    }

  override fun calcWidthInPixels(inlay: Inlay<*>): Int {
    val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
    val metrics = inlay.editor.contentComponent.getFontMetrics(font)
    val quotesWidth = listOfNotNull(
      quote?.takeIf { showOpeningQuote },
      quote?.takeIf { showClosingQuote },
    ).sumOf(metrics::charWidth)
    return panelWidth + quotesWidth + inlay.horizontalOffset(sourceOffset)
  }

  override fun calcHeightInPixels(inlay: Inlay<*>): Int =
    inlay.editor.lineHeight * lines.size + PREVIEW_VERTICAL_PADDING * 2

  override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
    val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
    val metrics = inlay.editor.contentComponent.getFontMetrics(font)
    val graphics = g.create() as Graphics2D
    try {
      graphics.font = font
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val horizontalOffset = inlay.horizontalOffset(sourceOffset)
      val openingQuoteWidth = quote?.takeIf { showOpeningQuote }?.let(metrics::charWidth) ?: 0
      val previewBounds = Rectangle(
        targetRegion.x + horizontalOffset + openingQuoteWidth,
        targetRegion.y,
        panelWidth,
        targetRegion.height,
      )
      graphics.color = ColorUtil.mix(
        inlay.editor.colorsScheme.defaultBackground,
        inlay.editor.colorsScheme.defaultForeground,
        PREVIEW_BACKGROUND_FOREGROUND_BALANCE,
      )
      graphics.fillPreviewPanel(previewBounds, startsPanel, endsPanel)

      val defaultForeground = textAttributes.foregroundColor ?: inlay.editor.colorsScheme.defaultForeground
      if (quote != null && showOpeningQuote) {
        graphics.color = openingQuoteForeground ?: defaultForeground
        graphics.drawString(
          quote.toString(),
          targetRegion.x + horizontalOffset,
          targetRegion.y + PREVIEW_VERTICAL_PADDING + metrics.ascent,
        )
      }
      for ((lineIndex, line) in lines.withIndex()) {
        var x = previewBounds.x + PREVIEW_HORIZONTAL_PADDING
        val baseline = targetRegion.y + PREVIEW_VERTICAL_PADDING + inlay.editor.lineHeight * lineIndex + metrics.ascent
        for (part in line) {
          val partFont = font.forDecorations(part.decorations)
          val partMetrics = inlay.editor.contentComponent.getFontMetrics(partFont)
          graphics.font = partFont
          graphics.color = part.color ?: defaultForeground
          graphics.drawString(part.text, x, baseline)
          val width = partMetrics.stringWidth(part.text)
          if (MinecraftDecoration.UNDERLINED in part.decorations) {
            graphics.drawLine(x, baseline + 1, x + width, baseline + 1)
          }
          if (MinecraftDecoration.STRIKETHROUGH in part.decorations) {
            val y = baseline - partMetrics.ascent / 3
            graphics.drawLine(x, y, x + width, y)
          }
          x += width
        }
      }
      if (quote != null && showClosingQuote) {
        graphics.color = closingQuoteForeground ?: defaultForeground
        val baseline = targetRegion.y + PREVIEW_VERTICAL_PADDING + inlay.editor.lineHeight * (lines.size - 1) + metrics.ascent
        graphics.drawString(quote.toString(), previewBounds.x + previewBounds.width, baseline)
      }
    }
    finally {
      graphics.dispose()
    }
  }
}

private fun List<MinecraftPreviewPart>.toPreviewLines(): List<List<MinecraftPreviewPart>> {
  val lines = ArrayList<MutableList<MinecraftPreviewPart>>()
  lines.add(ArrayList())
  for (part in this) {
    val fragments = part.text.split('\n')
    for ((index, fragment) in fragments.withIndex()) {
      if (fragment.isNotEmpty()) {
        lines.last().add(part.copy(text = fragment))
      }
      if (index < fragments.lastIndex) {
        lines.add(ArrayList())
      }
    }
  }
  return lines
}

private fun Inlay<*>.horizontalOffset(sourceOffset: Int?): Int =
  sourceOffset?.let { editor.offsetToXY(it).x - editor.contentComponent.insets.left } ?: 0

private fun Editor.previewPanelWidth(lines: List<List<MinecraftPreviewPart>>): Int {
  val font = colorsScheme.getFont(EditorFontType.PLAIN)
  return PREVIEW_HORIZONTAL_PADDING * 2 + lines.maxOf { line ->
    line.sumOf { part -> contentComponent.getFontMetrics(font.forDecorations(part.decorations)).stringWidth(part.text) }
  }
}

private fun Graphics2D.fillPreviewPanel(bounds: Rectangle, startsPanel: Boolean, endsPanel: Boolean) {
  when {
    startsPanel && endsPanel -> fillRoundRect(
      bounds.x,
      bounds.y + PREVIEW_VERTICAL_PADDING,
      bounds.width,
      bounds.height - PREVIEW_VERTICAL_PADDING * 2,
      PREVIEW_CORNER_ARC,
      PREVIEW_CORNER_ARC,
    )
    startsPanel -> {
      fillRoundRect(
        bounds.x,
        bounds.y + PREVIEW_VERTICAL_PADDING,
        bounds.width,
        bounds.height - PREVIEW_VERTICAL_PADDING,
        PREVIEW_CORNER_ARC,
        PREVIEW_CORNER_ARC,
      )
      fillRect(
        bounds.x,
        bounds.y + PREVIEW_VERTICAL_PADDING + PREVIEW_CORNER_ARC / 2,
        bounds.width,
        bounds.height - PREVIEW_VERTICAL_PADDING - PREVIEW_CORNER_ARC / 2,
      )
    }
    endsPanel -> {
      fillRoundRect(
        bounds.x,
        bounds.y,
        bounds.width,
        bounds.height - PREVIEW_VERTICAL_PADDING,
        PREVIEW_CORNER_ARC,
        PREVIEW_CORNER_ARC,
      )
      fillRect(bounds.x, bounds.y, bounds.width, PREVIEW_CORNER_ARC / 2)
    }
    else -> fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
  }
}

private fun Font.forDecorations(decorations: Set<MinecraftDecoration>): Font {
  var style = Font.PLAIN
  if (MinecraftDecoration.BOLD in decorations) style = style or Font.BOLD
  if (MinecraftDecoration.ITALIC in decorations) style = style or Font.ITALIC
  return deriveFont(style)
}

private fun PsiElement.isPreviewableMessageValue(): Boolean = isYamlScalar() || isPropertiesValue()

private fun PsiElement.isYamlScalar(): Boolean = javaClass.name in YAML_SCALAR_CLASS_NAMES

private fun PsiElement.isPropertiesValue(): Boolean = javaClass.name == PROPERTIES_VALUE_CLASS_NAME

private fun CharSequence.unwrapYamlQuotes(): CharSequence =
  if (length > 1 && first() == last() && first() in YAML_QUOTES) subSequence(1, length - 1) else this

private fun CharSequence.yamlQuote(): Char? =
  firstOrNull()?.takeIf { length > 1 && it in YAML_QUOTES && last() == it }

private fun Editor.syntaxForegroundAt(offset: Int): Color? =
  highlighter.createIterator(offset).textAttributes.foregroundColor

private val YAML_SCALAR_CLASS_NAMES = setOf(
  "org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl",
  "org.jetbrains.yaml.psi.impl.YAMLQuotedTextImpl",
)

private const val PROPERTIES_VALUE_CLASS_NAME = "com.intellij.lang.properties.psi.impl.PropertyValueImpl"

private const val YAML_QUOTES = "\"'"
private const val FOLD_PLACEHOLDER = "\u200B"
private const val PREVIEW_HORIZONTAL_PADDING = 4
private const val PREVIEW_VERTICAL_PADDING = 2
private const val PREVIEW_CORNER_ARC = 8
private const val PREVIEW_BACKGROUND_FOREGROUND_BALANCE = 0.08

internal data class MinecraftPreviewPart(
  val text: String,
  val color: Color?,
  val decorations: Set<MinecraftDecoration> = emptySet(),
)

internal enum class MinecraftDecoration {
  BOLD,
  ITALIC,
  UNDERLINED,
  STRIKETHROUGH,
  OBFUSCATED,
}

internal data class MinecraftPreview(
  val parts: List<MinecraftPreviewPart>,
  val hasFormatting: Boolean,
)

internal object MinecraftColorParser {
  private val legacyColors = mapOf(
    '0' to 0x000000,
    '1' to 0x0000AA,
    '2' to 0x00AA00,
    '3' to 0x00AAAA,
    '4' to 0xAA0000,
    '5' to 0xAA00AA,
    '6' to 0xFFAA00,
    '7' to 0xAAAAAA,
    '8' to 0x555555,
    '9' to 0x5555FF,
    'a' to 0x55FF55,
    'b' to 0x55FFFF,
    'c' to 0xFF5555,
    'd' to 0xFF55FF,
    'e' to 0xFFFF55,
    'f' to 0xFFFFFF,
  ).mapValues { (_, rgb) -> Color(rgb) }

  private val namedColors = legacyColors + mapOf(
    "black" to Color(0x000000),
    "dark_blue" to Color(0x0000AA),
    "dark_green" to Color(0x00AA00),
    "dark_aqua" to Color(0x00AAAA),
    "dark_red" to Color(0xAA0000),
    "dark_purple" to Color(0xAA00AA),
    "gold" to Color(0xFFAA00),
    "gray" to Color(0xAAAAAA),
    "grey" to Color(0xAAAAAA),
    "dark_gray" to Color(0x555555),
    "dark_grey" to Color(0x555555),
    "blue" to Color(0x5555FF),
    "green" to Color(0x55FF55),
    "aqua" to Color(0x55FFFF),
    "red" to Color(0xFF5555),
    "light_purple" to Color(0xFF55FF),
    "yellow" to Color(0xFFFF55),
    "white" to Color(0xFFFFFF),
  )

  fun parsePreview(text: CharSequence): MinecraftPreview {
    val parts = ArrayList<RawPreviewPart>()
    val tags = ArrayList<OpenTag>()
    var style = PreviewStyle()
    var hasFormatting = false
    var index = 0

    fun append(value: CharSequence) {
      if (value.isEmpty()) return
      val last = parts.lastOrNull()
      if (last != null && last.style == style) {
        parts[parts.lastIndex] = last.copy(text = last.text + value)
      }
      else {
        parts.add(RawPreviewPart(value.toString(), style))
      }
    }

    while (index < text.length) {
      val tag = findMiniMessageTag(text, index)
      if (tag != null) {
        hasFormatting = true
        when (tag) {
          is MiniMessageTag.Open -> {
            tags.add(OpenTag(tag.name, style))
            style = tag.applyTo(style)
          }
          is MiniMessageTag.Close -> {
            val tagIndex = tags.indexOfLast { it.name == tag.name }
            if (tagIndex >= 0) {
              style = tags[tagIndex].previousStyle
              tags.subList(tagIndex, tags.size).clear()
            }
          }
          is MiniMessageTag.Newline -> append("\n")
          is MiniMessageTag.Reset -> {
            tags.clear()
            style = PreviewStyle()
          }
        }
        index = tag.endOffset
        continue
      }

      val legacyCode = findLegacyCode(text, index)
      if (legacyCode != null) {
        hasFormatting = true
        style = legacyCode.applyTo(style)
        index = legacyCode.endOffset
        continue
      }

      if (text[index] == '\\' && index + 1 < text.length && text[index + 1] == 'n') {
        hasFormatting = true
        append("\n")
        index += 2
        continue
      }
      if (text[index] == '\\' && index + 1 < text.length && text[index + 1] == '<') {
        append("<")
        index += 2
        continue
      }
      append(text.subSequence(index, index + 1))
      index++
    }
    return MinecraftPreview(applyColorTransforms(parts), hasFormatting)
  }

  private fun findMiniMessageTag(text: CharSequence, offset: Int): MiniMessageTag? {
    if (text[offset] != '<' || text.getOrNull(offset - 1) == '\\') return null
    val endOffset = findTagEnd(text, offset + 1) ?: return null
    val rawContent = text.subSequence(offset + 1, endOffset).toString().trim()
    if (rawContent.isEmpty()) return null
    if (rawContent.equals("reset", ignoreCase = true)) return MiniMessageTag.Reset(endOffset + 1)
    if (rawContent.equals("nl", ignoreCase = true) ||
        rawContent.equals("newline", ignoreCase = true) ||
        rawContent.equals("br", ignoreCase = true)) {
      return MiniMessageTag.Newline(endOffset + 1)
    }

    val content = rawContent.removeSuffix("/").trim()
    if (content.startsWith('/')) {
      return content.substring(1).substringBefore(':').canonicalTagName()
        .takeIf { it.isNotEmpty() }
        ?.let { MiniMessageTag.Close(it, endOffset + 1) }
    }

    val tagName = content.substringBefore(':').canonicalTagName()
    val arguments = content.substringAfter(':', "").splitTagArguments()
    val color = when (tagName) {
      "color" -> arguments.firstOrNull()?.let(::parseColor)
      else -> parseColor(tagName)
    }
    if (color != null) return MiniMessageTag.Open(tagName, endOffset + 1) { it.copy(color = color) }

    decorationFor(tagName)?.let { decoration ->
      val enabled = !rawContent.startsWith("!") && arguments.firstOrNull()?.lowercase() != "false"
      return MiniMessageTag.Open(tagName, endOffset + 1) { style ->
        style.copy(decorations = style.decorations.withDecoration(decoration, enabled))
      }
    }
    when (tagName) {
      "gradient" -> {
        val colors = arguments.mapNotNull(::parseColor).ifEmpty { defaultGradientColors }
        val effect = ColorTransform.Gradient(colors, arguments.lastOrNull()?.toFloatOrNull() ?: 0f)
        return MiniMessageTag.Open(tagName, endOffset + 1) { it.copy(transforms = it.transforms + effect) }
      }
      "rainbow" -> {
        val argument = arguments.firstOrNull().orEmpty()
        val reverse = argument.startsWith('!')
        return MiniMessageTag.Open(tagName, endOffset + 1) {
          it.copy(transforms = it.transforms + ColorTransform.Rainbow(argument.removePrefix("!").toFloatOrNull() ?: 0f, reverse))
        }
      }
      "pride" -> {
        val flag = arguments.firstOrNull()?.takeUnless { it.toFloatOrNull() != null } ?: "pride"
        val phase = arguments.lastOrNull()?.toFloatOrNull() ?: 0f
        return MiniMessageTag.Open(tagName, endOffset + 1) {
          it.copy(transforms = it.transforms + ColorTransform.Gradient(prideColors[flag.lowercase()] ?: prideColors.getValue("pride"), phase))
        }
      }
      "transition" -> {
        val colors = arguments.mapNotNull(::parseColor)
        if (colors.isNotEmpty()) {
          val phase = arguments.lastOrNull()?.toFloatOrNull() ?: 0f
          return MiniMessageTag.Open(tagName, endOffset + 1) { it.copy(color = interpolate(colors, phase.normalizedPhase())) }
        }
      }
      "shadow", "font", "click", "hover", "insert" -> {
        return MiniMessageTag.Open(tagName, endOffset + 1) { it }
      }
      "key", "lang", "lang_or", "selector", "score", "nbt", "sprite", "head" -> {
        return MiniMessageTag.Open(tagName, endOffset + 1) { it }
      }
    }
    return null
  }

  private fun findTagEnd(text: CharSequence, startOffset: Int): Int? {
    var quote: Char? = null
    var escaped = false
    for (index in startOffset until text.length) {
      val character = text[index]
      if (escaped) {
        escaped = false
      }
      else if (character == '\\') {
        escaped = true
      }
      else if (character == '"' || character == '\'') {
        quote = if (quote == character) null else quote ?: character
      }
      else if (character == '>' && quote == null) {
        return index
      }
    }
    return null
  }

  private fun findLegacyCode(text: CharSequence, offset: Int): LegacyCode? {
    if ((text[offset] != '§' && text[offset] != '&') || offset + 1 >= text.length) return null

    if (text[offset + 1] == '#' && offset + 8 <= text.length) {
      val color = parseColor(text.subSequence(offset + 1, offset + 8).toString())
      if (color != null) return LegacyCode(color = color, endOffset = offset + 8)
    }

    if (text[offset + 1].equals('x', ignoreCase = true) && offset + 14 <= text.length) {
      val digits = CharArray(6)
      for (digitIndex in digits.indices) {
        val markerOffset = offset + 2 + digitIndex * 2
        if (text[markerOffset] != text[offset]) return null
        digits[digitIndex] = text[markerOffset + 1]
      }
      val color = parseColor("#${digits.concatToString()}") ?: return null
      return LegacyCode(color = color, endOffset = offset + 14)
    }

    val code = text[offset + 1].lowercaseChar()
    legacyColors[code]?.let { return LegacyCode(color = it, endOffset = offset + 2) }
    if (code == 'r') return LegacyCode(reset = true, endOffset = offset + 2)
    legacyDecorations[code]?.let { return LegacyCode(decoration = it, endOffset = offset + 2) }
    return null
  }

  private fun applyColorTransforms(parts: List<RawPreviewPart>): List<MinecraftPreviewPart> {
    val transformLengths = HashMap<ColorTransform, Int>()
    for (part in parts) {
      for (transform in part.style.transforms) {
        transformLengths.merge(transform, part.text.length, Int::plus)
      }
    }
    val transformOffsets = HashMap<ColorTransform, Int>()
    val result = ArrayList<MinecraftPreviewPart>()
    for (part in parts) {
      for (character in part.text) {
        var color = part.style.color
        for (transform in part.style.transforms) {
          val length = transformLengths.getValue(transform)
          val offset = transformOffsets.getOrDefault(transform, 0)
          color = transform.colorAt(offset, length)
          transformOffsets[transform] = offset + 1
        }
        result.append(MinecraftPreviewPart(character.toString(), color, part.style.decorations))
      }
    }
    return result
  }

  private fun MutableList<MinecraftPreviewPart>.append(part: MinecraftPreviewPart) {
    val last = lastOrNull()
    if (last != null && last.color == part.color && last.decorations == part.decorations) {
      this[lastIndex] = last.copy(text = last.text + part.text)
    }
    else {
      add(part)
    }
  }

  private fun parseColor(value: String): Color? {
    namedColors[value.lowercase()]?.let { return it }
    if (value.length !in setOf(7, 9) || value[0] != '#') return null
    return value.substring(1).toLongOrNull(16)?.let { rgb -> Color(rgb.toInt(), value.length == 9) }
  }

  private fun String.canonicalTagName(): String = when (lowercase().removePrefix("!")) {
    "colour", "c" -> "color"
    "b" -> "bold"
    "em", "i" -> "italic"
    "u" -> "underlined"
    "st" -> "strikethrough"
    "obf" -> "obfuscated"
    "tr", "translate" -> "lang"
    "tr_or", "translate_or" -> "lang_or"
    "sel" -> "selector"
    "data" -> "nbt"
    else -> lowercase().removePrefix("!")
  }

  private fun String.splitTagArguments(): List<String> =
    split(':').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }

  private fun decorationFor(tagName: String): MinecraftDecoration? = when (tagName) {
    "bold" -> MinecraftDecoration.BOLD
    "italic" -> MinecraftDecoration.ITALIC
    "underlined" -> MinecraftDecoration.UNDERLINED
    "strikethrough" -> MinecraftDecoration.STRIKETHROUGH
    "obfuscated" -> MinecraftDecoration.OBFUSCATED
    else -> null
  }

  private fun Set<MinecraftDecoration>.withDecoration(decoration: MinecraftDecoration, enabled: Boolean): Set<MinecraftDecoration> =
    if (enabled) this + decoration else this - decoration

  private fun Float.normalizedPhase(): Float = ((coerceIn(-1f, 1f) + 1f) / 2f)

  private fun interpolate(colors: List<Color>, position: Float): Color {
    if (colors.size == 1) return colors.single()
    val scaledPosition = position.coerceIn(0f, 1f) * (colors.size - 1)
    val index = scaledPosition.toInt().coerceAtMost(colors.lastIndex - 1)
    val fraction = scaledPosition - index
    val start = colors[index]
    val end = colors[index + 1]
    return Color(
      (start.red + (end.red - start.red) * fraction).toInt(),
      (start.green + (end.green - start.green) * fraction).toInt(),
      (start.blue + (end.blue - start.blue) * fraction).toInt(),
    )
  }

  private data class PreviewStyle(
    val color: Color? = null,
    val decorations: Set<MinecraftDecoration> = emptySet(),
    val transforms: List<ColorTransform> = emptyList(),
  )

  private data class RawPreviewPart(val text: String, val style: PreviewStyle)

  private data class OpenTag(val name: String, val previousStyle: PreviewStyle)

  private sealed class MiniMessageTag(open val endOffset: Int) {
    data class Open(
      val name: String,
      override val endOffset: Int,
      val applyTo: (PreviewStyle) -> PreviewStyle,
    ) : MiniMessageTag(endOffset)

    data class Close(val name: String, override val endOffset: Int) : MiniMessageTag(endOffset)
    data class Newline(override val endOffset: Int) : MiniMessageTag(endOffset)
    data class Reset(override val endOffset: Int) : MiniMessageTag(endOffset)
  }

  private sealed class ColorTransform {
    data class Gradient(val colors: List<Color>, val phase: Float) : ColorTransform()
    data class Rainbow(val phase: Float, val reversed: Boolean) : ColorTransform()

    fun colorAt(offset: Int, length: Int): Color = when (this) {
      is Gradient -> interpolate(colors, (offset.toFloat() / (length - 1).coerceAtLeast(1) + phase / 2f).coerceIn(0f, 1f))
      is Rainbow -> Color.getHSBColor(((offset.toFloat() / length.coerceAtLeast(1)) + phase).let { if (reversed) -it else it }.mod(1f), 0.8f, 1f)
    }
  }

  private data class LegacyCode(
    val color: Color? = null,
    val decoration: MinecraftDecoration? = null,
    val reset: Boolean = false,
    val endOffset: Int,
  ) {
    fun applyTo(style: PreviewStyle): PreviewStyle = when {
      reset -> PreviewStyle()
      color != null -> style.copy(color = color, decorations = emptySet(), transforms = emptyList())
      decoration != null -> style.copy(decorations = style.decorations + decoration)
      else -> style
    }
  }

  private val legacyDecorations = mapOf(
    'k' to MinecraftDecoration.OBFUSCATED,
    'l' to MinecraftDecoration.BOLD,
    'm' to MinecraftDecoration.STRIKETHROUGH,
    'n' to MinecraftDecoration.UNDERLINED,
    'o' to MinecraftDecoration.ITALIC,
  )

  private val prideColors = mapOf(
    "pride" to listOf(Color(0xE40303), Color(0xFF8C00), Color(0xFFED00), Color(0x008026), Color(0x24408E), Color(0x732982)),
    "trans" to listOf(Color(0x5BCEFA), Color(0xF5A9B8), Color.WHITE, Color(0xF5A9B8), Color(0x5BCEFA)),
    "bi" to listOf(Color(0xD60270), Color(0x9B4F96), Color(0x0038A8)),
    "pan" to listOf(Color(0xFF218C), Color(0xFFD800), Color(0x21B1FF)),
    "nb" to listOf(Color(0xFFF430), Color.WHITE, Color(0x9C59D1), Color.BLACK),
  )

  private val defaultGradientColors = listOf(Color(0xFF5555), Color(0x5555FF), Color(0x55FF55))
}
