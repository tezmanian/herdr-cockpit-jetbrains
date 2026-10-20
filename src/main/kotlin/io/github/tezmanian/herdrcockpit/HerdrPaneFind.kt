// The find bar of a pane tab (Ctrl/Cmd+F): searches the pane's whole history in Herdr (TermFind.kt), scrolls Herdr's
// view so the match is in the middle, and highlights the matches on screen. Port of upstream's builtinTerminal.ts find.
package io.github.tezmanian.herdrcockpit

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.regex.PatternSyntaxException
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** A highlighted match on screen: row `y`, cells `from` until `to`. */
data class Mark(val y: Int, val from: Int, val to: Int, val current: Boolean)

/** Typing in the find bar: wait this long before searching. */
private const val FIND_DEBOUNCE_MS = 120
/** Matches listed by Tab in the find bar. */
private const val LIST_MAX = 2000

/** What the bar needs from its tab. */
interface FindHost {
    val cols: Int
    val rows: Int
    /** The text on screen row `y`. */
    fun screenRow(y: Int): String
    /** Herdr's view moved (true: to the live screen). */
    fun viewMoved(live: Boolean)
    fun repaintMarks()
    /** The bar closed: back to the terminal. */
    fun closed()
    fun openOutput()
}

class PaneFindBar(private val project: Project, private val paneId: String, private val host: FindHost) : JPanel(BorderLayout()) {
    private val field = JBTextField(30)
    private val info = JBLabel()
    // In order: a jump must not overtake the search before it.
    private val worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("Herdr find", 1)
    private val debounce = Timer(FIND_DEBOUNCE_MS) { search() }.apply { isRepeats = false }

    // State, on the EDT. Rows are the history's (0 = oldest); `view` is Herdr's view as last answered.
    private var text: PaneText? = null
    private var view: PaneScroll? = null
    private var matches: List<Match> = emptyList()
    private var cur = -1
    private var error: String? = null
    /** Rows the current match turned out to be off by (a character's width guessed wrong). */
    private var drift = 0
    private var verify = 0
    private var seq = 0

