// Each Herdr pane opens as an editor tab holding a JediTerm terminal that streams the pane (see
// HerdrTtyConnector). Closing the tab only detaches: the program keeps running in Herdr.
// The Herdr TUI itself (`herdr` in a real PTY) opens the same way as HerdrTuiFile.
package io.github.tezmanian.herdrcockpit

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.FileIconProvider
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
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
import com.intellij.ui.JBColor
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalCopyPasteHandler
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalSelectionChangesListener
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.TransferHandler

/** A pane's tab. Not a real file: the editor provider below draws it. */
class HerdrPaneFile(val paneId: String, val spaceId: String, var title: String, val agent: Boolean) :
    LightVirtualFile(title, PlainTextFileType.INSTANCE, "") {
    @Volatile var connector: HerdrTtyConnector? = null
    /** Opened by following Herdr: watch a pane another window holds instead of taking it. */
    var polite = false

    init {
        isWritable = false
        // A second editor (split) would take the pane from the first one.
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
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
        HerdrTerminalPanel(settings as JBTerminalSystemSettingsProviderBase, textBuffer, styleState) { tabKey(it) }

    val panel: HerdrTerminalPanel get() = terminalPanel as HerdrTerminalPanel
}

/** Selecting past the top or bottom row scrolls Herdr's view this often, and waits this long for it to move. */
private const val AUTOSCROLL_EVERY_MS = 80
private const val SCROLL_WAIT_MS = 400
// shortcut: JediTerm has no public way to clear the selection; reflection, a no-op if a later JediTerm renames it
private val clearSelectionMethod = runCatching {
    com.jediterm.terminal.ui.TerminalPanel::class.java.getDeclaredMethod("updateSelection", TerminalSelection::class.java).apply { isAccessible = true }
}.getOrNull()

/**
 * The pane tab's terminal. Beyond JediTerm: Backspace / Delete deletes a selection on the line being typed and typing
 * replaces it, Alt+click moves the cursor there, Shift+click extends the selection, a selection dragged past the top
 * or bottom row scrolls Herdr's view (and copies the rows that scrolled away), and find matches are highlighted.
 */
class HerdrTerminalPanel(
    settings: JBTerminalSystemSettingsProviderBase,
    private val buffer: TerminalTextBuffer,
    styleState: StyleState,
    private val tabKey: (KeyEvent) -> Boolean,
) : JBTerminalPanel(settings, buffer, styleState) {
    var connector: () -> HerdrTtyConnector? = { null }
    /** The program's cursor, 0-based (column, row). */
    var cursor: () -> Pair<Int, Int>? = { null }
    var marks: () -> List<Mark> = { emptyList() }

    private var pressed = false
    private var swallowTyped = false
    private var extending = false
    /** Lines Herdr's view moved since the selection started (negative: down), and the selected rows by content row. */
    private var offset = 0
    private val kept = HashMap<Int, Pair<String, Boolean>>()
    private var auto: Timer? = null
    private var autoUp = false
    private var autoSince = 0L
    private var pending: Pending? = null
    private class Pending(val up: Boolean, val lines: Int, val before: List<String>, val at: Long)

    init {
        buffer.addModelListener { ApplicationManager.getApplication().invokeLater { settle(); if (offset != 0) keep() } }
        addSelectionListener(object : TerminalSelectionChangesListener {
            override fun selectionChanged(selection: TerminalSelection?) {
                if (selection == null) {
                    offset = 0
                    kept.clear()
                }
            }
        })
    }

    private val rows get() = buffer.height
    private val cols get() = buffer.width

    private fun rowText(y: Int): Pair<String, Boolean> {
        buffer.lock()
        try {
            val line = buffer.getLine(y)
            return line.text to line.isWrapped
        } finally {
            buffer.unlock()
        }
    }

    fun screenRow(y: Int) = rowText(y).first.replace(DWC.toString(), "")

    private fun cellAt(p: java.awt.Point) = com.jediterm.core.compatibility.Point(
        ((p.x - insetX) / maxOf(1, myCharSize.width)).coerceIn(0, cols - 1),
        Math.floorDiv(p.y, maxOf(1, myCharSize.height)).coerceIn(0, rows - 1) + myClientScrollOrigin,
    )

    private fun clearSelection() {
        runCatching { clearSelectionMethod?.invoke(this, null) }
        repaint()
    }

    // ----- editing the line being typed -----

    /** The keys that delete the selection, when it's on the cursor's row of the live screen. */
    private fun deleteKeys(): String? {
        val sel = selection ?: return null
        if (pressed || pending != null || connector()?.live != true) return null
        val (start, end) = sel.pointsForRun(cols)
        val (cx, cy) = cursor() ?: return null
        if (start.y != end.y || start.y != cy) return null
        return deleteKeys(rowText(cy).first, cx, start.x, end.x - 1)
    }

    override fun handleKeyEvent(e: KeyEvent) {
        if (e.id == KeyEvent.KEY_PRESSED) swallowTyped = false
        if (e.id == KeyEvent.KEY_PRESSED && tabKey(e)) return e.consume()
        // The typed event that follows a Backspace / Delete that deleted the selection.
        if (e.id == KeyEvent.KEY_TYPED && swallowTyped) {
            swallowTyped = false
            if (e.keyChar == '\b' || e.keyChar == '\u007f') return e.consume()
        }
        val plain = e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK) == 0
        val delete = e.id == KeyEvent.KEY_PRESSED && plain && (e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE)
        val typed = e.id == KeyEvent.KEY_TYPED && plain && e.keyChar >= ' ' && e.keyChar != '\u007f' && e.keyChar != KeyEvent.CHAR_UNDEFINED
        if ((delete || typed) && selection != null) {
            val keys = deleteKeys()
            if (keys != null) {
                connector()?.write(if (typed) keys + e.keyChar else keys)
                clearSelection()
                swallowTyped = delete
                return e.consume()
            }
        }
        super.handleKeyEvent(e)
    }

    // ----- mouse: Alt+click, Shift+click, dragging past the edge -----

    override fun processMouseEvent(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON1 && e.id == MouseEvent.MOUSE_PRESSED) {
            val alt = e.modifiersEx and InputEvent.ALT_DOWN_MASK != 0
            val shift = e.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0
            val c = cellAt(e.point)
            val sel = selection
            if (alt && !shift && e.clickCount == 1) {
                // Alt+click on the line being typed moves the cursor there.
                val (cx, cy) = cursor() ?: (0 to -1)
                val keys = if (c.y == cy && connector()?.live == true) moveKeys(rowText(cy).first, cx, c.x) else null
                if (keys != null) {
                    requestFocusInWindow()
                    connector()?.write(keys)
                    return e.consume()
                }
            }
            if (shift && sel != null) {
                sel.updateEnd(c)
                pressed = true
                extending = true
                requestFocusInWindow()
                repaint()
                return e.consume()
            }
            pressed = true
        }
        if (e.id == MouseEvent.MOUSE_RELEASED) {
            pressed = false
            extending = false
            stopAutoScroll()
        }
        super.processMouseEvent(e)
    }

    override fun processMouseMotionEvent(e: MouseEvent) {
        if (e.id != MouseEvent.MOUSE_DRAGGED || !pressed) return super.processMouseMotionEvent(e)
        if (extending) {
            selection?.updateEnd(cellAt(e.point))
            repaint()
            e.consume()
        } else {
            // Past the top or bottom JediTerm would scroll its own history: keep the pointer on screen for it,
            // Herdr's view scrolls instead (autoScroll).
            val y = e.y.coerceIn(0, maxOf(0, rows * myCharSize.height - 1))
            super.processMouseMotionEvent(if (y == e.y) e else MouseEvent(e.component, e.id, e.`when`, e.modifiersEx, e.x, y, e.clickCount, e.isPopupTrigger, e.button))
        }
        if (selection == null) return
        keep()
        autoScroll(edge(e))
    }

    /** Where a drag should scroll: the pointer on (or past) the top or bottom row, the anchor elsewhere. */
    private fun edge(e: MouseEvent): Boolean? {
        val anchor = selection?.start?.y ?: return null
        val h = maxOf(1, myCharSize.height)
        return when {
            e.y < h && anchor != 0 -> true
            e.y >= (rows - 1) * h && anchor != rows - 1 -> false
            else -> null
        }
    }

    private fun autoScroll(up: Boolean?) {
        if (up == null) return stopAutoScroll()
        if (auto != null && autoUp == up) return
        stopAutoScroll()
        autoUp = up
        autoSince = System.currentTimeMillis()
        auto = Timer(AUTOSCROLL_EVERY_MS) { tick(up) }.apply { start() }
    }

    private fun stopAutoScroll() {
        auto?.stop()
        auto = null
    }

    /** One step: faster the longer the drag is held there. The next waits for this one's frame (settle()). */
    private fun tick(up: Boolean) {
        val c = connector() ?: return stopAutoScroll()
        if (selection == null) return stopAutoScroll()
        val p = pending
        if (p != null) {
            if (System.currentTimeMillis() - p.at < SCROLL_WAIT_MS) return
            pending = null // no frame came: the end of the scrollback; keep trying while the drag is held there
        }
        val held = System.currentTimeMillis() - autoSince
        val lines = (if (held < 1500) 1 else if (held < 3000) 3 else 10).coerceIn(1, maxOf(1, rows - 1))
        keep()
        pending = Pending(up, lines, (0 until rows).map { rowText(it).first }, System.currentTimeMillis())
        c.scroll(up, lines)
    }

    /** A frame came: if it's the answer to a scroll, move the selection with the text and keep the head at the edge. */
    private fun settle() {
        val p = pending ?: return
        val sel = selection ?: run { pending = null; return }
        val k = scrollShift(p.before, (0 until rows).map { rowText(it).first }, p.up, p.lines)
        if (k == 0) return // another frame (a spinner): keep waiting
        pending = null
        val d = if (p.up) k else -k
        offset += d
        // The head stays under the pointer (on the edge row); getEnd() is the live point, so copy it first.
        val head = com.jediterm.core.compatibility.Point(sel.end.x, sel.end.y)
        sel.shiftY(d)
        sel.updateEnd(head)
        keep()
        repaint()
    }

    /**
     * Remember the selected rows on screen, for when they scroll out of view. Once the view has scrolled, a row keeps
     * the text it had when first seen: a full-screen program's fixed footer would otherwise overwrite rows under it.
     */
    private fun keep() {
        val sel = selection ?: return
        val (start, end) = sel.pointsForRun(cols)
        for (y in maxOf(0, start.y)..minOf(rows - 1, end.y)) {
            val r = y - offset
            if (offset == 0 || r !in kept) kept[r] = rowText(y)
        }
    }

    /** The selected text, from the kept rows once the view has scrolled (JediTerm only has the screen). */
    private fun selectedText(text: String): String {
        val sel = selection ?: return text
        if (offset == 0) return text
        keep()
        val (start, end) = sel.pointsForRun(cols)
        return keptText(kept, start.y - offset, start.x, end.y - offset, end.x)
    }

    override fun createCopyPasteHandler(): TerminalCopyPasteHandler {
        val base = super.createCopyPasteHandler()
        return object : TerminalCopyPasteHandler by base {
            override fun setContents(text: String, useSystemSelectionClipboardIfAvailable: Boolean) =
                base.setContents(selectedText(text), useSystemSelectionClipboardIfAvailable)
        }
    }

    // ----- find highlights -----

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val ms = marks()
        if (ms.isEmpty()) return
        val w = myCharSize.width
        val h = myCharSize.height
        val g2 = g.create()
        try {
            for (m in ms) {
                g2.color = if (m.current) CURRENT_MATCH else MATCH
                g2.fillRect(insetX + m.from * w, (m.y - myClientScrollOrigin) * h, (m.to - m.from) * w, h)
            }
        } finally {
            g2.dispose()
        }
    }
}

