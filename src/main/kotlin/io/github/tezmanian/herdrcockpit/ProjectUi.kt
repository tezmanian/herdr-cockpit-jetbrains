// The small UI helpers HerdrManage and ReviewFlow share: popups, input and prompt dialogs, background tasks, notifications.
package io.github.tezmanian.herdrcockpit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.MouseInfo
import javax.swing.Icon

/** A "Herdr Cockpit" balloon in `project`, from any thread. */
fun herdrNotify(project: Project, html: String, type: NotificationType) = ApplicationManager.getApplication().invokeLater {
    if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit").createNotification(html, type).notify(project)
}

abstract class ProjectUi(protected val project: Project, private val log: (String) -> Unit) {
    protected class Item(val text: String, val icon: Icon?, val separator: String? = null, val run: () -> Unit)

    protected fun notify(html: String, type: NotificationType) = herdrNotify(project, html, type)

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

    protected fun input(title: String, message: String, initial: String = "", error: (String) -> String? = { if (it.isBlank()) "Enter a name" else null }): String? =
        Messages.showInputDialog(project, message, title, null, initial, object : InputValidatorEx {
            override fun getErrorText(inputString: String) = error(inputString)
        })?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A prompt dialog with the library prompts of `category` (yours, then the trusted project's) as presets: picking one fills the text
     * after `initial`. With `required`, OK needs text beyond `initial` (the message says what). Null when cancelled.
     */
    protected fun promptDialog(title: String, message: String, category: String, initial: String, required: String?): String? {
        val presets = promptPresets(project, category)
        val area = JBTextArea(initial, 10, 70).apply { lineWrap = true; wrapStyleWord = true }
        val content = panel {
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
        return if (dialog.showAndGet()) area.text.trim() else null
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
