// The "Herdr" tool window: a JCEF browser running the VS Code extension's sidebar renderer (media/panel.js,
// media/panel.css) unchanged. A small shim stands in for acquireVsCodeApi(), and the --vscode-* theme
// variables the CSS uses are filled from the current IntelliJ theme.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.util.Base64
import javax.swing.SwingConstants
import javax.swing.UIManager

class HerdrToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = HerdrService.getInstance(project)
        val component = if (JBCefApp.isSupported()) HerdrPanel(project, toolWindow).component
        else JBLabel("Herdr Cockpit needs JCEF (the embedded browser), which this IDE runtime doesn't have.", SwingConstants.CENTER)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(component, "", false))
        toolWindow.setTitleActions(listOf(SwitchSpaceAction(), RefreshAction(), OpenSettingsAction(), HelpAction()))
        service.setPanelVisible(toolWindow.isVisible)
        badgeToolWindow(project, toolWindow)
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(manager: ToolWindowManager) {
                service.setPanelVisible(manager.getToolWindow("Herdr")?.isVisible == true)
            }
        })
    }
}

private class HerdrPanel(private val project: Project, toolWindow: ToolWindow) {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    /** UI-only state of the page (open cards, expanded rows), kept across reloads. */
    private var uiState = "{}"
    private var ready = false
    private var pending: String? = null
    val component get() = browser.component

    init {
        Disposer.register(toolWindow.disposable, browser)
        Disposer.register(browser, query)
        query.addHandler { raw ->
            val msg = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return@addHandler null
            ApplicationManager.getApplication().invokeLater {
                when (msg.get("type")?.asString) {
                    "ready" -> { ready = true; pending?.let(::post) }
                    "ui" -> uiState = msg.get("state")?.toString() ?: "{}"
                    "cmd" -> HerdrService.getInstance(project).handle(
                        msg.get("command")?.asString ?: return@invokeLater,
                        msg.get("spaceId")?.takeIf { it.isJsonPrimitive }?.asString,
                        msg.get("paneId")?.takeIf { it.isJsonPrimitive }?.asString,
                    )
                }
            }
            null
        }
        load()
        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { load() })
        HerdrService.getInstance(project).addStateListener(toolWindow.disposable) { state ->
            ApplicationManager.getApplication().invokeLater { pending = state; if (ready) post(state) }
        }
    }

    private fun post(state: String) {
        browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new MessageEvent('message', { data: { type: 'state', state: $state } }));", "", 0,
        )
    }

    private fun load() {
        ready = false
        browser.loadHTML(html())
    }

    private fun html(): String {
        val shim = """
            window.__herdrSend = function (msg) { ${query.inject("msg")} };
            window.__herdrUi = $uiState;
            window.acquireVsCodeApi = function () {
              return {
                postMessage: function (m) { window.__herdrSend(JSON.stringify(m)); },
                getState: function () { return window.__herdrUi; },
                setState: function (s) { window.__herdrUi = s; window.__herdrSend(JSON.stringify({ type: 'ui', state: s })); }
              };
            };
            document.addEventListener('contextmenu', function (e) { e.preventDefault(); });
        """.trimIndent()
        return """<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8">
${mediaStyles()}
<style>body { background: var(--vscode-sideBar-background); padding: 4px 8px; }</style>
</head><body>
<main id="app" aria-label="Herdr spaces and agents"></main>
${script(shim)}
${script(media("agent-icons.js"))}
${script(media("panel.js"))}
</body></html>"""
    }
}

/** A file from src/main/resources/media. */
fun media(path: String): String = HerdrPanel::class.java.getResourceAsStream("/media/$path")!!.use { String(it.readBytes(), Charsets.UTF_8) }
private fun mediaBase64(path: String): String = HerdrPanel::class.java.getResourceAsStream("/media/$path")!!.use { Base64.getEncoder().encodeToString(it.readBytes()) }

// Inline scripts can't contain a literal "</script>"; the bundled files don't, but guard anyway.
fun script(js: String) = "<script>${js.replace("</script>", "<\\/script>")}</script>"

