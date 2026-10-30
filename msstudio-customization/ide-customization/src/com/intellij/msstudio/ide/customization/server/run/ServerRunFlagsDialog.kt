// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.run

import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.msstudio.ide.customization.MSStudioBundle
import com.intellij.msstudio.ide.customization.server.core.ServerCoreService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxModel
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.TabbedPaneWrapper
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.text.ParseException
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerModel
import javax.swing.SpinnerNumberModel
import kotlin.math.roundToInt

/**
 * The dialog of the start parameters of the server.
 *
 * It is a constructor of the parameters. Every argument holds a row of the
 * editor, so the user adds them, edits them, removes them, or changes their
 * order. The JVM options and the program arguments live on the two tabs.
 * OK saves the rows for the next launch. An empty constructor means the
 * defaults of the core profile.
 */
private class ServerRunFlagsDialog(project: Project) : DialogWrapper(project) {
  private val service = ServerRunFlagService.getInstance(project)
  private val core = ServerCoreService.getInstance(project).detectedCore

  private val jvmEditor = ArgumentsEditor(service.jvmArguments(core).filter { !it.isMemoryFlagRow() })
  private val programEditor = ArgumentsEditor(service.programArguments(core))

  private val initialMemory = MemoryAdjuster(
    MSStudioBundle.serverRunFlagsMemoryInitialText, service.initialMemoryMb() != null, initialMemoryValue(),
    MemoryUnit.fromPersisted(service.initialMemoryUnit()))

  private val maximumMemory = MemoryAdjuster(
    MSStudioBundle.serverRunFlagsMemoryMaximumText, maximumMemoryEnabled(), maximumMemoryValue(),
    MemoryUnit.fromPersisted(service.maximumMemoryUnit()))

  private val javaSelector by lazy { JavaRuntimeSelector(project, myDisposable) }

  init {
    title = MSStudioBundle.serverRunFlagsTitle
    init()
  }

  override fun createCenterPanel(): JComponent {
    // The memory block sits in the north, so it keeps its preferred height
    // when the window grows. Only the table of the arguments stretches.
    val memoryPanel = JPanel(GridLayout(3, 1)).apply {
      border = JBUI.Borders.emptyTop(4)
      add(JBLabel(MSStudioBundle.serverRunFlagsMemoryTitle).apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
      })
      add(initialMemory.component)
      add(maximumMemory.component)
    }
    val jvmTab = JPanel(BorderLayout()).apply {
      add(memoryPanel, BorderLayout.NORTH)
      add(jvmEditor.component, BorderLayout.CENTER)
    }

    val tabs = TabbedPaneWrapper(myDisposable)
    tabs.addTab(MSStudioBundle.serverRunFlagsJvmText, jvmTab)
    tabs.addTab(MSStudioBundle.serverRunFlagsProgramText, programEditor.component)
    tabs.addTab(MSStudioBundle.serverRunFlagsJavaText, javaSelector.component)

    val hint = JBLabel(MSStudioBundle.serverRunFlagsHintText).apply {
      foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    return JPanel(BorderLayout()).apply {
      add(tabs.component, BorderLayout.CENTER)
      add(hint, BorderLayout.SOUTH)
    }
  }

  override fun createLeftSideActions(): Array<Action> = arrayOf(
    object : AbstractAction(MSStudioBundle.serverRunFlagsRestoreText) {
      override fun actionPerformed(event: ActionEvent) {
        service.reset()
        jvmEditor.load(service.jvmArguments(core).filter { !it.isMemoryFlagRow() })
        programEditor.load(service.programArguments(core))
        initialMemory.load(
          service.initialMemoryMb() != null, initialMemoryValue(), MemoryUnit.fromPersisted(service.initialMemoryUnit()))
        maximumMemory.load(
          maximumMemoryEnabled(), maximumMemoryValue(), MemoryUnit.fromPersisted(service.maximumMemoryUnit()))
        javaSelector.resetToProjectSdk()
      }
    }
  )

  override fun doOKAction() {
    try {
      javaSelector.applyModel()
    }
    catch (exception: ConfigurationException) {
      Messages.showErrorDialog(contentPane, exception.messageHtml.toString(), exception.title)
      return
    }
    service.setJvmArguments(jvmEditor.arguments())
    service.setProgramArguments(programEditor.arguments())
    val maximumMb = maximumMemory.memoryMb() ?: ServerRunFlagService.DISABLED_MEMORY_MB
    service.setMemory(
      initialMemory.memoryMb(), maximumMb, initialMemory.unit().persisted, maximumMemory.unit().persisted)
    super.doOKAction()
  }

  private fun initialMemoryValue(): Int = service.initialMemoryMb() ?: DEFAULT_INITIAL_MEMORY_MB

  /**
   * The maximum size is on by default. Only a stored 0 turns it off.
   */
  private fun maximumMemoryEnabled(): Boolean = (service.maximumMemoryMb() ?: 1) > 0

