// Reviews from the IDE: uncommitted changes as diffs, and agent reviews (a branch, or a merge request's changes) whose
// findings can go to GitLab, GitHub or Gitea, unpublished. Needs from the service only what ReviewHost offers.
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
import com.intellij.ide.BrowserUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckBoxList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a review needs from HerdrService: the socket calls, the current snapshot, opening a pane, the log. */
interface ReviewHost {
    val actions: HerdrActions
    fun isConnected(): Boolean
    fun currentModel(): Model?
    fun showPane(paneId: String)
    fun log(line: String)
}

/** How long an agent review is followed before it's given up on. */
private const val REVIEW_TIMEOUT_MS = 2 * 60 * 60 * 1000L
/** The states that end a wait: finished, or waiting for you. */
private val SETTLED = setOf(AgentStatus.idle, AgentStatus.done, AgentStatus.blocked)

class ReviewFlow(project: Project, private val host: ReviewHost) : ProjectUi(project, host::log) {
    private val actions get() = host.actions

    /** "Review": every uncommitted change in the repo of the pane (or space) folder, as HEAD ↔ working tree diffs. */
    fun review(space: Space, pane: Pane?) {
        val cwd = pane?.cwd ?: space.cwd ?: return notify("No folder known for ${space.label}.", NotificationType.WARNING)
        // Git runs programs from the repo's config in some setups; GitInfo hardens that, but untrusted projects skip git entirely.
        if (!TrustedProjects.isProjectTrusted(project)) return notify("Review needs a trusted project (git isn't run in untrusted ones).", NotificationType.WARNING)
        run("Review ${pane?.let { paneName(space.tabs.firstOrNull { t -> t.id == it.tabId }, it) } ?: space.label}") {
            val root = gitRoot(cwd) ?: return@run notify("$cwd is not inside a git repository.", NotificationType.INFORMATION)
            val changes = gitChanges(root) ?: throw IllegalStateException("git status failed in $root")
            if (changes.isEmpty()) return@run notify("No uncommitted changes in ${File(root).name}.", NotificationType.INFORMATION)
            val chain = SimpleDiffRequestChain.fromProducers(changes.map { diffOf(root, it) })
            later { DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT) }
        }
    }

    /** A review an agent owes: where it writes, and the branch (if any) and commit it reviews, to find the merge request. `forge` is known when it was started from that forge's tool window. */
    private class PendingReview(val root: String, val out: File, val since: Long, val branch: String?, val head: String?, val forge: ForgeProvider? = null)
    /** Panes whose agent is doing a review; its "needs you" and "done" notifications come from the review. */
    private val watched = ConcurrentHashMap.newKeySet<String>()

    fun isReviewing(paneId: String) = paneId in watched
    /** The forge found per host; unknown ones are asked again next time. */
    private val found = ConcurrentHashMap<String, ForgeProvider>()

    /** Asks the agent to review its folder's branch against a target and write findings to a file; nothing is posted. */
    fun reviewBranch(space: Space, pane: Pane) {
        val cwd = pane.cwd ?: space.cwd ?: return notify("No folder known for ${space.label}.", NotificationType.WARNING)
        if (!TrustedProjects.isProjectTrusted(project)) return notify("Review needs a trusted project (git isn't run in untrusted ones).", NotificationType.WARNING)
        val name = editorTitle(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane)
        run("Review branch with $name") {
            val root = gitRoot(cwd) ?: return@run notify("$cwd is not inside a git repository.", NotificationType.INFORMATION)
            val branch = gitBranch(root) ?: return@run notify("${File(root).name} is not on a branch.", NotificationType.INFORMATION)
            val default = gitDefaultBranch(root) ?: "main"
            val head = gitHead(root)
            later {
                val target = input("Review $branch with $name", "Compare $branch against (branch or ref):", default) {
                    if (!isValidBranch(it)) "Not a valid branch name" else null
                } ?: return@later
                val out = reviewFile(File(System.getProperty("user.home")), space.label, branch)
                val (extra, template) = reviewInstructions(name, BRANCH_TEMPLATE, DEFAULT_BRANCH_TEMPLATE) ?: return@later
                startReview(name, pane.id, PendingReview(root, out, System.currentTimeMillis(), branch, head)) {
                    reviewPrompt(root, branch, target, out, extra, gitChangedFiles(root, target).orEmpty(), template)
                }
            }
        }
    }

    /** A merge request's changes (GitLab tool window): pick an agent, it reviews `base`..`head` for those files. */
    fun reviewChanges(files: List<String>, base: String, head: String) {
        if (!host.isConnected()) return notify("Not connected to Herdr.", NotificationType.WARNING)
        if (!TrustedProjects.isProjectTrusted(project)) return notify("Review needs a trusted project (git isn't run in untrusted ones).", NotificationType.WARNING)
        run("Review with agent") {
            // A file new in the merge request may not exist here: find the repo from its nearest existing folder.
            val dir = generateSequence(File(files.first())) { it.parentFile }.firstOrNull { it.isDirectory }?.path
            val root = dir?.let(::gitRoot) ?: return@run notify("${files.first()} is not inside a git repository.", NotificationType.INFORMATION)
            val paths = files.mapNotNull { f -> File(f).relativeTo(File(root)).path.takeUnless { it.startsWith("..") } }.distinct()
            later {
                val targets = host.currentModel()?.spaces.orEmpty().flatMap { s -> s.agents.map { s to it } }
                    .sortedByDescending { (s, a) -> (a.cwd ?: s.cwd)?.let { it == root || it.startsWith("$root/") } == true }
                if (targets.isEmpty()) return@later notify("No agent in Herdr to review this.", NotificationType.INFORMATION)
                choose("Review ${paths.size} file${if (paths.size > 1) "s" else ""} with", targets.map { (s, a) ->
                    val name = editorTitle(s, s.tabs.firstOrNull { it.id == a.tabId }, a)
                    Item("$name (${a.status ?: AgentStatus.unknown})", null) {
                        val out = reviewFile(File(System.getProperty("user.home")), s.label, "mr-" + head.take(12))
                        val (extra, template) = reviewInstructions(name, CHANGES_TEMPLATE, DEFAULT_CHANGES_TEMPLATE) ?: return@Item
                        startReview(name, a.id, PendingReview(root, out, System.currentTimeMillis(), null, head, forgeNamed("GitLab"))) {
                            reviewChangesPrompt(root, base, head, paths, out, extra, template)
                        }
                    }
                })
            }
        }
    }

    /** Optional extra instructions ("" when left empty) and the template to send them with; null when cancelled. */
    private fun reviewInstructions(name: String, templateCategory: String, default: String): Pair<String, String>? =
        promptDialog("Review with $name", "Anything $name should look at in particular? (optional)", "review", "", null,
            reviewTemplates(project, templateCategory, default), templateCategory)

    /** Sends the prompt `text` builds (in the background: it may run git), then follows the agent on its own thread. */
    private fun startReview(name: String, paneId: String, review: PendingReview, text: () -> String) = run("Review with $name") {
        if (!watched.add(paneId)) return@run notify("$name is already doing a review.", NotificationType.INFORMATION)
        try {
            val prompt = text()
            review.out.parentFile.mkdirs()
            review.out.delete()
            ApplicationManager.getApplication().executeOnPooledThread {
                try { follow(name, paneId, review, prompt) } finally { watched.remove(paneId) }
            }
        } catch (e: Exception) {
            watched.remove(paneId)
            throw e
        }
    }

    private fun answerAction(name: String, paneId: String) = "Answer…" to { answerAgent(project, actions, name, paneId) }
    private fun showAction(paneId: String) = "Show" to { host.showPane(paneId) }

    /**
     * Prompts the agent and follows it until it settles: its findings when it wrote the file, a note when it didn't,
     * and a notification to answer whenever it waits at a question. Blocks a pooled thread; closing the project ends
     * the wait (the socket closes).
     */
    private fun follow(name: String, paneId: String, r: PendingReview, prompt: String) {
        val deadline = System.currentTimeMillis() + REVIEW_TIMEOUT_MS
        fun left() = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
        try {
            var status = actions.promptAndWait(paneId, prompt, SETTLED, left())
            while (status == AgentStatus.blocked) {
                notify("$name needs an answer to go on with the review.", NotificationType.WARNING, answerAction(name, paneId), showAction(paneId))
                // Answered (here or in Herdr), then on to the next stop.
                actions.waitAgent(paneId, SETTLED - AgentStatus.blocked + AgentStatus.working, left())
                status = actions.waitAgent(paneId, SETTLED, left())
            }
            settled(name, paneId, r)
        } catch (e: HerdrError) {
            when (e.code) {
                "agent_blocked" -> notify("$name is waiting for an answer; the review wasn't sent.", NotificationType.WARNING, answerAction(name, paneId), showAction(paneId))
                "agent_not_found", "agent_not_running" -> notify("$name is no longer running in that pane; the review ended.", NotificationType.WARNING)
                "timeout" -> notify("$name hasn't finished the review in ${REVIEW_TIMEOUT_MS / 3_600_000} hours; it's no longer followed.", NotificationType.WARNING, showAction(paneId))
                else -> notify("$name's review: ${e.message}", NotificationType.WARNING)
            }
        } catch (e: Exception) {
            host.log("review by $name: ${e.message}") // the socket closed: the project is closing or Herdr went away
        }
    }

    /** The agent finished: offer its findings, or say why there are none. Pooled thread. */
    private fun settled(name: String, paneId: String, r: PendingReview) {
        if (!r.out.isFile || r.out.lastModified() < r.since)
            return notify("$name finished without writing the review to ${r.out}.", NotificationType.WARNING, showAction(paneId))
        val findings = parseReview(r.out.readText(), File(r.root))
            ?: return notify("$name's review in ${r.out} isn't a JSON array of findings.", NotificationType.WARNING)
        if (findings.isEmpty()) return notify("$name's review: no findings.", NotificationType.INFORMATION)
        val remote = gitRemoteUrl(r.root)?.let(::remoteRepo)
        val target = remote?.let(::target)
        if (remote != null) host.log("review: ${remote.host} is ${target?.forge?.title ?: "an unknown kind of server"}")
        offerFindings(name, r, findings, target?.takeIf { r.forge == null || it.forge.title == r.forge.title })
    }

    /** Where a review can go: the forge, the repository on it (a subpath install's prefix taken off) and your server entry. */
    private class Target(val forge: ForgeProvider, val repo: RemoteRepo, val server: ReviewServer?) {
        val send get() = "Send to ${forge.title} as ${forge.pending}…"
    }

    /** The forge of `remote`: your server entry, else what was found before, else detection. Background thread. */
    private fun target(remote: RemoteRepo): Target? {
        val server = HerdrSettings.serverFor(remote.host)
        val forge = server?.forge ?: found[remote.host] ?: forges().let { all -> detectForge(remote.host, foundClis(all), all) }?.also { found[remote.host] = it }
        val repo = server?.prefix?.takeIf { it.isNotEmpty() && remote.path.startsWith("$it/") }?.let { remote.copy(path = remote.path.removePrefix("$it/")) } ?: remote
        return forge?.let { Target(it, repo, server) }
    }

    private fun offerFindings(name: String, r: PendingReview, findings: List<Finding>, target: Target?) {
        later {
            val n = NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit")
                .createNotification("$name's review: ${findings.size} finding${if (findings.size > 1) "s" else ""}", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple("Show") { showFindings(name, r, findings, target) })
            if (target != null) n.addAction(NotificationAction.createSimple(target.send) { sendReview(r, target, findings) })
            n.notify(project)
        }
    }

    private fun showFindings(name: String, r: PendingReview, findings: List<Finding>, target: Target?) {
        val send = listOfNotNull(target?.let { Item(it.send, AllIcons.Actions.Upload) { sendReview(r, it, findings) } })
        choose("Review by $name", send + findings.mapIndexed { i, f ->
            Item("${f.path}:${f.line}  ${f.body.lineSequence().first().take(120)}", null, if (i == 0 && send.isNotEmpty()) "Findings" else null) {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("${r.root}/${f.path}")
                    ?: return@Item notify("${f.path} doesn't exist (any more).", NotificationType.WARNING)
                OpenFileDescriptor(project, vf, f.line - 1, 0).navigate(true)
                notify("${f.path}:${f.line}: ${f.body}", NotificationType.INFORMATION)
            }
        })
    }

    /** Findings as unpublished comments on the merge/pull request: signs in as the server entry says (Review servers). */
    private fun sendReview(r: PendingReview, t: Target, findings: List<Finding>): Unit = run("Send review to ${t.forge.title}") {
        when (val reach = reach(t.forge, t.repo.host, t.server)) {
            is Reach.Ready -> prepareReview(t.forge.review(reach.api, t.repo), reach, r, t, findings)
            is Reach.Fail -> notify(reach.message, NotificationType.WARNING)
            is Reach.NeedToken -> later {
                val entered = Messages.showPasswordDialog(project,
                    "No ${t.forge.cli} signed in to ${t.repo.host}${if (t.forge.ideAccounts != null) " and no ${t.forge.title} account in the IDE" else ""}. " +
                        "Set the server up under Settings | Tools | Herdr Cockpit | Review servers, or enter an access token here " +
                        "(kept in the IDE's password safe):",
                    "${t.forge.title} Token for ${t.repo.host}", null)?.trim()?.ifEmpty { null } ?: return@later
                ReviewTokens.set(reach.web, entered)
                sendReview(r, t, findings)
            }
        }
    }

    /** Finds the merge/pull request (asks when several fit), then shows which findings would go there. Background thread. */
    private fun prepareReview(review: ForgeReview, reach: Reach.Ready, r: PendingReview, t: Target, findings: List<Finding>) {
        val candidates = forgetRejected(reach, t) { review.candidates(r.branch, r.head) }
        when (candidates.size) {
            0 -> notify("No open ${t.forge.request} on ${t.repo.host} for ${r.branch ?: "these changes"}.", NotificationType.INFORMATION)
            1 -> previewFor(review, reach, r, t, findings, candidates[0].number)
            else -> later {
                choose("Which ${t.forge.request.replaceFirstChar { it.uppercase() }}?", candidates.map { c ->
                    Item("${t.forge.sign}${c.number} ${c.title}  (${c.source} → ${c.target})", null) {
                        run("Send review to ${t.forge.title}") { previewFor(review, reach, r, t, findings, c.number) }
                    }
                })
            }
        }
    }

    /** A token the forge rejects is forgotten, so the next try asks again. */
    private fun <T> forgetRejected(reach: Reach.Ready, t: Target, call: () -> T): T = try {
        call()
    } catch (e: ForgeError) {
        if (reach.ownToken && e.status == 401) ReviewTokens.set(t.server?.url?.trimEnd('/') ?: "https://${t.repo.host}", null)
        throw e
    }

    /** Loads the merge/pull request and shows which findings would go there. Background thread. */
    private fun previewFor(review: ForgeReview, reach: Reach.Ready, r: PendingReview, t: Target, findings: List<Finding>, number: Int) {
        val cr = forgetRejected(reach, t) { review.load(number) }
        val onChanged = findings.filter { it.path in cr.files }
        if (onChanged.isEmpty()) return notify("None of the findings is on a file ${t.forge.sign}$number changes.", NotificationType.INFORMATION)
        later { previewReview(review, reach, r, t, cr, onChanged, findings.size - onChanged.size) }
    }

    /** What would be sent, each finding with a checkbox; OK posts the checked ones, unpublished. */
    private fun previewReview(review: ForgeReview, reach: Reach.Ready, r: PendingReview, t: Target, cr: ChangeRequest,
                              findings: List<Finding>, left: Int) {
        val f = t.forge
        val ref = "${f.sign}${cr.number}"
        val list = CheckBoxList<Finding>().apply {
            findings.forEach { addItem(it, "${it.path}:${it.line}  ${it.body.lineSequence().first().take(120)}", true) }
        }
        val notes = listOfNotNull(
            "${f.sendsAs} $ref ${cr.title} (via ${reach.via}). Nothing is published: you submit the review on ${f.title}.",
            f.offDiff,
            if (left > 0) "$left finding${if (left > 1) "s" else ""} on files the ${f.request} doesn't change are left out." else null,
            if (r.head != null && r.head != cr.headSha) "The review saw ${r.head.take(8)}, the ${f.request} is at ${cr.headSha.take(8)}: lines may be off." else null,
        )
        val content = panel {
            notes.forEach { row { text(it, maxLineLength = 100) } }
            row { scrollCell(list).align(Align.FILL) }.resizableRow()
        }
        val dialog = object : DialogWrapper(project) {
            init { title = t.send.removeSuffix("…"); setOKButtonText("Send"); init() }
            override fun createCenterPanel() = content
        }
        if (!dialog.showAndGet()) return
        val chosen = findings.filter { list.isItemSelected(it) }
        if (chosen.isEmpty()) return
        run("Sending review to $ref") {
            val n = forgetRejected(reach, t) { review.send(cr, chosen) }
            later {
                NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit")
                    .createNotification("$n finding${if (n > 1) "s" else ""} on $ref, unpublished; submit the review on ${f.title} to publish.", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple("Open ${f.request.replaceFirstChar { it.uppercase() }}") { BrowserUtil.browse(cr.url) })
                    .notify(project)
            }
        }
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
}
