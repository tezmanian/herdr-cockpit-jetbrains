// Each Herdr pane opens as an editor tab holding a JediTerm terminal that streams the pane (see
// HerdrTtyConnector). Closing the tab only detaches: the program keeps running in Herdr.
// The Herdr TUI itself (`herdr` in a real PTY) opens the same way as HerdrTuiFile.
package io.github.tezmanian.herdrcockpit

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.FileIconProvider
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.intellij.testFramework.LightVirtualFile
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.TransferHandler

/** A pane's tab. Not a real file: the editor provider below draws it. */
class HerdrPaneFile(val paneId: String, val spaceId: String, var title: String, val agent: Boolean) :
    LightVirtualFile(title, PlainTextFileType.INSTANCE, "") {
    @Volatile var connector: HerdrTtyConnector? = null
    /** Opened by following Herdr: watch a pane another window holds instead of taking it. */
    var polite = false

    init {
        isWritable = false
    }

    /** The stream ended without the tab being closed: the tab only shows a note. */
    val ended: Boolean get() = connector?.isEnded == true

    override fun getPath(): String = "herdr://$paneId"
}

/** The Herdr TUI's tab: one per project. */
class HerdrTuiFile : LightVirtualFile("Herdr", PlainTextFileType.INSTANCE, "") {
    init {
        isWritable = false
    }

    override fun getPath(): String = "herdr://tui"
}

class HerdrPaneEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is HerdrPaneFile || file is HerdrTuiFile || file is HerdrHelpFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = when (file) {
        is HerdrTuiFile -> HerdrTuiEditor(project, file)
        is HerdrHelpFile -> HerdrHelpEditor(project, file)
        else -> HerdrPaneEditor(project, file as HerdrPaneFile)
    }
    override fun getEditorTypeId() = "herdr-pane"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private const val WHEEL_LINES = 3

/**
 * Typed for dropped files, with a trailing space to keep typing: absolute paths, shell-quoted only when needed, or
 * Claude Code's `@path` mentions (which attach the file's content) when `claude`.
 */
fun dropText(files: List<File>, claude: Boolean = false): String =
    files.joinToString(" ", postfix = " ") { f ->
        val path = f.absolutePath
        val plain = !Regex("[^\\w@%+=:,./-]").containsMatchIn(path)
        when {
            claude -> if (plain) "@$path" else "@\"$path\""
            plain -> path
            else -> "'" + path.replace("'", "'\\''") + "'"
        }
    }

/** Files dropped on the terminal (from the project view, the editor or Finder) are typed in as their paths. */
private fun acceptFileDrops(widget: JBTerminalWidget, claude: () -> Boolean = { false }) {
    widget.terminalPanel.transferHandler = object : TransferHandler() {
        override fun canImport(support: TransferSupport) = FileCopyPasteUtil.isFileListFlavorAvailable(support.dataFlavors)
        override fun importData(support: TransferSupport): Boolean {
            val files = FileCopyPasteUtil.getFileList(support.transferable)?.takeIf { it.isNotEmpty() } ?: return false
            val tty = widget.ttyConnector ?: return false
            tty.write(dropText(files, claude()))
            widget.terminalPanel.requestFocusInWindow()
            return true
        }
    }
}

/** A terminal whose panel offers each key press to [tabKey] first; true means the tab handled it and the pane never sees it. */
class HerdrTerminalWidget(project: Project, parent: com.intellij.openapi.Disposable, private val tabKey: (KeyEvent) -> Boolean) :
    JBTerminalWidget(project, JBTerminalSystemSettingsProviderBase(), parent) {
    override fun createTerminalPanel(settings: SettingsProvider, styleState: StyleState, textBuffer: TerminalTextBuffer): JBTerminalPanel =
        object : JBTerminalPanel(settings as JBTerminalSystemSettingsProviderBase, textBuffer, styleState) {
            override fun handleKeyEvent(e: KeyEvent) {
                if (e.id == KeyEvent.KEY_PRESSED && tabKey(e)) e.consume() else super.handleKeyEvent(e)
            }
        }
}

/** Herdr's scrollback keys: Shift+PageUp/PageDown a page, Ctrl/Cmd+Home/End to the top or bottom. Null: not ours. */
fun scrollKey(code: Int, modifiers: Int, rows: Int): Pair<Boolean, Int>? {
    val shift = modifiers and InputEvent.SHIFT_DOWN_MASK != 0
    val jump = modifiers and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0 && !shift
    val all = 65535
    return when {
        shift && code == KeyEvent.VK_PAGE_UP -> true to maxOf(1, rows - 1)
        shift && code == KeyEvent.VK_PAGE_DOWN -> false to maxOf(1, rows - 1)
        jump && code == KeyEvent.VK_HOME -> true to all
        jump && code == KeyEvent.VK_END -> false to all
        else -> null
    }
}