  private fun maximumMemoryValue(): Int =
    service.maximumMemoryMb()?.takeIf { it > 0 } ?: ServerRunFlagService.DEFAULT_MAXIMUM_MEMORY_MB

  private fun String.isMemoryFlagRow(): Boolean = startsWith("-Xms") || startsWith("-Xmx")

  private companion object {
    const val DEFAULT_INITIAL_MEMORY_MB: Int = 1024
  }
}

/**
 * The unit of a memory adjuster.
 */
private enum class MemoryUnit(val label: String, val persisted: String) {
  MB("MB", ServerRunFlagService.MEMORY_UNIT_MB),
  GB("GB", ServerRunFlagService.MEMORY_UNIT_GB);

  companion object {
    fun fromPersisted(saved: String?): MemoryUnit = if (saved == ServerRunFlagService.MEMORY_UNIT_GB) GB else MB
  }
}

/**
 * The adjuster of one memory size of the JVM heap.
 *
 * The checkbox enables the size. The spinner holds the number, and the combo
 * holds the unit: MB or GB. A unit switch converts the number. The MB unit is
 * the default selection.
 */
private class MemoryAdjuster(labelText: String, enabled: Boolean, valueMb: Int, savedUnit: MemoryUnit) {
  private val checkbox = JBCheckBox(labelText, enabled)
  private val unitCombo = JComboBox(MemoryUnit.entries.map { it.label }.toTypedArray())
  private val spinner = JSpinner()

  /**
   * The unit of the current spinner model. It differs from the combo
   * selection inside a unit switch, because the value is read under the old
   * unit before the model moves to the new one.
   */
  private var currentUnit = MemoryUnit.MB

  init {
    spinner.model = model(savedUnit, valueMb)
    currentUnit = savedUnit
    unitCombo.selectedIndex = savedUnit.ordinal
    unitCombo.addActionListener {
      val newUnit = MemoryUnit.entries[unitCombo.selectedIndex]
      if (newUnit == currentUnit) return@addActionListener
      // The old value converts under the old unit, not under the new one.
      val mb = memoryMb(currentUnit)
      currentUnit = newUnit
      spinner.model = model(newUnit, mb)
    }
    updateEnabled()
    checkbox.addItemListener { updateEnabled() }
  }

  val component: JComponent = JPanel(BorderLayout()).apply {
    add(checkbox, BorderLayout.WEST)
    add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
      add(spinner)
      add(unitCombo)
    }, BorderLayout.EAST)
  }

  /**
   * Holds the selected size in MB, or null when the adjuster is off.
   */
  fun memoryMb(): Int? = if (checkbox.isSelected) memoryMb(currentUnit) else null

  /**
   * Holds the selected unit of the adjuster.
   */
  fun unit(): MemoryUnit = currentUnit

  fun load(enabled: Boolean, valueMb: Int, unit: MemoryUnit) {
    checkbox.isSelected = enabled
    currentUnit = unit
    unitCombo.selectedIndex = unit.ordinal
    spinner.model = model(unit, valueMb)
    updateEnabled()
  }

  private fun memoryMb(unit: MemoryUnit): Int {
    try {
      spinner.commitEdit()
    }
    catch (_: ParseException) {
      // An invalid number keeps the last valid value of the spinner.
    }
    val number = spinner.value as Number
    return when (unit) {
      MemoryUnit.GB -> (number.toDouble() * MB_PER_GB).roundToInt()
      MemoryUnit.MB -> number.toInt()
    }
  }

  private fun model(unit: MemoryUnit, mb: Int): SpinnerModel = when (unit) {
    MemoryUnit.MB -> SpinnerNumberModel(mb.coerceIn(MIN_MB, MAX_MB), MIN_MB, MAX_MB, STEP_MB)
    MemoryUnit.GB -> SpinnerNumberModel(mb.coerceIn(MIN_MB, MAX_MB) / MB_PER_GB, MIN_GB, MAX_GB, STEP_GB)
  }

  private fun updateEnabled() {
    spinner.isEnabled = checkbox.isSelected
    unitCombo.isEnabled = checkbox.isSelected
  }

  private companion object {
    const val MB_PER_GB: Double = 1024.0

    const val MIN_MB: Int = ServerRunFlagService.MIN_MEMORY_MB
    const val MAX_MB: Int = ServerRunFlagService.MAX_MEMORY_MB
    const val STEP_MB: Int = ServerRunFlagService.MEMORY_STEP_MB
    const val MIN_GB: Double = MIN_MB / MB_PER_GB
    const val MAX_GB: Double = MAX_MB / MB_PER_GB
    const val STEP_GB: Double = 0.125
  }
}

/**
 * The editor of the arguments of one kind.
 *
 * The table keeps an identity per row, because a constructor may hold two
 * equal arguments. The decorator toolbar adds, removes, and orders the rows.
 */
private class ArgumentsEditor(args: List<String>) {
  private class ArgRow(var text: String)

