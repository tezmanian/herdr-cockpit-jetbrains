// New spaces and tabs from the IDE: the popups, folder chooser and input dialogs around HerdrActions.
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
import java.io.File
import javax.swing.Icon

/** What a new tab runs: a plain shell, or an agent kind ("claude", "codex", ...). */
data class What(val kind: String? = null, val label: String? = null)

class HerdrManage(private val project: Project, private val service: HerdrService) {
    private val actions get() = service.actions

    private class Item(val text: String, val icon: Icon?, val separator: String? = null, val run: () -> Unit)

    private fun choose(title: String, items: List<Item>) {
        val step = object : BaseListPopupStep<Item>(title, items) {
            override fun getTextFor(value: Item) = value.text
            override fun getIconFor(value: Item) = value.icon
            override fun getSeparatorAbove(value: Item) = value.separator?.let { ListSeparator(it) }
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? = doFinalStep { selectedValue.run() }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
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
