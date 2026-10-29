// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.run

import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.msstudio.ide.customization.server.core.ServerCoreService
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.impl.customFrameDecorations.header.toolbar.HeaderToolbarButtonLook
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.Insets
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The replacement of the run widget in the main toolbar.
 *
 * The custom component of the action hosts the controls of the widget: the
 * core name, the run button, and the more button. The form follows the
 * original widget, which holds a toolbar inside its custom component.
 * A plain XML registration stays valid, because the class holds an action,
 * not a group. The action instances stay in the fields, because the toolbar
 * replaces the components of fresh action instances on every update.
 */
internal class ServerRunWidgetAction : DumbAwareAction(), CustomComponentAction {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  /**
   * The widget is not a command. The toolbar host never invokes it, and the
   * action search selects nothing with it.
   */
  override fun actionPerformed(event: AnActionEvent) {
  }

  override fun update(event: AnActionEvent) {
    // The custom component renders the controls. The presentation of this
    // action never appears.
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
    createRunWidgetToolbar().component

  /**
   * Builds the toolbar of the widget. It mimics the toolbar of the original
   * run widget: the same strategy, insets, button size, and look.
   */
  private fun createRunWidgetToolbar(): ActionToolbarImpl {
    val toolbar = ActionToolbarImpl(ActionPlaces.NEW_UI_RUN_TOOLBAR, ServerRunWidgetGroup(), true)
    toolbar.targetComponent = null
    toolbar.isReservePlaceAutoPopupIcon = false
    toolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
    toolbar.component.isOpaque = false
    val toolbarInsets: Supplier<Insets> = Supplier {
      // If an action sits to the left of the widget, the gap is needed.
      @Suppress("UseDPIAwareInsets") // the supplier must provide unscaled values
      Insets(0, if (isFirst(toolbar)) 0 else 12, 0, 16)
    }
    toolbar.component.border = JBUI.Borders.empty(JBInsets.create(toolbarInsets, toolbarInsets.get()))
    toolbar.setMinimumButtonSize {
      JBUI.size(JBUI.CurrentTheme.RunWidget.actionButtonWidth(), JBUI.CurrentTheme.RunWidget.toolbarHeight())
    }
    val buttonInsets: Supplier<Insets> = Supplier {
      val horizontal = JBUI.CurrentTheme.RunWidget.toolbarBorderDirectionalGap()
      val mainToolbarInsets = (JBUI.CurrentTheme.Toolbar.mainToolbarButtonInsets() as JBInsets).unscaled
      @Suppress("UseDPIAwareInsets") // the supplier must provide unscaled values
      Insets(mainToolbarInsets.top, horizontal, mainToolbarInsets.bottom, horizontal)
    }
    toolbar.setActionButtonBorder(JBEmptyBorder(JBInsets.create(buttonInsets, buttonInsets.get())))
    toolbar.setCustomButtonLook(HeaderToolbarButtonLook())
    return toolbar
  }

  /**
   * Returns whether the widget is the first control of its parent.
   */
  private fun isFirst(toolbar: ActionToolbarImpl): Boolean {
    val component = toolbar.component
    val parent = component.parent ?: return true
    return parent.getComponent(0) == component
  }
}

/**
 * The group of the widget controls. It is a programmatic object on purpose:
 * an ActionGroup class under an <action> tag in the XML is an error.
 */
private class ServerRunWidgetGroup : ActionGroup(), DumbAware {
  private val coreLabel = ServerRunCoreLabelAction()
  private val runStop = ServerRunAction()
  private val flags = ServerRunFlagsAction()

  override fun getChildren(e: AnActionEvent?): Array<AnAction> = arrayOf(coreLabel, runStop, flags)

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * The label with the detected server core next to the run button.
 *
 * It mirrors the text button of the original widget: the same font, color,
 * trim limits, and margins. It shows no own look like a hover background,
 * because it is an informational control and holds no popup.
 */
internal class ServerRunCoreLabelAction : DumbAwareAction(), CustomComponentAction {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  /**
   * The label is informational. The action system hides it in the action
   * search, so the control never reaches the user there.
   */
  override fun actionPerformed(event: AnActionEvent) {
  }

  override fun update(event: AnActionEvent) {
    val project = event.project
    if (project == null || project.isDisposed || event.place == ActionPlaces.ACTION_SEARCH) {
      event.presentation.isEnabledAndVisible = false
      return
    }

    val core = ServerCoreService.getInstance(project).detectedCore
    val name = core?.presentableName()
    if (name == null) {
      event.presentation.isVisible = false
      return
    }

    event.presentation.text = StringUtil.shortenTextWithEllipsis(name, NAME_MAX_LENGTH, NAME_TRIM_SUFFIX_LENGTH, true)
    event.presentation.description = MSStudioBundle.serverCoreDetectedText(name, core.fileName)
    event.presentation.isVisible = true
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent = RunCoreLabel()

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    component as RunCoreLabel
    component.text = presentation.text
    component.toolTipText = presentation.description
  }

  private class RunCoreLabel : JBLabel() {
    init {
      isOpaque = false
      isFocusable = false
      horizontalAlignment = SwingConstants.LEFT
      border = JBUI.Borders.empty(0, 10, 0, 6) // the margins of the text button of the original widget
      updateFont()
    }

    override fun updateUI() {
      super.updateUI()
      updateFont()
    }

    fun updateFont() {
      font = JBUI.CurrentTheme.RunWidget.configurationSelectorFont()
      foreground = JBUI.CurrentTheme.RunWidget.FOREGROUND
    }
  }

  private companion object {
    // The trim limits of the name in the original widget.
    const val NAME_MAX_LENGTH: Int = 41

    const val NAME_TRIM_SUFFIX_LENGTH: Int = 8
  }
}