    init {
        isVisible = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Editor.BORDER_COLOR), JBUI.Borders.empty(2, 6))
        field.emptyText.text = "Find in history (/regex/ too)"
        field.focusTraversalKeysEnabled = false // Tab lists the matches
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounce.restart()
            override fun removeUpdate(e: DocumentEvent) = debounce.restart()
            override fun changedUpdate(e: DocumentEvent) = debounce.restart()
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val shift = e.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0
                when (e.keyCode) {
                    // Older first, as the search starts from the bottom (like the editor's terminal find).
                    KeyEvent.VK_ENTER, KeyEvent.VK_F3 -> step(if (shift) 1 else -1)
                    KeyEvent.VK_UP -> step(-1)
                    KeyEvent.VK_DOWN -> step(1)
                    KeyEvent.VK_TAB -> listMatches()
                    KeyEvent.VK_ESCAPE -> close()
                    else -> return
                }
                e.consume()
            }
        })
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(info)
            add(InplaceButton("Previous (older) match: Enter, ↑, F3", AllIcons.Actions.PreviousOccurence) { step(-1) })
            add(InplaceButton("Next (newer) match: Shift+Enter, ↓, Shift+F3", AllIcons.Actions.NextOccurence) { step(1) })
            add(InplaceButton("All matching lines: Tab", AllIcons.Actions.ListFiles) { listMatches() })
            add(InplaceButton("Open the whole history as a document", AllIcons.Actions.MenuOpen) { host.openOutput() })
            add(InplaceButton("Close: Esc", AllIcons.Actions.Close) { close() })
        }
        add(field, BorderLayout.WEST)
        add(buttons, BorderLayout.CENTER)
    }

    val isOpen: Boolean get() = isVisible

    fun open() {
        isVisible = true
        revalidate()
        field.requestFocusInWindow()
        field.selectAll()
        if (field.text.isNotEmpty()) search()
    }

    fun close() {
        debounce.stop()
        seq++
        isVisible = false
        matches = emptyList()
        cur = -1
        revalidate()
        host.repaintMarks()
        host.closed()
    }

    fun dispose() {
        debounce.stop()
        seq++
        worker.shutdownNow()
    }

    /** Off the EDT, then back on it unless the bar moved on (closed, or a newer search) meanwhile. */
    private fun <T> async(work: () -> T, done: (T) -> Unit) {
        val mine = seq
        worker.execute {
            val r = runCatching(work)
            ApplicationManager.getApplication().invokeLater({
                if (mine != seq || !isOpen) return@invokeLater
                r.onSuccess(done).onFailure { error = it.message ?: it.toString(); paintInfo() }
            }, project.disposed)
        }
    }

    private val actions get() = HerdrService.getInstance(project).actions

    /** Reads the history again when the pane's content changed since. */
    private fun load(): Pair<PaneText, PaneScroll> {
        val rev = actions.paneRevision(paneId)
        val v = actions.paneScroll(paneId)
        val t = text?.takeIf { it.revision == rev && it.cols == host.cols }
            ?: PaneText(actions.paneHistory(paneId, host.cols).split('\n'), host.cols, rev)
        return t to v
    }

    private fun search() {
        val q = field.text
        val re = try {
            compileQuery(q)
        } catch (e: PatternSyntaxException) {
            seq++ // a search still on its way must not overwrite the error
            matches = emptyList(); cur = -1; error = "Invalid regular expression"
            return paintInfo()
        }
        seq++
        if (re == null) {
            matches = emptyList(); cur = -1; error = null
            return paintInfo()
        }
        val prev = matches.getOrNull(cur)?.row
        async({ load() }) { (t, v) ->
            text = t
            view = v
            matches = findMatches(t, re)
            error = null
            drift = 0
            // Refining the query stays near the match it was on; a new search starts from the bottom of the view.
            cur = if (matches.isEmpty()) -1 else startAt(matches, prev ?: (v.max - v.offset + v.rows - 1))
            if (cur >= 0) jump(cur) else paintInfo()
        }
    }

    /** Next (newer, +1) or previous (older, -1) match, wrapping around. */
    fun step(dir: Int) {
        if (matches.isEmpty()) {
            if (field.text.isNotEmpty()) search()
            return
        }
        jump((cur + dir + matches.size) % matches.size)
        // If the pane's content changed since it was read, search again (rows may have moved).
        val rev = text?.revision
        async({ actions.paneRevision(paneId) }) { if (it != rev) search() }
    }

    /** Scroll Herdr's view so match `i` is in the middle, and highlight it. */
    private fun jump(i: Int) {
        val m = matches.getOrNull(i) ?: return
        val v0 = view ?: return
        cur = i
        paintInfo()
        async({
            var v = actions.scrollPaneTo(paneId, offsetFor(m.row, v0.max, v0.rows))
            // New output since the history was read moves the bottom: aim again from where it is now.
            val again = offsetFor(m.row, v.max, v.rows)
            if (again != v.offset) v = actions.scrollPaneTo(paneId, again)
            v
        }) { v ->
            view = v
            verify = 5
            host.viewMoved(v.offset == 0)
            host.repaintMarks()
        }
    }

    /** Herdr's view was scrolled some other way (the wheel, Shift+PageUp): where is it now. */
    fun refreshView() {
        if (!isOpen || matches.isEmpty()) return
        async({ actions.paneScroll(paneId) }) { view = it; host.repaintMarks() }
    }

    private fun paintInfo() {
        info.text = error ?: when {
            field.text.isEmpty() -> ""
            matches.isEmpty() -> if (text == null) "" else "No results"
            else -> "${cur + 1} of ${matches.size}"
        }
        info.foreground = if (error != null) JBUI.CurrentTheme.Focus.errorColor(true) else JBUI.CurrentTheme.Label.foreground()
        host.repaintMarks()
    }

    /** The matches on screen, to highlight. */
    fun marks(): List<Mark> {
        val v = view ?: return emptyList()
        if (!isOpen || matches.isEmpty()) return emptyList()
        if (verify > 0) verifyCurrent(v)
        val top = v.max - v.offset - drift
        val bottom = top + host.rows
        val out = ArrayList<Mark>()
        for ((i, m) in matches.withIndex()) {
            if (m.row >= bottom) break
            for (s in m.spans) if (s.row in top until bottom) out += Mark(s.row - top, s.from, s.to, i == cur)
        }
        return out
    }

    /**
     * Lines are mapped to rows by wrapping them at the pane's width; if that's off (a character's width), find the
     * current match on screen near where it should be and correct for it.
     */
    private fun verifyCurrent(v: PaneScroll) {
        val m = matches.getOrNull(cur)
        val t = text
        if (m == null || t == null) {
            verify = 0
            return
        }
        verify--
        val want = t.lines[m.line].substring(m.index, m.index + m.length).lowercase()
        val y = m.row - (v.max - v.offset - drift)
        for (d in listOf(0, -1, 1, -2, 2, -3, 3)) {
            if (y + d !in 0 until host.rows) continue
            if (host.screenRow(y + d).lowercase().contains(want)) {
                drift += d
                verify = 0
                return
            }
        }
    }

    /** Tab: every matching line in a list to filter; moving through it previews, Enter stays there. */
    private fun listMatches() {
        val t = text ?: return
        if (matches.isEmpty()) return
        // The list shows toString().
        data class Item(val i: Int, val label: String) { override fun toString() = label }
        val items = matches.take(LIST_MAX).mapIndexed { i, m -> Item(i, t.lines[m.line].trim().take(200).ifEmpty { " " }) }
        val title = "${matches.size} match${if (matches.size == 1) "" else "es"} for “${field.text}”" + if (matches.size > LIST_MAX) " (first $LIST_MAX)" else ""
        var ready = false
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(title)
            .setNamerForFiltering { it.label }
            .setSelectedValue(items.getOrNull(cur.coerceAtMost(items.size - 1)), true)
            .setItemSelectedCallback { if (ready && it != null) jump(it.i) }
            .setItemChosenCallback { jump(it.i) }
            .createPopup()
            .showUnderneathOf(field)
        ready = true // selecting the start item isn't a move
    }
}
