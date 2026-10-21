// New spaces and tabs, renames and closes from the IDE: the popups, folder chooser and dialogs around HerdrActions.
// Every new pane opens straight away in an editor tab; an agent is then started in it, in the background.
package io.github.tezmanian.herdrcockpit

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.awt.RelativePoint
import java.awt.MouseInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    /** New space: in one of the window's projects, a folder you pick, or on a new git worktree of an existing space's repository. */
    fun newSpace() {
        if (!connected()) return
        val spaces = service.currentModel()?.spaces ?: emptyList()
        val repos = spaces.mapNotNull { s -> service.branchOf(s.cwd)?.let { s to it } }
        val home = System.getProperty("user.home")
        val items = buildList {
            service.projectFolders().forEachIndexed { i, dir ->
                add(Item("${File(dir).name}  (${dir.replaceFirst(home, "~")})", AllIcons.Nodes.Module, if (i == 0) "Project" else null) { newSpaceIn(dir, File(dir).name) })
            }
            add(Item("Choose a folder…", AllIcons.Nodes.Folder, "Other") { newSpaceInFolder(spaces) })
            repos.forEachIndexed { i, (s, branch) ->
                add(Item("${s.label}  (branch off $branch)", AllIcons.Vcs.Branch, if (i == 0) "New git worktree of" else null) { newWorktreeSpace(s, branch) })
            }
        }
        choose("New space", items)
    }

    private fun newSpaceInFolder(spaces: List<Space>) {
        val near = project.basePath ?: spaces.firstNotNullOfOrNull { it.cwd }?.let { File(it).parent } ?: System.getProperty("user.home")
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("New Space: Folder")
        FileChooser.chooseFile(descriptor, project, LocalFileSystem.getInstance().findFileByPath(near)) { dir -> newSpaceIn(dir.path, dir.name) }
    }

    private fun newSpaceIn(dir: String, name: String) {
        val label = input("New space", "Space name:", name) ?: return
        pickWhat("Start in \"$label\"") { what -> createSpace(label, what) { actions.createSpace(dir, label) } }
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
            else {
                add(Item("Switch to Space", AllIcons.Actions.Forward) { service.switchTo(space) })
                if (service.isOurMount(space)) add(Item("Unmount Folder", AllIcons.Actions.Cancel) { service.unmount(space) })
                add(Item("New Tab…", AllIcons.General.Add) { newTab(space) })
            }
            if (pane == null || pane.isAgent) add(Item("Review Changes", AllIcons.Actions.Diff) { review(space, pane) })
            if (pane?.isAgent == true) add(Item("Review Branch with Agent…", AllIcons.Actions.Preview) { reviewBranch(space, pane) })
            if (pane?.isAgent == true) add(Item("Hand Off to Agent…", AllIcons.Actions.Forward) { handOff(space, pane) })
            if (pane?.agentKind == "claude") add(Item("Switch Claude Session…", AllIcons.Vcs.History) { switchClaudeSession(space, pane) })
            add(Item("Rename…", AllIcons.Actions.Edit) { rename(space, pane) })
            add(Item("Close in Herdr…", AllIcons.Actions.Close, "Herdr") { closeInHerdr(space, pane) })
            add(Item("Show Raw", AllIcons.FileTypes.Json, "Debug") { showRaw(space, pane) })
        }
        choose(null, items, atMouse = true)
    }

    /** Pick another agent (any space), write what it should do; the prompt says where the work came from. */
    fun handOff(space: Space, pane: Pane) {
        if (!connected()) return
        val from = editorTitle(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        val targets = service.currentModel()?.spaces.orEmpty().flatMap { s -> s.agents.filter { it.id != pane.id }.map { s to it } }
        if (targets.isEmpty()) return service.notify("No other agent in Herdr to hand off to.", NotificationType.INFORMATION)
        promptOne("Hand off $from to", targets, "Hand Off to", "", handoffContext(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane))
    }

    /** A fresh session (`/clear`) or one of Claude Code's saved sessions for the agent's folder (`/resume <id>`). */
    fun switchClaudeSession(space: Space, pane: Pane) {
        val name = editorTitle(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        val cwd = pane.cwd ?: space.cwd
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = cwd?.let { claudeSessions(claudeProjectDir(File(System.getProperty("user.home")), it)) }.orEmpty()
            ApplicationManager.getApplication().invokeLater {
                val send = { text: String -> prompt("$name: $text", name, pane.id, text) }
                choose("Switch Claude session of $name", buildList {
                    add(Item("New Session (/clear)", AllIcons.General.Add) { send("/clear") })
                    sessions.forEachIndexed { i, s ->
                        add(Item("${sessionWhen(s.modified)}  ${s.title}", null, if (i == 0) "Resume" else null) { send("/resume ${s.id}") })
                    }
                })
            }
        }
    }

    /** Code or files from the IDE to an agent:agents in the space holding `path` first, the dialog starts with `context`. */
    fun sendToAgent(context: String, path: String?) {
        if (!connected()) return
        val spaces = service.currentModel()?.spaces.orEmpty()
        val targets = spaces.flatMap { s -> s.agents.map { s to it } }
            .sortedByDescending { (s, _) -> val cwd = s.cwd; path != null && cwd != null && (path == cwd || path.startsWith(cwd.trimEnd('/') + "/")) }
        if (targets.isEmpty()) return service.notify("No agent in Herdr to send this to.", NotificationType.INFORMATION)
        promptOne("Send to", targets, "Send to", context, "")
    }

    /** Pick one of `targets`, write the prompt (starting with `initial`), send it with `suffix` appended. */
    private fun promptOne(title: String, targets: List<Pair<Space, Pane>>, verb: String, initial: String, suffix: String) {
        choose(title, targets.map { (s, a) ->
            Item("${editorTitle(s, s.tabs.firstOrNull { it.id == a.tabId }, a)} (${a.status ?: AgentStatus.unknown})", null) {
                val to = editorTitle(s, s.tabs.firstOrNull { it.id == a.tabId }, a)
                val text = Messages.showMultilineInputDialog(project, "What should $to do?" + if (suffix.isNotEmpty()) " A note on where it comes from is added." else "", "$verb $to", initial, null, object : InputValidatorEx {
                    // OK stays off until there's something to send beyond the prefilled code.
                    override fun getErrorText(t: String?) = if (t.isNullOrBlank() || t.trim() == initial.trim()) "Write what $to should do" else null
                    override fun checkInput(t: String?) = getErrorText(t) == null
                    override fun canClose(t: String?) = checkInput(t)
                })
                    ?.trim()?.takeIf { it.isNotEmpty() && it != initial.trim() } ?: return@Item
                prompt("$verb $to", to, a.id, text + suffix)
            }
        })
    }

    private fun prompt(title: String, to: String, paneId: String, text: String) = run(title) {
        try {
            actions.promptAgent(paneId, text)
        } catch (e: HerdrError) {
            if (e.code != "agent_blocked") throw e
            service.notify("$to is waiting for an answer; nothing was sent.", NotificationType.WARNING)
        }
    }

    /** Herdr's snapshot records (and, for a pane, the polled activity) as a read-only JSON tab. */
    private fun showRaw(space: Space, pane: Pane?) {
        val raw = service.currentModel()?.rawOf(space.id, pane?.id) ?: return
        if (pane != null) raw.add("activity", com.google.gson.Gson().toJsonTree(service.activityOf(pane.id)))
        val json = com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(raw)
        val name = (pane?.let { paneName(space.tabs.firstOrNull { t -> t.id == it.tabId }, it) } ?: space.label) + ".json"
        val file = com.intellij.testFramework.LightVirtualFile(name, FileTypeManager.getInstance().getFileTypeByExtension("json"), json).apply { isWritable = false }
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
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

    /** "Review": every uncommitted change in the repo of the pane (or space) folder, as HEAD ↔ working tree diffs. */
    fun review(space: Space, pane: Pane?) {
        val cwd = pane?.cwd ?: space.cwd ?: return service.notify("No folder known for ${space.label}.", NotificationType.WARNING)
        // Git runs programs from the repo's config in some setups; GitInfo hardens that, but untrusted projects skip git entirely.
        if (!TrustedProjects.isProjectTrusted(project)) return service.notify("Review needs a trusted project (git isn't run in untrusted ones).", NotificationType.WARNING)
        run("Review ${pane?.let { paneName(space.tabs.firstOrNull { t -> t.id == it.tabId }, it) } ?: space.label}") {
            val root = gitRoot(cwd) ?: return@run service.notify("$cwd is not inside a git repository.", NotificationType.INFORMATION)
            val changes = gitChanges(root) ?: throw IllegalStateException("git status failed in $root")
            if (changes.isEmpty()) return@run service.notify("No uncommitted changes in ${File(root).name}.", NotificationType.INFORMATION)
            val chain = SimpleDiffRequestChain.fromProducers(changes.map { diffOf(root, it) })
            later { DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT) }
        }
    }

    private class PendingReview(val root: String, val out: File, val since: Long)
    private val reviews = ConcurrentHashMap<String, PendingReview>()

    /** Asks the agent to review its folder's branch against a target and write findings to a file; nothing is posted. */
    fun reviewBranch(space: Space, pane: Pane) {
        val cwd = pane.cwd ?: space.cwd ?: return service.notify("No folder known for ${space.label}.", NotificationType.WARNING)
        if (!TrustedProjects.isProjectTrusted(project)) return service.notify("Review needs a trusted project (git isn't run in untrusted ones).", NotificationType.WARNING)
        val name = editorTitle(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        run("Review branch with $name") {
            val root = gitRoot(cwd) ?: return@run service.notify("$cwd is not inside a git repository.", NotificationType.INFORMATION)
            val branch = gitBranch(root) ?: return@run service.notify("${File(root).name} is not on a branch.", NotificationType.INFORMATION)
            val default = gitDefaultBranch(root) ?: "main"
            later {
                val target = input("Review $branch with $name", "Compare $branch against (branch or ref):", default) {
                    if (!isValidBranch(it)) "Not a valid branch name" else null
                } ?: return@later
                val out = reviewFile(File(System.getProperty("user.home")), space.label, branch)
                val extra = reviewInstructions(name) ?: return@later
                startReview(name, pane.id, root, out, reviewPrompt(root, branch, target, out, extra))
            }
        }
    }

    /** A merge request's changes (GitLab tool window): pick an agent, it reviews `base`..`head` for those files. */
    fun reviewChanges(files: List<String>, base: String, head: String) {
        if (!connected()) return
        if (!TrustedProjects.isProjectTrusted(project)) return service.notify("Review needs a trusted project (git isn't run in untrusted ones).", NotificationType.WARNING)
        run("Review with agent") {
            // A file new in the merge request may not exist here: find the repo from its nearest existing folder.
            val dir = generateSequence(File(files.first())) { it.parentFile }.firstOrNull { it.isDirectory }?.path
            val root = dir?.let(::gitRoot) ?: return@run service.notify("${files.first()} is not inside a git repository.", NotificationType.INFORMATION)
            val paths = files.mapNotNull { f -> File(f).relativeTo(File(root)).path.takeUnless { it.startsWith("..") } }.distinct()
            later {
                val targets = service.currentModel()?.spaces.orEmpty().flatMap { s -> s.agents.map { s to it } }
                    .sortedByDescending { (s, a) -> (a.cwd ?: s.cwd)?.let { it == root || it.startsWith("$root/") } == true }
                if (targets.isEmpty()) return@later service.notify("No agent in Herdr to review this.", NotificationType.INFORMATION)
                choose("Review ${paths.size} file${if (paths.size > 1) "s" else ""} with", targets.map { (s, a) ->
                    val name = editorTitle(s, s.tabs.firstOrNull { it.id == a.tabId }, a)
                    Item("$name (${a.status ?: AgentStatus.unknown})", null) {
                        val out = reviewFile(File(System.getProperty("user.home")), s.label, "mr-" + head.take(12))
                        val extra = reviewInstructions(name) ?: return@Item
                        startReview(name, a.id, root, out, reviewChangesPrompt(root, base, head, paths, out, extra))
                    }
                })
            }
        }
    }

    /** Optional extra instructions for the review ("" when left empty); null when cancelled. */
    private fun reviewInstructions(name: String): String? =
        Messages.showMultilineInputDialog(project, "Anything $name should look at in particular? (optional, e.g. \"focus on security\")", "Review with $name", "", null, null)?.trim()

    private fun startReview(name: String, paneId: String, root: String, out: File, text: String) = run("Review with $name") {
        out.parentFile.mkdirs()
        out.delete()
        reviews[paneId] = PendingReview(root, out, System.currentTimeMillis())
        try {
            actions.promptAgent(paneId, text)
        } catch (e: HerdrError) {
            reviews.remove(paneId)
            if (e.code != "agent_blocked") throw e
            service.notify("$name is waiting for an answer; nothing was sent.", NotificationType.WARNING)
        }
    }

    /** An agent stopped working: if it owes a review and has written the file, offer its findings. */
    fun agentSettled(space: Space, pane: Pane) {
        val r = reviews[pane.id] ?: return
        if (!r.out.isFile || r.out.lastModified() < r.since) return
        reviews.remove(pane.id)
        val name = editorTitle(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        val findings = parseReview(r.out.readText(), File(r.root))
            ?: return service.notify("$name's review in ${r.out} isn't a JSON array of findings.", NotificationType.WARNING)
        if (findings.isEmpty()) return service.notify("$name's review: no findings.", NotificationType.INFORMATION)
        later {
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit")
                .createNotification("$name's review: ${findings.size} finding${if (findings.size > 1) "s" else ""}", NotificationType.INFORMATION)
                .addAction(com.intellij.notification.NotificationAction.createSimple("Show") { showFindings(name, r.root, findings) })
                .notify(project)
        }
    }

    private fun showFindings(name: String, root: String, findings: List<Finding>) {
        choose("Review by $name", findings.map { f ->
            Item("${f.path}:${f.line}  ${f.body.lineSequence().first().take(120)}", null) {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$root/${f.path}")
                    ?: return@Item service.notify("${f.path} doesn't exist (any more).", NotificationType.WARNING)
                OpenFileDescriptor(project, vf, f.line - 1, 0).navigate(true)
                service.notify("${f.path}:${f.line}: ${f.body}", NotificationType.INFORMATION)
            }
        })
    }

    /** One file's diff, built when the diff viewer gets to it (off the EDT). */
    private fun diffOf(root: String, c: GitChange) = object : DiffRequestProducer {
        override fun getName() = c.path
        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            val factory = DiffContentFactory.getInstance()
            val type = FileTypeManager.getInstance().getFileTypeByFileName(File(c.path).name)
            val head = if (c.status == "??") null else gitShowHead(root, c.from ?: c.path)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath("$root/${c.path}")
            val left = head?.let { factory.create(project, it, type) } ?: factory.createEmpty()
            val right = file?.let { ApplicationManager.getApplication().runReadAction<DiffContent> { factory.create(project, it) } } ?: factory.createEmpty()
            val what = c.from?.let { "renamed from $it" } ?: if (c.status == "??") "new" else c.status.trim()
            return SimpleDiffRequest("${c.path} ($what)", left, right, if (head == null) "Not in HEAD" else "HEAD", if (file == null) "Deleted" else "Working tree")
        }
    }

    // ---------- usage ----------

    fun setKiroCredits() {
        val s = HerdrSettings.get()
        val v = input("Kiro Monthly Credits", "Your plan's monthly credit allowance (Kiro shows it in /usage). 0 hides the bar.", s.kiroMonthlyCredits.toString()) {
            if ((it.trim().toIntOrNull() ?: -1) >= 0) null else "Enter a whole number"
        } ?: return
        s.kiroMonthlyCredits = v.toInt()
        service.pollUsageNow()
    }

    /**
     * Claude Code shares its 5-hour and weekly limits only with a status line command: after asking, installs the
     * tiny one from claudeStatusLineScript() in ~/.claude/settings.json. Never replaces an existing status line.
     */
    fun setUpClaudeUsage() {
        val home = System.getProperty("user.home")
        val settingsFile = File(home, ".claude/settings.json")
        val capture = claudeCaptureFile(home)
        val settings = try {
            if (settingsFile.exists()) com.google.gson.JsonParser.parseString(settingsFile.readText()).asJsonObject else com.google.gson.JsonObject()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Couldn't read $settingsFile: ${e.message}", "Herdr")
            return
        }
        if (settings.has("statusLine")) {
            val snippet = "tee \"$capture\" | "
            if (Messages.showOkCancelDialog(project,
                    "To show plan limits in the sidebar, make your status line command also save its input: put $snippet in front of the command in ~/.claude/settings.json (\"statusLine\" → \"command\").",
                    "You already have a Claude Code status line, so Herdr left it alone", "Copy Snippet", "Close", Messages.getInformationIcon()) == Messages.OK)
                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(snippet))
            return
        }
        if (Messages.showOkCancelDialog(project,
                "Claude Code shares your 5-hour and weekly usage only with a status line command. Herdr will add a small one to " +
                    "~/.claude/settings.json (a backup is saved next to it) that shows \"5h 42% · 7d 18%\" under the Claude prompt and saves " +
                    "the numbers to $capture. With a custom status line, Claude Code hides some footer hints such as \"? for shortcuts\".\n\n" +
                    "Limits appear for claude.ai Pro and Max plans, after the next reply in each session. To undo, remove \"statusLine\" from the settings file.",
                "Show Claude plan limits in the Herdr sidebar?", "Set Up", "Cancel", Messages.getQuestionIcon()) != Messages.OK) return
        try {
            val script = File(capture.parentFile, "claude-statusline.sh")
            capture.parentFile.mkdirs()
            script.writeText(claudeStatusLineScript(capture))
            script.setExecutable(true)
            if (settingsFile.exists()) settingsFile.copyTo(File("$settingsFile.herdr-backup"), overwrite = true) else settingsFile.parentFile.mkdirs()
            settings.add("statusLine", com.google.gson.JsonObject().apply { addProperty("type", "command"); addProperty("command", "sh \"$script\"") })
            settingsFile.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(settings) + "\n")
            service.log("claude usage: status line installed ($script); backup at $settingsFile.herdr-backup")
            service.notify("Done. Claude plan limits appear after the next Claude reply.", NotificationType.INFORMATION)
            service.pollUsageNow()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Couldn't set up the status line: ${e.message}", "Herdr")
        }
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