/** Theme variables, codicons and panel.css, as <style> blocks: what the sidebar and the help page share. */
fun mediaStyles(): String {
    val codicons = media("codicons/codicon.css").replace(Regex("""url\("\./codicon\.ttf[^"]*"\)"""), "url(\"data:font/ttf;base64,${mediaBase64("codicons/codicon.ttf")}\")")
    return "<style>${themeCss()}</style>\n<style>$codicons</style>\n<style>${media("panel.css")}</style>"
}

/** The VS Code theme variables media/panel.css reads, from the current IntelliJ theme. */
private fun themeCss(): String {
    val dark = !JBColor.isBright()
    fun ui(key: String, fallback: Color) = UIManager.getColor(key) ?: fallback
    fun c(color: Color?) = color?.let { if (it.alpha == 255) "#%02x%02x%02x".format(it.red, it.green, it.blue) else "rgba(${it.red},${it.green},${it.blue},${it.alpha / 255.0})" } ?: "inherit"
    val scheme = EditorColorsManager.getInstance().globalScheme
    val font = UIUtil.getLabelFont()
    val fg = UIUtil.getLabelForeground()
    val accent = JBUI.CurrentTheme.Focus.focusColor()
    val vars = mapOf(
        "foreground" to c(fg),
        "descriptionForeground" to c(UIUtil.getContextHelpForeground()),
        "sideBar-background" to c(JBUI.CurrentTheme.ToolWindow.background()),
        "focusBorder" to c(accent),
        "textLink-foreground" to c(JBUI.CurrentTheme.Link.Foreground.ENABLED),
        "list-hoverBackground" to c(JBUI.CurrentTheme.List.Hover.background(true)),
        "toolbar-hoverBackground" to c(JBUI.CurrentTheme.ActionButton.hoverBackground()),
        "button-background" to c(ui("Button.default.startBackground", accent)),
        "button-hoverBackground" to c(ui("Button.default.endBackground", accent)),
        "button-foreground" to c(ui("Button.default.foreground", Color.WHITE)),
        "button-secondaryBackground" to c(ui("Button.startBackground", JBUI.CurrentTheme.ToolWindow.background())),
        "button-secondaryHoverBackground" to c(ui("Button.endBackground", JBUI.CurrentTheme.ToolWindow.background())),
        "button-secondaryForeground" to c(ui("Button.foreground", fg)),
        "badge-background" to c(accent),
        "badge-foreground" to "#ffffff",
        "inputOption-activeBackground" to c(Color(accent.red, accent.green, accent.blue, 64)),
        "inputOption-activeForeground" to c(fg),
        "charts-blue" to if (dark) "#3794ff" else "#1a85ff",
        "charts-green" to if (dark) "#89d185" else "#388a34",
        "charts-orange" to if (dark) "#d18616" else "#c27d0e",
        "charts-purple" to if (dark) "#b180d7" else "#8152a6",
        "charts-red" to if (dark) "#f14c4c" else "#e51400",
        "gitDecoration-addedResourceForeground" to c(FileStatus.ADDED.color),
        "gitDecoration-modifiedResourceForeground" to c(FileStatus.MODIFIED.color),
        "gitDecoration-untrackedResourceForeground" to c(FileStatus.UNKNOWN.color),
        "gitDecoration-conflictingResourceForeground" to c(FileStatus.MERGED_WITH_CONFLICTS.color),
        "editor-background" to c(scheme.defaultBackground),
        "terminal-background" to c(scheme.defaultBackground),
        "terminal-foreground" to c(scheme.defaultForeground),
        "font-family" to "'${font.family}', system-ui, sans-serif",
        "font-size" to "${font.size}px",
        "editor-font-family" to "'${scheme.editorFontName}', ui-monospace, monospace",
    )
    return ":root { color-scheme: ${if (dark) "dark" else "light"}; ${vars.entries.joinToString(" ") { "--vscode-${it.key}: ${it.value};" }} }"
}

