// "Needs you" outside the sidebar: a status bar widget ("⬢ 1 blocked · 2 done") and a badge on the tool window icon.
package io.github.tezmanian.herdrcockpit

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon

private const val WIDGET_ID = "HerdrCockpit.Status"

/** "⬢ 1 blocked · 2 done", "⬢ Herdr" when nothing needs you, "⬢ Herdr: disconnected" when down. */
fun statusText(connected: Boolean, blocked: Int, done: Int): String {
    if (!connected) return "⬢ Herdr: disconnected"
    val parts = listOfNotNull(blocked.takeIf { it > 0 }?.let { "$it blocked" }, done.takeIf { it > 0 }?.let { "$it done" })
    return "⬢ " + parts.joinToString(" · ").ifEmpty { "Herdr" }
}

class HerdrStatusBarFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "Herdr Cockpit"
    override fun createWidget(project: Project): StatusBarWidget = HerdrStatusWidget(project)
}

private class HerdrStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val service = HerdrService.getInstance(project)

    override fun ID() = WIDGET_ID
    override fun getPresentation() = this
    override fun install(statusBar: StatusBar) {
        service.addStateListener(this) { ApplicationManager.getApplication().invokeLater { statusBar.updateWidget(WIDGET_ID) } }
    }

    override fun dispose() {}
    override fun getAlignment() = 0f

    override fun getText(): String = service.attention().let { (b, d) -> statusText(service.isConnected(), b, d) }

    override fun getTooltipText(): String =
        if (service.isConnected()) "Herdr ${service.currentModel()?.version ?: ""} at ${service.socketPath()}"
        else "Not connected to Herdr (${service.socketPath()})"

    /** Something needs you: jump to it. Otherwise the connection menu. */
    override fun getClickConsumer() = Consumer<MouseEvent> { e ->
        val (b, d) = service.attention()
        if (service.isConnected() && b + d > 0) return@Consumer service.handle("attention", null, null)
        fun act(text: String, command: String) = DumbAwareAction.create(text) { service.handle(command, null, null) }
        val group: ActionGroup = DefaultActionGroup(
            RefreshAction(), act("Start Herdr", "startServer"), OpenTuiAction(), OpenSettingsAction(), act("Copy Diagnostics", "copyDiagnostics"),
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Herdr", group, SimpleDataContext.getProjectContext(project), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .show(RelativePoint(e.component, e.point))
    }
}

/** A red dot on the tool window icon while an agent is blocked. */
fun badgeToolWindow(project: Project, toolWindow: ToolWindow) {
    val plain = IconLoader.getIcon("/icons/herdr.svg", HerdrStatusBarFactory::class.java)
    val badged = DotIcon(plain)
    var shown: Boolean? = null
    val service = HerdrService.getInstance(project)
    service.addStateListener(toolWindow.disposable) {
        ApplicationManager.getApplication().invokeLater {
            val blocked = service.isConnected() && service.attention().first > 0
            if (blocked != shown && !toolWindow.isDisposed) {
                shown = blocked
                toolWindow.setIcon(if (blocked) badged else plain)
            }
        }
    }
}

/** The icon with a red dot in its top right corner (the platform's BadgeIcon is internal API). */
private class DotIcon(private val base: Icon) : Icon by base {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        val d = JBUI.scale(6)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor.RED
        g2.fillOval(x + base.iconWidth - d, y, d, d)
        g2.dispose()
    }
}