private val MATCH = JBColor(Color(255, 210, 0, 90), Color(255, 210, 0, 70))
private val CURRENT_MATCH = JBColor(Color(255, 120, 0, 150), Color(255, 140, 0, 130))

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

/** Ctrl+F (Cmd+F on a Mac): find in the pane's whole history instead of JediTerm's search, which sees only Herdr's viewport. */
fun isFindKey(code: Int, modifiers: Int): Boolean =
    code == KeyEvent.VK_F && modifiers and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0 &&
        modifiers and (InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK) == 0

class HerdrPaneEditor(private val project: Project, private val file: HerdrPaneFile) : UserDataHolderBase(), FileEditor {
    private val widget: HerdrTerminalWidget = HerdrTerminalWidget(project, this) { e ->
        if (isFindKey(e.keyCode, e.modifiersEx)) return@HerdrTerminalWidget true.also { findBar.open() }
        if (e.keyCode == KeyEvent.VK_F3 && findBar.isOpen) {
            findBar.step(if (e.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0) 1 else -1)
            return@HerdrTerminalWidget true
        }
        val (up, lines) = scrollKey(e.keyCode, e.modifiersEx, widget.terminalTextBuffer.height) ?: return@HerdrTerminalWidget false
        file.connector?.scroll(up, lines)
        findBar.refreshView()
        true
    }
    private val findBar = PaneFindBar(project, file.paneId, object : FindHost {
        override val cols get() = widget.terminalTextBuffer.width
        override val rows get() = widget.terminalTextBuffer.height
        override fun screenRow(y: Int) = widget.panel.screenRow(y)
        override fun viewMoved(live: Boolean) { file.connector?.live = live }
        override fun repaintMarks() = widget.panel.repaint()
        override fun closed() { widget.terminalPanel.requestFocusInWindow() }
        override fun openOutput() = this@HerdrPaneEditor.openOutput()
    })
    private val connector: HerdrTtyConnector
    private val component = JPanel(BorderLayout()).apply {
        add(findBar, BorderLayout.NORTH)
        add(widget, BorderLayout.CENTER)
    }