class HerdrPaneEditor(private val project: Project, private val file: HerdrPaneFile) : UserDataHolderBase(), FileEditor {
    private val widget: HerdrTerminalWidget = HerdrTerminalWidget(project, this) { e ->
        val (up, lines) = scrollKey(e.keyCode, e.modifiersEx, widget.terminalTextBuffer.height) ?: return@HerdrTerminalWidget false
        file.connector?.scroll(up, lines)
        true
    }

    init {
        val service = HerdrService.getInstance(project)
        val connector = HerdrTtyConnector(service.binary(), file.paneId, service.herdrEnvNow(), file.title, service.paneHooks(file.paneId), file.polite) { why ->
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
        // `src/foo.ts:42` printed in the pane: a link to the file at that line, resolved against the pane's folder.
        widget.addHyperlinkFilter(HyperlinkFilter { line ->
            val hit = service.currentModel()?.find(file.paneId)
            val bases = listOfNotNull(hit?.second?.cwd, hit?.first?.cwd).distinct()
            val items = findFileRefs(line).take(50).mapNotNull { ref ->
                val target = resolveRef(ref, bases, System.getProperty("user.home")) ?: return@mapNotNull null
                LinkResultItem(ref.start, ref.start + ref.length, LinkInfo {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target) ?: return@LinkInfo
                    OpenFileDescriptor(project, vf, (ref.line ?: 1) - 1, (ref.col ?: 1) - 1).navigate(true)
                })
            }
            if (items.isEmpty()) null else LinkResult(items)
        })
        widget.start(connector)
        acceptFileDrops(widget) { service.currentModel()?.find(file.paneId)?.second?.agentKind == "claude" }
    }

    /** The pane's whole history from Herdr as a read-only document, caret at the end. */
    fun openOutput() {
        val service = HerdrService.getInstance(project)
        val cols = widget.terminalTextBuffer.width
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching { service.actions.paneHistory(file.paneId, cols) }.getOrElse {
                return@executeOnPooledThread service.notify("Couldn't read this pane's output from Herdr: ${it.message}", NotificationType.WARNING)
            }
            ApplicationManager.getApplication().invokeLater({
                val doc = LightVirtualFile("${file.title}.log", PlainTextFileType.INSTANCE, text.trimEnd() + "\n").apply { isWritable = false }
                OpenFileDescriptor(project, doc, doc.length.toInt()).navigate(true)
            }, project.disposed)
        }
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

/** `herdr` (the TUI client) in a PTY, against the same session as the plugin. Closing the tab ends only the client. */
class HerdrTuiEditor(project: Project, private val file: HerdrTuiFile) : UserDataHolderBase(), FileEditor {
    private val widget = JBTerminalWidget(project, JBTerminalSystemSettingsProviderBase(), this)
    private val proc = HerdrService.getInstance(project).let { s ->
        PtyProcessBuilder(arrayOf(s.binary()))
            // An IDE started from a Herdr pane inherits that pane's variables; with them herdr refuses to run "nested".
            .setEnvironment(s.herdrEnvNow() - setOf("HERDR_ENV", "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID") + ("TERM" to "xterm-256color"))
            .setDirectory(project.basePath ?: System.getProperty("user.home"))
            .setInitialColumns(120).setInitialRows(32)
            .start()
    }

    init {
        widget.start(object : ProcessTtyConnector(proc, Charsets.UTF_8) {
            override fun getName() = "Herdr"
            override fun resize(termSize: TermSize) { if (proc.isAlive) proc.winSize = WinSize(termSize.columns, termSize.rows) }
        })
        acceptFileDrops(widget)
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
    override fun dispose() { proc.destroy() }
}

class HerdrPaneTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? = (file as? HerdrPaneFile)?.title
}

class HerdrPaneIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, @Iconable.IconFlags flags: Int, project: Project?): Icon? =
        when (file) {
            is HerdrPaneFile -> if (file.agent) AllIcons.Nodes.Plugin else AllIcons.Nodes.Console
            is HerdrTuiFile -> AllIcons.Nodes.Console
            is HerdrHelpFile -> AllIcons.Actions.Help
            else -> null
        }
}
