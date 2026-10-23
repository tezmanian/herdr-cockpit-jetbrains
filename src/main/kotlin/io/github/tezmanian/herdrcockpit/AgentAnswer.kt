// Answering an agent that waits at a question or permission dialog ("blocked"): its screen and the keys such dialogs
// take, sent with agent.send_keys (refused once that agent no longer holds the pane). No raw pane input.
package io.github.tezmanian.herdrcockpit

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import javax.swing.Action

/** The keys offered, as (button, Herdr key name): moving in a list, picking a numbered option, confirming, cancelling. */
private val ANSWER_KEYS = listOf("↑" to "up", "↓" to "down", "1" to "1", "2" to "2", "3" to "3", "Enter" to "enter", "Esc" to "esc")

/** How long the agent gets to redraw after a key before its screen is read again. */
private const val REDRAW_MS = 400L

/** Reads the agent's screen in the background, then shows it with the keys; each key is sent and the screen read again. */
fun answerAgent(project: Project, actions: HerdrActions, name: String, paneId: String) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val screen = try {
            actions.readAgent(paneId)
        } catch (e: Exception) {
            return@executeOnPooledThread herdrNotify(project, "Can't read $name's screen: ${e.message}", NotificationType.WARNING)
        }
        ApplicationManager.getApplication().invokeLater({ AnswerDialog(project, actions, name, paneId, screen).show() }, project.disposed)
    }
}

private class AnswerDialog(project: Project, private val actions: HerdrActions, private val name: String,
                           private val paneId: String, screen: String) : DialogWrapper(project, false) {
    private val area = JBTextArea(screen.trimEnd(), 18, 90).apply { isEditable = false; font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)) }
    private val status = JBLabel(" ")

    init {
        title = "Answer $name"
        isModal = false
        setCancelButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel() = panel {
        row { label("$name is waiting for an answer:") }
        row { scrollCell(area).align(Align.FILL) }.resizableRow()
        row { ANSWER_KEYS.forEach { (text, key) -> button(text) { send(text, key) } } }
        row { cell(status) }
    }

    private fun send(text: String, key: String) {
        status.text = "Sending $text…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val (msg, screen) = try {
                actions.sendKeys(paneId, listOf(key))
                Thread.sleep(REDRAW_MS)
                "Sent $text." to runCatching { actions.readAgent(paneId) }.getOrNull()
            } catch (e: Exception) {
                "Not sent: ${e.message}" to null
            }
            ApplicationManager.getApplication().invokeLater({
                status.text = msg
                screen?.let { area.text = it.trimEnd(); area.caretPosition = area.document.length }
            }, ModalityState.any())
        }
    }
}
