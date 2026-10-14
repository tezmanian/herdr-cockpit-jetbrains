// "How to read the Herdr sidebar" (media/help.html, adapted from the VS Code extension) in an editor tab. It reuses the
// sidebar's styles, so its examples look exactly like the real sidebar.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Commands the page's buttons may run. */
private val HELP_COMMANDS = setOf("openSettings", "refresh", "startServer", "openTui")

class HerdrHelpFile : LightVirtualFile("Herdr Help", PlainTextFileType.INSTANCE, "") {
    init {
        isWritable = false
    }

    override fun getPath(): String = "herdr://help"
}

class HerdrHelpEditor(project: Project, private val file: HerdrHelpFile) : UserDataHolderBase(), FileEditor {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    init {
        Disposer.register(this, browser)
        Disposer.register(browser, query)
        query.addHandler { raw ->
            val cmd = runCatching { JsonParser.parseString(raw).asJsonObject.get("command").asString }.getOrNull()
            if (cmd in HELP_COMMANDS) ApplicationManager.getApplication().invokeLater { HerdrService.getInstance(project).handle(cmd!!, null, null) }
            null
        }
        val shim = "window.acquireVsCodeApi = function () { return { postMessage: function (m) { ${query.inject("JSON.stringify(m)")} } }; };"
        browser.loadHTML("""<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"><title>Herdr Help</title>
${mediaStyles()}
<style>${media("help.css")}</style>
</head><body><main class="doc">${media("help.html")}</main>
${script(shim)}
${script(media("agent-icons.js"))}
${script(media("help.js"))}
</body></html>""")
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = "Herdr Help"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() {}
}
