// The small UI helpers HerdrManage and ReviewFlow share: popups, input and prompt dialogs, background tasks, notifications.
package io.github.tezmanian.herdrcockpit

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.MouseInfo
import java.io.File
import javax.swing.Icon
import javax.swing.JList

/** A "Herdr Cockpit" balloon in `project`, from any thread, with `actions` (label, what it does; each closes it). */
fun herdrNotify(project: Project, html: String, type: NotificationType, vararg actions: Pair<String, () -> Unit>) = ApplicationManager.getApplication().invokeLater {
    if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit").createNotification(html, type)
        .apply { actions.forEach { (label, run) -> addAction(NotificationAction.createSimpleExpiring(label) { run() }) } }
        .notify(project)
}

/**
 * Opens `file` at `line` and `col` (0-based), from any thread. The file is looked up (and refreshed into the VFS) on a
 * pooled thread: a synchronous refresh on the EDT needs the write-intent lock, which a click in a terminal doesn't
 * hold. `missing` runs (pooled thread) when there's no such file.
 */
fun openFileAt(project: Project, file: File, line: Int, col: Int = 0, missing: () -> Unit = {}) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@executeOnPooledThread missing()
        ApplicationManager.getApplication().invokeLater({ OpenFileDescriptor(project, vf, line, col).navigate(true) }, project.disposed)
    }
}

abstract class ProjectUi(protected val project: Project, private val log: (String) -> Unit) {
    protected class Item(val text: String, val icon: Icon?, val separator: String? = null, val run: () -> Unit)

    protected fun notify(html: String, type: NotificationType, vararg actions: Pair<String, () -> Unit>) = herdrNotify(project, html, type, *actions)

    protected fun choose(title: String?, items: List<Item>, atMouse: Boolean = false) {
        val step = object : BaseListPopupStep<Item>(title, items) {
            override fun getTextFor(value: Item) = value.text
            override fun getIconFor(value: Item) = value.icon
            override fun getSeparatorAbove(value: Item) = value.separator?.let { ListSeparator(it) }
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? = doFinalStep { selectedValue.run() }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val mouse = if (atMouse) MouseInfo.getPointerInfo()?.location else null
        if (mouse != null) popup.show(RelativePoint.fromScreen(mouse)) else popup.showCenteredInCurrentWindow(project)
    }

    /** Like [choose], with a search field above the list that filters it by the items' text: for long lists. */
    protected fun chooseFiltered(title: String, items: List<Item>) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(title)
            .setRenderer(object : ColoredListCellRenderer<Item>() {
                override fun customizeCellRenderer(list: JList<out Item>, value: Item?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    icon = value?.icon
                    append(value?.text ?: "")
                }
            })
            .setNamerForFiltering { it.text }
            .setItemChosenCallback { it.run() }
            .createPopup().showCenteredInCurrentWindow(project)
    }

    protected fun input(title: String, message: String, initial: String = "", error: (String) -> String? = { if (it.isBlank()) "Enter a name" else null }): String? =
        Messages.showInputDialog(project, message, title, null, initial, object : InputValidatorEx {
            override fun getErrorText(inputString: String) = error(inputString)
        })?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A prompt dialog with the library prompts of `category` (yours, then the trusted project's) as presets: picking one fills the text
     * after `initial`. With `required`, OK needs text beyond `initial` (the message says what). Null when cancelled.
     */
    protected fun promptDialog(title: String, message: String, category: String, initial: String, required: String?): String? =
        promptDialog(title, message, category, initial, required, emptyList(), "")?.first

    /**
     * The prompt dialog, plus a choice among `templates` (label, text) when there are several, preselecting the one last
     * picked under `templateKey`; (text, chosen template's text, "" without templates). Null when cancelled.
     */
    protected fun promptDialog(title: String, message: String, category: String, initial: String, required: String?,
                               templates: List<Pair<String, String>>, templateKey: String): Pair<String, String>? {
        val presets = promptPresets(project, category)
        val area = JBTextArea(initial, 10, 70).apply { lineWrap = true; wrapStyleWord = true }
        val key = "herdr-cockpit.template.$templateKey"
        val last = PropertiesComponent.getInstance().getValue(key)
        var chosen = templates.indexOfFirst { it.first == last }.coerceAtLeast(0)
        val content = panel {
            if (templates.size > 1) row("Template:") {
                comboBox(templates.map { it.first }).applyToComponent {
                    selectedIndex = chosen
                    addActionListener { chosen = selectedIndex }
                }.comment("The whole prompt the review sends (prompt library, categories ending in \"template\").")
            }
            row { label(message) }
            if (presets.isNotEmpty()) row("Preset:") {
                comboBox(listOf("") + presets.map { it.first }).applyToComponent {
                    addActionListener { area.text = initial + (presets.getOrNull(selectedIndex - 1)?.second ?: "") }
                }
            }
            row { scrollCell(area).align(Align.FILL) }.resizableRow()
        }
        val dialog = object : DialogWrapper(project) {
            init { this.title = title; init(); initValidation() }
            override fun createCenterPanel() = content
            override fun getPreferredFocusedComponent() = area
            // A DSL panel turns continuous validation off: OK would stay disabled after the first check.
            override fun continuousValidation() = true
            override fun doValidate() =
                if (required != null && (area.text.isBlank() || area.text.trim() == initial.trim())) ValidationInfo(required, area) else null
        }
        if (!dialog.showAndGet()) return null
        val template = templates.getOrNull(chosen)
        if (templates.size > 1) PropertiesComponent.getInstance().setValue(key, template?.first)
        return area.text.trim() to (template?.second ?: "")
    }

    protected fun later(f: () -> Unit) = ApplicationManager.getApplication().invokeLater(f, project.disposed)

    /** `body` as a background task; a failure is logged and shown. */
    protected fun run(title: String, body: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, "Herdr: $title", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    body(indicator)
                } catch (e: Exception) {
                    log("$title: ${e.message}")
                    notify("$title failed: ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }
}