  private val column = object : ColumnInfo<ArgRow, String>("") {
    override fun valueOf(item: ArgRow): String = item.text

    override fun setValue(item: ArgRow, value: String) {
      item.text = value
    }

    override fun isCellEditable(item: ArgRow): Boolean = true
  }

  private val model = ListTableModel(arrayOf<ColumnInfo<*, *>>(column), args.map { ArgRow(it) })
  private val table = JBTable(model)

  init {
    table.tableHeader = null
    table.setShowGrid(false)
    table.setVisibleRowCount(VISIBLE_ROW_COUNT)
    table.emptyText.text = MSStudioBundle.serverRunFlagsEmptyText
  }

  val component: JComponent by lazy {
    ToolbarDecorator.createDecorator(table)
      .setAddAction { addRow() }
      .setRemoveAction { removeRows() }
      .setMoveUpAction { moveRows(-1) }
      .setMoveDownAction { moveRows(1) }
      .createPanel()
  }

  fun arguments(): List<String> {
    TableUtil.stopEditing(table)
    return model.items.map { it.text.trim() }.filter { it.isNotEmpty() }
  }

  fun load(args: List<String>) {
    TableUtil.stopEditing(table)
    model.setItems(args.map { ArgRow(it) })
  }

  private fun addRow() {
    TableUtil.stopEditing(table)
    val last = model.rowCount
    model.addRow(ArgRow(""))
    table.setRowSelectionInterval(last, last)
    table.editCellAt(last, 0)
  }

  private fun removeRows() {
    TableUtil.stopEditing(table)
    for (row in table.selectedRows.reversed()) {
      model.removeRow(row)
    }
  }

  private fun moveRows(delta: Int) {
    TableUtil.stopEditing(table)
    if (delta < 0) {
      for (row in table.selectedRows) {
        if (row > 0) {
          model.exchangeRows(row - 1, row)
          table.setRowSelectionInterval(row - 1, row - 1)
        }
      }
    }
    else {
      for (row in table.selectedRows.reversed()) {
        if (row < model.rowCount - 1) {
          model.exchangeRows(row, row + 1)
          table.setRowSelectionInterval(row + 1, row + 1)
        }
      }
    }
  }
  private companion object {
    const val VISIBLE_ROW_COUNT: Int = 8
  }
}

/**
 * The more button of the run widget. It opens the dialog of the start parameters.
 */
internal class ServerRunFlagsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val project = event.project
    if (project == null || project.isDisposed) {
      event.presentation.isEnabledAndVisible = false
      return
    }
    event.presentation.isEnabledAndVisible = true
    event.presentation.icon = AllIcons.Actions.More
    // The description comes from the original bundle of the IDE, like the text
    // of the more button of the original run widget.
    event.presentation.text = IdeBundle.message("inline.actions.more.actions.text")
    event.presentation.description = IdeBundle.message("inline.actions.more.actions.text")
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    ServerRunFlagsDialog(project).show()
  }
}

/**
 * The selector of the Java SDK for the server start.
 *
 * The combo is the SDK picker of the IDE, the same control as in Project
 * Structure: it lists the JDKs of the SDK table, detects the installed JDKs
 * of the machine, and offers the add and download flows. The None item means
 * the java command from PATH.
 */
private class JavaRuntimeSelector(
  private val project: Project,
  parentDisposable: Disposable,
) {
  private val sdksModel = ProjectSdksModel().apply {
    reset(project)
    Disposer.register(parentDisposable, Disposable { disposeUIResources() })
  }
  private val combo = SdkComboBox(SdkComboBoxModel.createJdkComboBoxModel(project, sdksModel))

  init {
    combo.showNoneSdkItem()
    resetToProjectSdk()
  }

  val component: JComponent = JPanel(BorderLayout()).apply {
    // The whole block sits in the north, so the combo keeps its preferred
    // height when the window grows.
    add(JPanel(BorderLayout()).apply {
      border = JBUI.Borders.emptyTop(4)
      add(JBLabel(MSStudioBundle.serverRunJavaTitleText).apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
      }, BorderLayout.NORTH)
      add(combo, BorderLayout.CENTER)
    }, BorderLayout.NORTH)
  }

  /**
   * Selects the current SDK of the project. The None item means that the
   * project and the server start use the java command from PATH.
   */
  fun resetToProjectSdk() {
    val sdk = sdksModel.projectSdk
    if (sdk == null) {
      combo.selectedItem = combo.showNoneSdkItem()
    }
    else {
      combo.setSelectedSdk(sdk)
    }
  }

  /**
   * Saves SDK changes and makes the selected SDK the SDK of the project.
   */
  @Throws(ConfigurationException::class)
  fun applyModel() {
    val selectedSdk = combo.getSelectedSdk()
    sdksModel.apply()
    ApplicationManager.getApplication().runWriteAction {
      ProjectRootManager.getInstance(project).projectSdk = selectedSdk
    }
  }
}
