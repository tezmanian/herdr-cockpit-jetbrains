// Each Herdr pane opens as an editor tab holding a JediTerm terminal that streams the pane (see
// HerdrTtyConnector). Closing the tab only detaches: the program keeps running in Herdr.
package io.github.tezmanian.herdrcockpit

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.intellij.testFramework.LightVirtualFile
import java.awt.event.MouseWheelEvent
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/** A pane's tab. Not a real file: the editor provider below draws it. */
class HerdrPaneFile(val paneId: String, val spaceId: String, var title: String, val agent: Boolean) :
    LightVirtualFile(title, PlainTextFileType.INSTANCE, "") {
    @Volatile var connector: HerdrTtyConnector? = null

    init {
        isWritable = false
    }

    /** The stream ended without the tab being closed: the tab only shows a note. */
    val ended: Boolean get() = connector?.isEnded == true

    override fun getPath(): String = "herdr://$paneId"
}

class HerdrPaneEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is HerdrPaneFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = HerdrPaneEditor(project, file as HerdrPaneFile)
    override fun getEditorTypeId() = "herdr-pane"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private const val WHEEL_LINES = 3

class HerdrPaneEditor(private val project: Project, private val file: HerdrPaneFile) : UserDataHolderBase(), FileEditor {
    private val widget = JBTerminalWidget(project, JBTerminalSystemSettingsProviderBase(), this)

    init {
        val service = HerdrService.getInstance(project)
        val connector = HerdrTtyConnector(service.binary(), file.paneId, service.herdrEnvNow(), file.title) { why ->
            service.log("pane ${file.paneId}: $why")
            service.scheduleRender()
        }
        file.connector = connector
        // Herdr draws its own viewport, so the wheel scrolls Herdr's scrollback instead of JediTerm's buffer.
        val panel = widget.terminalPanel
        panel.mouseWheelListeners.forEach(panel::removeMouseWheelListener)
        panel.addMouseWheelListener { e: MouseWheelEvent ->
            val n = e.wheelRotation
            if (n != 0) connector.scroll(n < 0, WHEEL_LINES * kotlin.math.abs(n))
            e.consume()
        }
        widget.start(connector)
    }

    override fun getComponent(): JComponent = widget
    override fun getPreferredFocusedComponent(): JComponent = widget.preferredFocusableComponent
    override fun getName() = "Herdr"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        file.connector?.close()
        file.connector = null
        HerdrService.getInstance(project).scheduleRender()
    }
}

class HerdrPaneTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? = (file as? HerdrPaneFile)?.title
}

class HerdrPaneIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, @Iconable.IconFlags flags: Int, project: Project?): Icon? =
        (file as? HerdrPaneFile)?.let { if (it.agent) AllIcons.Nodes.Plugin else AllIcons.Nodes.Console }
}
