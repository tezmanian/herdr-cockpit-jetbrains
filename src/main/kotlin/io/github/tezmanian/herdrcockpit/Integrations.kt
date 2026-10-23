// Settings | Tools | Herdr Cockpit | Agent integrations: which of Herdr's agent integrations (hooks that report an
// agent's session) are installed, with
// Install / Update and Uninstall. Read and changed over the socket of an open project's Herdr.
package io.github.tezmanian.herdrcockpit

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.Icon
import javax.swing.JComponent

fun integrationState(i: Integration): String = when (i.state) {
    "not_installed" -> ""
    "current" -> "installed"
    "outdated" -> "outdated: update it"
    else -> i.state
}

class IntegrationTable {
    private val model = ListTableModel<Integration>(
        column("Agent") { it.label },
        column("Integration") { integrationState(it) },
        column("Agent found") { if (it.available) "yes" else "" },
    )
    private val table = TableView(model).apply { setShowGrid(false) }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .disableAddAction().disableRemoveAction().disableUpDownActions()
        .addExtraAction(button("Install or Update", AllIcons.Actions.Download) { a, i -> a.installIntegration(i.target) })
        .addExtraAction(button("Uninstall", AllIcons.General.Remove) { a, i -> a.uninstallIntegration(i.target) })
        .addExtraAction(button("Refresh", AllIcons.Actions.Refresh, needsRow = false) { _, _ -> })
        .createPanel()

    init { load() }

    // The settings are application-wide; any open project's connection reaches the same Herdr.
    private fun actions() = ProjectManager.getInstance().openProjects.firstOrNull()?.let { HerdrService.getInstance(it).actions }

    /** Runs `change` off the EDT, then reloads the list; the dialog is modal, so results come back in its modality. */
    private fun load(change: ((HerdrActions) -> Unit)? = null) {
        val a = actions() ?: return run { table.emptyText.text = "Open a project to see Herdr's integrations." }
        table.emptyText.text = "Loading…"
        val modality = ModalityState.stateForComponent(table)
        ApplicationManager.getApplication().executeOnPooledThread {
            val error = change?.let { runCatching { it(a) }.exceptionOrNull() }
            val list = runCatching { a.integrations() }
            ApplicationManager.getApplication().invokeLater({
                model.items = list.getOrNull().orEmpty().toMutableList()
                table.emptyText.text = list.exceptionOrNull()?.let { "Couldn't read them from Herdr: ${it.message}" } ?: "No integrations"
                if (error != null) Messages.showErrorDialog(component, error.message ?: error.toString(), "Herdr")
            }, modality)
        }
    }

    private fun button(text: String, icon: Icon, needsRow: Boolean = true, run: (HerdrActions, Integration) -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !needsRow || table.selectedObject != null }
            override fun actionPerformed(e: AnActionEvent) {
                val i = table.selectedObject
                if (!needsRow || i == null) return load()
                load { run(it, i) }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun column(title: String, value: (Integration) -> String) = object : ColumnInfo<Integration, String>(title) {
        override fun valueOf(item: Integration) = value(item)
    }
}
