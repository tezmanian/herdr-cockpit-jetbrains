// New spaces and tabs, renames and closes from the IDE: the popups, folder chooser and dialogs around HerdrActions.
// Every new pane opens straight away in an editor tab; an agent is then started in it, in the background.
package io.github.tezmanian.herdrcockpit

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.awt.RelativePoint
import java.awt.MouseInfo
import java.io.File
import javax.swing.Icon

/** What a new tab runs: a plain shell, or an agent kind ("claude", "codex", ...). */
data class What(val kind: String? = null, val label: String? = null)

class HerdrManage(private val project: Project, private val service: HerdrService) {
    private val actions get() = service.actions

    private class Item(val text: String, val icon: Icon?, val separator: String? = null, val run: () -> Unit)

    private fun choose(title: String?, items: List<Item>, atMouse: Boolean = false) {
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

    private fun input(title: String, message: String, initial: String = "", error: (String) -> String? = { if (it.isBlank()) "Enter a name" else null }): String? =
        Messages.showInputDialog(project, message, title, null, initial, object : InputValidatorEx {
            override fun getErrorText(inputString: String) = error(inputString)
        })?.trim()?.takeIf { it.isNotEmpty() }

    private fun connected(): Boolean {
        if (service.isConnected()) return true
        service.notify("Not connected to Herdr.", NotificationType.WARNING)
        return false
    }

    /** "Shell", "Shell with a name…", then the favorite agents and "More agents…". */
    fun pickWhat(title: String, then: (What) -> Unit) {
        val favorites = favoriteAgents(HerdrSettings.get().agents)
        val agentIcon = AllIcons.Nodes.Plugin
        val items = buildList {
            add(Item("Shell", AllIcons.Nodes.Console) { then(What()) })
            add(Item("Shell with a name…", AllIcons.Actions.Edit) { input(title, "Tab name (e.g. dev server):")?.let { then(What(label = it)) } })
            favorites.forEachIndexed { i, k -> add(Item(agentTitle(k), agentIcon, if (i == 0) "Agents" else null) { then(What(kind = k)) }) }
            add(Item("More agents…", AllIcons.Actions.More, if (favorites.isEmpty()) "Agents" else null) {
                choose("$title: agent", AGENT_KINDS.keys.filter { it !in favorites }.map { k -> Item(agentTitle(k), agentIcon) { then(What(kind = k)) } })
            })
        }
        choose(title, items)
    }

    fun newTab(space: Space) {
        if (!connected()) return
        pickWhat("New tab in ${space.label}") { what ->
            run("New tab in ${space.label}") {
                val c = actions.createTab(space.id, space.cwd, what.label ?: what.kind?.let(::agentTitle))
                openCreated(it, c, what, space.label)
            }
        }
    }

    /** New space: in a folder you pick, or on a new git worktree of an existing space's repository. */
    fun newSpace() {
        if (!connected()) return
        val spaces = service.currentModel()?.spaces ?: emptyList()
        val repos = spaces.mapNotNull { s -> service.branchOf(s.cwd)?.let { s to it } }
        val items = buildList {
            add(Item("Choose a folder…", AllIcons.Nodes.Folder) { newSpaceInFolder(spaces) })
            repos.forEachIndexed { i, (s, branch) ->
                add(Item("${s.label}  (branch off $branch)", AllIcons.Vcs.Branch, if (i == 0) "New git worktree of" else null) { newWorktreeSpace(s, branch) })
            }
        }
        choose("New space", items)
    }

    private fun newSpaceInFolder(spaces: List<Space>) {
        val near = spaces.firstNotNullOfOrNull { it.cwd }?.let { File(it).parent } ?: System.getProperty("user.home")
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("New Space: Folder")
        FileChooser.chooseFile(descriptor, project, LocalFileSystem.getInstance().findFileByPath(near)) { dir ->
            val label = input("New space", "Space name:", dir.name) ?: return@chooseFile
            pickWhat("Start in \"$label\"") { what -> createSpace(label, what) { actions.createSpace(dir.path, label) } }
        }
    }

    private fun newWorktreeSpace(from: Space, branch: String) {
        val name = input("New worktree of ${from.label}", "New branch name (created from $branch), e.g. feat/new-idea:") {
            if (it.isBlank()) "Enter a branch name" else if (!isValidBranch(it)) "Not a valid git branch name" else null
        } ?: return
        pickWhat("Start in \"$name\"") { what -> createSpace(name, what) { actions.createWorktreeSpace(from.id, name, name) } }
    }

    /** Right-click on a space or pane in the sidebar. */
    fun menu(space: Space, pane: Pane?) {
        if (!connected()) return
        val items = buildList {
            if (pane != null) add(Item("Open", AllIcons.Actions.MenuOpen) { service.attachPane(space, pane) })
            else add(Item("New Tab…", AllIcons.General.Add) { newTab(space) })
            add(Item("Rename…", AllIcons.Actions.Edit) { rename(space, pane) })
            add(Item("Close in Herdr…", AllIcons.Actions.Close, "Herdr") { closeInHerdr(space, pane) })
        }
        choose(null, items, atMouse = true)
    }

    /** A space, or a pane (shells: the pane label; agents: the agent's name). */
    fun rename(space: Space, pane: Pane?) {
        if (pane == null) {
            val label = input("Rename space", "Space name:", space.label) ?: return
            run("Rename ${space.label}") { actions.renameSpace(space.id, label); service.scheduleRefresh() }
            return
        }
        val current = paneName(space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        val value = if (pane.isAgent) {
            input("Rename $current", "Agent name: lowercase letters, digits, - or _ (e.g. reviewer):", pane.agentName ?: pane.agentKind ?: "") {
                if (!AGENT_NAME.matches(it.trim())) "Start with a letter; use lowercase letters, digits, - or _" else null
            } ?: return
        } else {
            // Empty clears the label: Herdr falls back to the tab name.
            Messages.showInputDialog(project, "Shell name (empty: use the tab name):", "Rename $current", null, pane.paneLabel ?: current, null)?.trim() ?: return
        }
        run("Rename $current") {
            if (pane.isAgent) actions.renameAgent(pane.id, value) else actions.renamePane(pane.id, value.ifEmpty { null })
            service.scheduleRefresh()
        }
    }

    /** Closes a pane (its tab, when it's the only pane) or a whole space in Herdr, after confirming. */
    fun closeInHerdr(space: Space, pane: Pane?) {
        if (pane == null) return closeSpace(space)
        val tab = space.tabs.firstOrNull { it.id == pane.tabId }
        val name = paneName(tab, pane)
        val what = if (pane.isAgent) "The agent" else "The shell and anything running in it"
        if (Messages.showOkCancelDialog(project, "$what will stop. Closing the editor tab instead only detaches.", "Close \"$name\" in Herdr?", "Close", "Cancel", Messages.getWarningIcon()) != Messages.OK) return
        run("Close $name") {
            if (tab != null && tab.panes.size <= 1) actions.closeTab(tab.id) else actions.closePane(pane.id)
            service.scheduleRefresh()
        }
    }

    private fun closeSpace(space: Space) {
        val agents = space.agents.size
        val shells = space.panes.size - agents
        val what = listOfNotNull(agents.takeIf { it > 0 }?.let { "$it agent${if (it > 1) "s" else ""}" }, shells.takeIf { it > 0 }?.let { "$it shell${if (it > 1) "s" else ""}" })
            .joinToString(" and ").ifEmpty { "Everything" }
        val title = "Close the space \"${space.label}\" in Herdr?"
        val delete = if (space.linkedWorktree) {
            when (Messages.showYesNoCancelDialog(project, "$what in it will stop. It's a git worktree at ${space.cwd}: \"Close Space\" keeps that folder, \"Close and Delete Worktree\" removes it (Herdr refuses if it has uncommitted changes).",
                title, "Close Space", "Close and Delete Worktree", "Cancel", Messages.getWarningIcon())) {
                Messages.YES -> false
                Messages.NO -> true
                else -> return
            }
        } else {
            if (Messages.showOkCancelDialog(project, "$what in it will stop. Files on disk are not touched.", title, "Close Space", "Cancel", Messages.getWarningIcon()) != Messages.OK) return
            false
        }
        if (delete) return removeWorktree(space, force = false)
        run("Close ${space.label}") {
            try {
                actions.closeSpace(space.id)
            } catch (e: HerdrError) {
                if (e.code != "workspace_group_close_required") throw e
                later {
                    if (Messages.showOkCancelDialog(project, "Herdr closes a space together with its worktree spaces. Their worktree folders stay on disk.", "\"${space.label}\" has worktree spaces", "Close All", "Cancel", Messages.getWarningIcon()) == Messages.OK)
                        run("Close ${space.label}") { actions.closeSpace(space.id, group = true); service.scheduleRefresh() }
                }
            }
            service.scheduleRefresh()
        }
    }

    private fun removeWorktree(space: Space, force: Boolean) {
        run("Delete worktree ${space.label}") {
            try {
                actions.removeWorktree(space.id, force)
            } catch (e: HerdrError) {
                if (force) throw e
                later {
                    if (Messages.showOkCancelDialog(project, "${e.message}\n\nDelete it anyway? Uncommitted changes in ${space.cwd} will be lost.", "Herdr didn't delete the worktree \"${space.label}\"", "Delete Anyway", "Cancel", Messages.getWarningIcon()) == Messages.OK)
                        removeWorktree(space, force = true)
                }
            }
            service.scheduleRefresh()
        }
    }

    /** You closed a pane's editor tab yourself (setting "closeTabInHerdr"): close the pane in Herdr too. Background thread. */
    fun closedTabByUser(space: Space, pane: Pane, mode: String) {
        val name = paneName(space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        val running = if (pane.isAgent) null else runCatching { actions.processInfo(pane.id) }.getOrNull()
            ?.let(::leaderProcess)?.takeIf { !isShellProcess(it.name) }?.let(::describeProcess)
        val last = space.panes.size == 1
        val close = { run("Close $name") { actions.closePane(pane.id); service.log("closed ${pane.id} in Herdr (tab closed)"); service.scheduleRefresh() } }
        if (!last && (mode == "always" || (!pane.isAgent && running == null))) return close()
        val detail = listOfNotNull(
            if (pane.isAgent) "$name is an agent: closing it in Herdr stops it." else running?.let { "It's running $it." },
            if (last) "It's the last tab of \"${space.label}\", so Herdr closes the space too." else null,
            "Keep Running leaves it in Herdr; click it in the sidebar to open it again.",
        ).joinToString(" ")
        later { if (Messages.showOkCancelDialog(project, detail, "Close $name in Herdr too?", "Close in Herdr", "Keep Running", Messages.getWarningIcon()) == Messages.OK) close() }
    }

    private fun later(f: () -> Unit) = ApplicationManager.getApplication().invokeLater(f, project.disposed)

    private fun createSpace(label: String, what: What, create: () -> Created) = run("Creating space \"$label\"") { indicator ->
        val c = create()
        // Name the first tab after what runs in it, like a new tab.
        val tabLabel = what.label ?: what.kind?.let(::agentTitle)
        if (tabLabel != null && c.tabId != null) runCatching { actions.renameTab(c.tabId, tabLabel) }
        openCreated(indicator, c, what, label)
    }

    /** Opens the pane Herdr just created, then starts its agent if one was chosen. Background thread. */
    private fun openCreated(indicator: ProgressIndicator, c: Created, what: What, where: String) {
        val hit = service.awaitPane(c.paneId)
        if (hit == null) {
            service.notify("Herdr created ${c.paneId} but it hasn't appeared yet. Refresh and click it in the sidebar.", NotificationType.WARNING)
            return
        }
        // Load a new folder (e.g. a worktree) into the VFS here, not on the EDT when the space is mounted.
        hit.first.cwd?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
        ApplicationManager.getApplication().invokeLater({ service.attachPane(hit.first, hit.second) }, project.disposed)
        val kind = what.kind ?: return
        indicator.text = "Starting ${agentTitle(kind)} in $where…"
        val taken = service.currentModel()?.spaces?.flatMap { s -> s.agents.mapNotNull { it.agentName } } ?: emptyList()
        try {
            actions.startAgent(c.paneId, kind, agentName(kind, taken))
        } catch (e: Exception) {
            service.log("agent.start $kind in ${c.paneId}: ${e.message}")
            service.notify("Couldn't start ${agentTitle(kind)}: ${e.message}", NotificationType.ERROR)
        }
        service.scheduleRefresh()
    }

    private fun run(title: String, body: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, "Herdr: $title", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    body(indicator)
                } catch (e: Exception) {
                    service.log("$title: ${e.message}")
                    service.notify("$title failed: ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }
}