    init {
        val service = HerdrService.getInstance(project)
        connector = HerdrTtyConnector(service.binary(), file.paneId, service.herdrEnvNow(), file.title, service.paneHooks(file.paneId), file.polite) { why ->
            service.log("pane ${file.paneId}: $why")
            service.scheduleRender()
        }
        file.connector = connector
        widget.panel.connector = { file.connector }
        widget.panel.cursor = { widget.terminal.let { it.cursorX - 1 to it.cursorY - 1 } }
        widget.panel.marks = findBar::marks
        // Herdr draws its own viewport, so the wheel scrolls Herdr's scrollback instead of JediTerm's buffer.
        val panel = widget.terminalPanel
        panel.mouseWheelListeners.forEach(panel::removeMouseWheelListener)
        panel.addMouseWheelListener { e: MouseWheelEvent ->
            val n = e.wheelRotation
            if (n != 0) connector.scroll(n < 0, WHEEL_LINES * kotlin.math.abs(n))
            findBar.refreshView()
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

    /** The pane's whole history from Herdr as a read-only document, caret at the end, with the editor's find bar. */
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
                FileEditorManager.getInstance(project).selectedTextEditor?.let { ed ->
                    val am = ActionManager.getInstance()
                    am.tryToExecute(am.getAction(IdeActions.ACTION_FIND), null, ed.contentComponent, ActionPlaces.UNKNOWN, true)
                }
            }, project.disposed)
        }
    }

    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent = widget.preferredFocusableComponent
    override fun getName() = "Herdr"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        findBar.dispose()
        connector.close()
        if (file.connector === connector) file.connector = null
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