class RefreshAction : DumbAwareAction("Refresh", "Re-read Herdr's state", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { HerdrService.getInstance(it).handle("refresh", null, null) } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class SwitchSpaceAction : DumbAwareAction("Switch Herdr Space…", "Pick a Herdr space to switch to", AllIcons.Actions.ListFiles) {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { HerdrService.getInstance(it).pickSpace() } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class OpenSettingsAction : DumbAwareAction("Settings", "Herdr Cockpit settings", AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) { ShowSettingsUtil.getInstance().showSettingsDialog(e.project, HerdrConfigurable::class.java) }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class HelpAction : DumbAwareAction("Help", "How to read the Herdr sidebar", AllIcons.Actions.Help) {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { HerdrService.getInstance(it).openHelp() } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class FocusAttentionAction : DumbAwareAction("Jump to Agent That Needs You", "Open the blocked or done agent", AllIcons.General.Warning) {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { HerdrService.getInstance(it).handle("attention", null, null) } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** A pane tab's whole history as a document (the tab's context menu, Tools | Herdr). */
class OpenPaneOutputAction : DumbAwareAction("Open Pane Output in Editor", "The pane's whole history from Herdr as a read-only document", AllIcons.Actions.ShowAsTree) {
    private fun editor(e: AnActionEvent) = e.project?.let { p ->
        (e.getData(CommonDataKeys.VIRTUAL_FILE) as? HerdrPaneFile)?.let { FileEditorManager.getInstance(p).getEditors(it).firstNotNullOfOrNull { ed -> ed as? HerdrPaneEditor } }
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = editor(e) != null }
    override fun actionPerformed(e: AnActionEvent) { editor(e)?.openOutput() }
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class OpenTuiAction : DumbAwareAction("Open Herdr TUI", "Run herdr in an editor tab", AllIcons.Nodes.Console) {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { HerdrService.getInstance(it).openTui() } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** The editor's selection (or its line), or files in the project view, to an agent in Herdr with a prompt. */
class SendToAgentAction : DumbAwareAction("Send to Agent…", "Send the selection or file to an agent in Herdr, with what it should do", AllIcons.Actions.Forward) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && (e.getData(CommonDataKeys.EDITOR) != null || !e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).isNullOrEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val context = if (editor != null && file != null && file.isInLocalFileSystem) {
            val doc = editor.document
            val sel = editor.selectionModel
            val first = doc.getLineNumber(if (sel.hasSelection()) sel.selectionStart else editor.caretModel.offset)
            // A selection ending at the start of a line doesn't include that line.
            val end = if (sel.hasSelection()) maxOf(sel.selectionStart, sel.selectionEnd - 1) else editor.caretModel.offset
            val last = doc.getLineNumber(end)
            val code = if (sel.hasSelection()) sel.selectedText else null
            codeContext(file.path, first + 1, last + 1, code, file.extension.orEmpty())
        } else {
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().filter { it.isInLocalFileSystem }.joinToString("") { codeContext(it.path) }
        }
        if (context.isEmpty()) return
        HerdrService.getInstance(project).sendToAgent(context, file?.path)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** "Review with Agent…" on a merge request's changes in the GitLab tool window (registered only with the GitLab plugin). */
class ReviewChangesWithAgentAction : DumbAwareAction("Review with Agent…", "An agent in Herdr reviews these changes and lists its findings; nothing is posted", AllIcons.Actions.Preview) {
    // The merge request's change tree puts its selection (RefComparisonChange, @ApiStatus.Experimental) only under this key,
    // not in VcsDataKeys.CHANGES. shortcut: looked up by name and read reflectively so the plugin needn't depend on
    // collaborationTools; if JetBrains renames it the entry just stays hidden. Upgrade when there's a public key.
    private val selected = DataKey.create<List<Any>>("Code.Review.Change.List.Selected.RefComparisonChanges")

    private fun get(o: Any, name: String) = runCatching { o.javaClass.getMethod(name).invoke(o) }.getOrNull()

    /** (before commit, after commit, path) of each selected change. */
    private fun changes(e: AnActionEvent) = e.getData(selected).orEmpty().map { c ->
        Triple((get(c, "getRevisionNumberBefore") as? VcsRevisionNumber)?.asString(), (get(c, "getRevisionNumberAfter") as? VcsRevisionNumber)?.asString(),
            ((get(c, "getFilePathAfter") ?: get(c, "getFilePathBefore")) as? FilePath)?.path)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && changes(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changes = changes(e)
        val service = HerdrService.getInstance(project)
        val (base, head) = changeRange(changes.map { it.first to it.second })
            ?: return service.notify("Couldn't tell which commits these changes are between.", NotificationType.WARNING)
        service.reviewChanges(changes.mapNotNull { it.third }, base, head)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
