// Per-project wiring and the sync loop. Connection states: connecting -> connected -> reconnecting (keep the
// last view greyed for a few seconds) -> down (diagnosed; retry with backoff 1.5s -> 10s). Events are only
// invalidation signals: every change is re-read with session.snapshot (what Herdr's docs recommend).
// All Herdr I/O runs on one background thread (`loop`), UI work on the EDT.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val RECONNECT_GRACE_MS = 4000L
private const val MANAGED_KEY = "herdr.cockpit.managed"
private const val MOUNTED_KEY = "herdr.cockpit.mountedRoots"

@Service(Service.Level.PROJECT)
class HerdrService(private val project: Project) : Disposable {
    companion object {
        fun getInstance(project: Project): HerdrService = project.service()
        private val LOG = Logger.getInstance(HerdrService::class.java)
    }

    private sealed class Conn(val kind: String) {
        object Connecting : Conn("connecting")
        object Starting : Conn("starting")
        class Connected(val version: String?, val protocol: Int?) : Conn("connected")
        class Reconnecting(val since: Long) : Conn("reconnecting")
        class Down(val d: Diagnosis) : Conn("down")
    }

    private val settings get() = HerdrSettings.get()
    private val loop = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "herdr-loop").apply { isDaemon = true } }
    private val activityExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "herdr-activity-loop").apply { isDaemon = true } }
    private val client = HerdrClient { socketPath() }
    val actions = HerdrActions(client)
    private val manage = HerdrManage(project, this)
    private val activity = ActivityWatcher(client)
    private val git = GitCache { scheduleRender() }
    private val logLines = ArrayDeque<String>()

    @Volatile private var model: Model? = null
    @Volatile private var conn: Conn = Conn.Connecting
    private var failures = 0
    private var lastDiagnosis = 0L
    private var nextAttempt = 0L
    private var lastRefresh = 0L
    private var sub: Subscription? = null
    private var subscribedPanes = ""
    @Volatile private var eventsLive = false
    private var refreshTimer: ScheduledFuture<*>? = null
    private var renderTimer: ScheduledFuture<*>? = null
    private var firstLoad = true
    private var lastFocusedSpace: String? = null
    @Volatile private var switching = false
    private val lastStatus = HashMap<String, AgentStatus?>()
    private val stateSince = ConcurrentHashMap<String, Pair<AgentStatus?, Long>>()
    private val paneNames = HashMap<String, String>()
    /** Open (or once opened) pane tabs, by pane id. */
    private val files = ConcurrentHashMap<String, HerdrPaneFile>()

    /** The project (or the IDE) is closing: its tabs close then, but that only detaches. */
    @Volatile private var closing = false

    @Volatile private var panelVisible = false
    private val listeners = mutableListOf<(String) -> Unit>()
    @Volatile private var lastState: String? = null

    init {
        loop.scheduleWithFixedDelay({ guard { tick() } }, 0, 1500, TimeUnit.MILLISECONDS)
        activityExec.scheduleWithFixedDelay({ guard { pollActivity() } }, 2, 4, TimeUnit.SECONDS)
        loop.scheduleWithFixedDelay({ guard { if (model != null) render() } }, 15, 15, TimeUnit.SECONDS)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosing(p: Project) { if (p == project) closing = true }
        })
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) { if (file is HerdrPaneFile) closedTab(file) }
        })
    }

    /** A pane's tab closed. The plugin's own closes drop it from `files` first; anything else was you. */
    private fun closedTab(f: HerdrPaneFile) {
        val mode = settings.closeTabInHerdr ?: "never"
        if (mode == "never" || closing || files[f.paneId] !== f || f.ended || !isConnected()) return
        val (space, pane) = model?.find(f.paneId) ?: return
        loop.execute { guard { manage.closedTabByUser(space, pane, mode) } }
    }

    private fun guard(f: () -> Unit) = try { f() } catch (e: Throwable) { LOG.warn(e) }

    // ---------- configuration ----------
    fun binary(): String = resolveBinary(settings.binaryPath)
    private fun customSocket(): String? = settings.socketPath?.takeIf { it.isNotBlank() }
    fun socketPath(): String = resolveSocketPath(customSocket())
    fun herdrEnvNow(): Map<String, String> = herdrEnv(customSocket()?.let { resolveSocketPath(it) })

    fun log(line: String) {
        LOG.info(line)
        synchronized(logLines) {
            logLines.addLast("${java.time.LocalTime.now().withNano(0)} $line")
            while (logLines.size > 200) logLines.removeFirst()
        }
    }

    fun currentModel(): Model? = model
    fun isConnected(): Boolean = conn is Conn.Connected

    /** The current git branch of a space's folder (cached; none in untrusted projects). */
    fun branchOf(cwd: String?): String? = if (TrustedProjects.isProjectTrusted(project)) git.get(cwd)?.branch else null

    /** Re-reads the snapshot until a pane Herdr just created shows up (normally right away). Not on the EDT. */
    fun awaitPane(paneId: String): Pair<Space, Pane>? {
        repeat(20) { i ->
            runCatching { normalize(client.request("session.snapshot")).find(paneId) }.getOrNull()?.let {
                scheduleRefresh(0)
                return it
            }
            if (i < 19) Thread.sleep(150)
        }
        return null
    }

    fun settingsChanged() = loop.execute { guard { refresh(); render() } }

    // ---------- sync loop ----------
    private fun tick() {
        val now = System.currentTimeMillis()
        when (conn) {
            is Conn.Connected -> {
                // Without an event stream poll every tick; with one, a safety net every 5s (pane renames emit no event).
                if (!eventsLive || now - lastRefresh > 5000) refresh()
                if (sub == null) startEvents()
            }
            is Conn.Starting -> {}
            else -> if (now >= nextAttempt) refresh()
        }
    }

    fun scheduleRefresh(delayMs: Long = 120) {
        refreshTimer?.cancel(false)
        refreshTimer = loop.schedule({ guard { refresh() } }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        lastRefresh = System.currentTimeMillis()
        try {
            val res = client.request("session.snapshot")
            val next = normalize(res)
            val wasConnected = conn is Conn.Connected
            conn = Conn.Connected(next.version, next.protocol)
            failures = 0
            apply(next)
            if (!wasConnected) log("connected to herdr ${next.version ?: ""}".trim())
            // Panes came or went: move the live subscription to the new set.
            if (sub != null && next.paneIds().sorted().joinToString(" ") != subscribedPanes) startEvents()
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    private fun onFailure(e: Exception) {
        failures++
        val now = System.currentTimeMillis()
        nextAttempt = now + backoffDelay(failures)
        val c = conn
        if (c is Conn.Connected) {
            log("lost herdr: ${e.message}")
            conn = Conn.Reconnecting(now)
            render()
            return
        }
        if (c is Conn.Starting) return
        if (c is Conn.Reconnecting && now - c.since < RECONNECT_GRACE_MS) return
        // Diagnosing runs `herdr status`; while it stays down, do that at most every 10s.
        if (c is Conn.Down && now - lastDiagnosis < 10_000) return
        lastDiagnosis = now
        val before = (c as? Conn.Down)?.d?.reason
        model = null
        val d = diagnose(binary(), socketPath(), customSocket() != null, herdrEnvNow(), e)
        conn = Conn.Down(d)
        if (d.reason != before) log("herdr unavailable: ${d.reason.id}${d.detail?.let { " ($it)" } ?: ""}")
        if (c is Conn.Reconnecting) notify("Lost the connection to Herdr${d.detail?.let { ": $it" } ?: ""}.", NotificationType.WARNING)
        render()
    }

    private fun startEvents() {
        val panes = model?.paneIds() ?: emptyList()
        val key = panes.sorted().joinToString(" ")
        if (sub != null && key != subscribedPanes) {
            sub?.dispose() // dispose() doesn't call onEnd
            sub = null
        }
        if (sub != null || conn !is Conn.Connected) return
        subscribedPanes = key
        var self: Subscription? = null
        self = client.subscribe(subscriptionsFor(panes), { loop.execute { scheduleRefresh() } }) { err ->
            loop.execute {
                if (sub !== self) return@execute
                sub = null
                if (eventsLive || err != null) log("event stream ended${err?.let { ": ${it.message}" } ?: ""}; polling")
                eventsLive = false
                scheduleRefresh()
            }
        }
        sub = self
        eventsLive = true
    }

    // ---------- applying a snapshot ----------
    private fun apply(next: Model) {
        val prev = model
        model = next
        // Renamed in Herdr: rename its tab here too.
        for (sp in next.spaces) for (tab in sp.tabs) for (p in tab.panes) {
            val n = paneName(tab, p)
            val was = paneNames[p.id]
            if (was != null && was != n) files[p.id]?.let { f -> f.title = n; edt { FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(f) } }
            paneNames[p.id] = n
        }

        // Notifications on agent transitions.
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        for (sp in next.spaces) for (a in sp.agents) {
            seen += a.id
            val since = stateSince[a.id]
            if (since == null || since.first != a.status) stateSince[a.id] = a.status to now
            val before = lastStatus[a.id]
            if (!firstLoad && before != null && a.status != null && before != a.status) notifyTransition(sp, a)
            lastStatus[a.id] = a.status
        }
        lastStatus.keys.retainAll(seen)
        stateSince.keys.retainAll(seen)

        // Close tabs whose panes no longer exist.
        val live = next.paneIds().toSet()
        if (live.isNotEmpty()) for ((id, f) in files) if (id !in live) {
            files.remove(id)
            edt { FileEditorManager.getInstance(project).closeFile(f) }
        }

        // Follow mode: Herdr's focus moved (e.g. you switched spaces in your terminal).
        val focused = next.focusedSpaceId
        if (!firstLoad && prev != null && focused != null && focused != lastFocusedSpace && !switching && settings.followMode && isManaged()) {
            next.space(focused)?.let { sp ->
                log("follow: herdr focused ${sp.label}")
                edt { switchTo(sp, fromHerdr = true) }
            }
        }
        lastFocusedSpace = focused
        firstLoad = false
        render()
        val shape = { m: Model? -> m?.spaces?.flatMap { s -> s.agents.map { "${it.id}=${it.status}" } + s.panes.map { it.id } }?.joinToString() }
        if (shape(prev) != shape(next)) activityExec.execute { guard { pollActivity() } }
    }

    private fun notifyTransition(space: Space, agent: Pane) {
        val st = agent.status ?: return
        if (st == AgentStatus.blocked && !settings.notifyBlocked) return
        if (st == AgentStatus.done && !settings.notifyDone) return
        if (st != AgentStatus.blocked && st != AgentStatus.done) return
        val name = paneName(space.tabs.firstOrNull { it.id == agent.tabId }, agent)
        val text = if (st == AgentStatus.blocked) "$name in ${space.label} needs you" else "$name in ${space.label} is done"
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit")
                .createNotification(text, if (st == AgentStatus.blocked) NotificationType.WARNING else NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring("Show") { model?.find(agent.id)?.let { (s, p) -> attachPane(s, p) } })
                .notify(project)
        }
    }

    private fun pollActivity() {
        val m = model ?: return
        if (!panelVisible) return
        val targets = m.spaces.flatMap { sp -> sp.panes.filter { it.isAgent || settings.showShells }.map { ActivityTarget(it.id, it.isAgent, it.status) } }
        if (activity.poll(targets)) scheduleRender()
    }

    // ---------- sidebar ----------
    fun addStateListener(parent: Disposable, l: (String) -> Unit) {
        synchronized(listeners) { listeners += l }
        com.intellij.openapi.util.Disposer.register(parent) { synchronized(listeners) { listeners -= l } }
        lastState?.let(l)
        scheduleRender()
    }

    fun setPanelVisible(v: Boolean) {
        val was = panelVisible
        panelVisible = v
        if (v && !was) activityExec.execute { guard { pollActivity() } }
    }

    fun scheduleRender() {
        renderTimer?.cancel(false)
        renderTimer = loop.schedule({ guard { render() } }, 50, TimeUnit.MILLISECONDS)
    }

    private fun connectionJson(): JsonObject = JsonObject().apply {
        val c = conn
        addProperty("kind", c.kind)
        addProperty("expectedProtocol", HERDR_PROTOCOL)
        when (c) {
            is Conn.Connected -> { c.version?.let { addProperty("version", it) }; c.protocol?.let { addProperty("protocol", it) } }
            is Conn.Down -> c.d.let { d ->
                addProperty("reason", d.reason.id)
                addProperty("socket", d.socket)
                addProperty("customSocket", d.customSocket)
                addProperty("binary", d.binary)
                d.detail?.let { addProperty("detail", it) }
                d.version?.let { addProperty("version", it) }
                d.protocol?.let { addProperty("protocol", it) }
            }
            else -> {}
        }
    }

    private fun render() {
        val trusted = TrustedProjects.isProjectTrusted(project)
        val state = buildViewState(
            ViewInputs(
                model = model,
                showShells = settings.showShells,
                since = { stateSince[it]?.second },
                git = { if (trusted) git.get(it) else null },
                mounted = ::isMounted,
                attached = { files[it]?.connector != null },
                activity = activity::get,
                connection = connectionJson(),
            ),
        ).toString()
        if (state == lastState) return
        lastState = state
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { it(state) }
    }

    // ---------- mounting a space as a content root ----------
    private fun props() = PropertiesComponent.getInstance(project)
    private fun isManaged() = props().getBoolean(MANAGED_KEY, false)
    private fun mountedRoots(): List<String> = props().getList(MOUNTED_KEY) ?: emptyList()

    private fun isMounted(space: Space): Boolean {
        val cwd = space.cwd ?: return false
        return ProjectRootManager.getInstance(project).contentRoots.any { root -> cwd == root.path || cwd.startsWith(root.path + "/") }
    }

    /**
     * Adds the space's folder as a content root of the project's first module and removes the root this plugin
     * added for the previous space. Never touches a content root the plugin didn't add.
     */
    private fun mount(space: Space): Boolean {
        val cwd = space.cwd ?: return false.also { notify("No working directory known for space \"${space.label}\".", NotificationType.WARNING) }
        if (isMounted(space)) return true
        val module = ModuleManager.getInstance(project).modules.firstOrNull()
            ?: return false.also { notify("This project has no module to add ${space.label}'s folder to.", NotificationType.WARNING) }
        val dir = LocalFileSystem.getInstance().findFileByPath(cwd) ?: run {
            // A folder the IDE hasn't seen yet (e.g. a new worktree): load it off the EDT, then try again.
            ApplicationManager.getApplication().executeOnPooledThread {
                if (LocalFileSystem.getInstance().refreshAndFindFileByPath(cwd) != null) edt { mount(space) }
            }
            return false
        }
        val ours = mountedRoots()
        ModuleRootModificationUtil.updateModel(module) { m ->
            for (e in m.contentEntries) if (e.url in ours && e.file?.path != cwd) m.removeContentEntry(e)
            m.addContentEntry(dir)
        }
        props().setList(MOUNTED_KEY, listOf(dir.url))
        return true
    }

    // ---------- actions ----------
    fun switchTo(space: Space, fromHerdr: Boolean = false, autoAttach: Boolean = settings.autoAttachOnSwitch) {
        switching = true
        try {
            props().setValue(MANAGED_KEY, true)
            if (settings.mountOnSwitch && mount(space)) {
                space.cwd?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let { ProjectView.getInstance(project).select(null, it, false) }
            }
            if (!fromHerdr) {
                loop.execute { guard { client.request("workspace.focus", JsonObject().apply { addProperty("workspace_id", space.id) }) } }
                lastFocusedSpace = space.id // don't let follow mode bounce us
            }
            if (settings.closeTerminalsOnSwitch) {
                val fem = FileEditorManager.getInstance(project)
                for ((id, f) in files) if (f.spaceId != space.id) { files.remove(id); fem.closeFile(f) }
            }
            if (autoAttach) {
                val panes = attachOrder(space, settings.autoAttachShells)
                for (p in panes) openTab(space, p, focus = false)
                panes.firstOrNull()?.let { openTab(space, it, focus = true) }
            }
        } finally {
            switching = false
            scheduleRender()
        }
    }

    fun attachPane(space: Space, pane: Pane) {
        if (files[pane.id]?.connector == null && settings.mountOnSwitch && !isMounted(space) && space.cwd != null) switchTo(space, autoAttach = false)
        openTab(space, pane, focus = true)
        if (pane.isAgent) loop.execute { runCatching { client.request("agent.focus", JsonObject().apply { addProperty("target", pane.id) }) } }
    }

    private fun openTab(space: Space, pane: Pane, focus: Boolean) {
        val fem = FileEditorManager.getInstance(project)
        var f = files[pane.id]
        if (f != null && (f.ended || !fem.isFileOpen(f))) {
            files.remove(pane.id)
            fem.closeFile(f)
            f = null
        }
        if (f == null) {
            f = HerdrPaneFile(pane.id, space.id, paneName(space.tabs.firstOrNull { it.id == pane.tabId }, pane), pane.isAgent)
            files[pane.id] = f
        }
        fem.openFile(f, focus)
        scheduleRender()
    }

    /** A command from the sidebar (media/panel.js data-act values). Runs on the EDT. */
    fun handle(command: String, spaceId: String?, paneId: String?) {
        val m = model
        when (command) {
            "switch" -> m?.space(spaceId)?.let { switchTo(it) }
            "attach" -> m?.find(paneId)?.let { (s, p) -> attachPane(s, p) }
            "attention" -> m?.spaces?.flatMap { s -> s.agents.map { s to it } }
                ?.filter { it.second.status == AgentStatus.blocked || it.second.status == AgentStatus.done }
                ?.minByOrNull { (it.second.status ?: AgentStatus.unknown).urgency }
                ?.let { (s, p) -> attachPane(s, p) }
            "refresh" -> { nextAttempt = 0; lastDiagnosis = 0; loop.execute { guard { refresh() } } }
            "toggleShellPanes" -> { settings.showShells = !settings.showShells; scheduleRender() }
            "startServer" -> startHerdr()
            "openInstallDocs" -> BrowserUtil.browse("https://herdr.dev")
            "openSettings" -> ShowSettingsUtil.getInstance().showSettingsDialog(project, HerdrConfigurable::class.java)
            "copyDiagnostics" -> copyDiagnostics()
            "newSpace" -> manage.newSpace()
            "newTab" -> m?.space(spaceId)?.let { manage.newTab(it) }
            "menu" -> m?.space(spaceId)?.let { manage.menu(it, it.panes.firstOrNull { p -> p.id == paneId }) }
            "openTui" -> notify("Run <code>herdr</code> in a terminal to open the Herdr TUI.", NotificationType.INFORMATION)
            else -> notify("\"$command\" isn't available in the JetBrains port yet.", NotificationType.INFORMATION)
        }
    }

    fun startHerdr() {
        conn = Conn.Starting
        scheduleRender()
        loop.execute {
            guard {
                val logFile = File(System.getProperty("user.home"), ".herdr-cockpit/herdr-server.log")
                val r = startServer(binary(), herdrEnvNow(), logFile)
                log(if (r.ok) (if (r.already) "herdr was already running" else "started herdr server") else "start failed: ${r.error}")
                conn = Conn.Connecting
                nextAttempt = 0
                lastDiagnosis = 0
                if (!r.ok) notify("Couldn't start Herdr: ${r.error}", NotificationType.ERROR)
                refresh()
            }
        }
    }

    private fun copyDiagnostics() {
        val text = buildString {
            appendLine("Herdr Cockpit (JetBrains) diagnostics")
            appendLine("connection: ${conn.kind}")
            appendLine("binary: ${binary()}")
            appendLine("socket: ${socketPath()}")
            appendLine("events live: $eventsLive")
            appendLine("spaces: ${model?.spaces?.size ?: 0}, panes: ${model?.paneIds()?.size ?: 0}")
            appendLine("--- log ---")
            synchronized(logLines) { logLines.forEach { appendLine(it) } }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify("Diagnostics copied to the clipboard.", NotificationType.INFORMATION)
    }

    /** Space picker for the "Switch Space" action. */
    fun pickSpace() {
        val spaces = model?.spaces ?: return notify("Not connected to Herdr.", NotificationType.WARNING)
        if (spaces.isEmpty()) return notify("Herdr has no spaces yet.", NotificationType.INFORMATION)
        JBPopupFactory.getInstance().createPopupChooserBuilder(spaces)
            .setTitle("Switch to Herdr Space")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<Space>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out Space>, value: Space?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value == null) return
                    append(value.label)
                    listOfNotNull(value.status?.name, value.cwd).forEach { append("  ·  $it", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
            })
            .setItemChosenCallback { switchTo(it) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    fun showToolWindow() = ToolWindowManager.getInstance(project).getToolWindow("Herdr")?.show()

    fun notify(html: String, type: NotificationType) = ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit").createNotification(html, type).notify(project)
    }

    private fun edt(f: () -> Unit) = ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) f() }, project.disposed)

    override fun dispose() {
        sub?.dispose()
        loop.shutdownNow()
        activityExec.shutdownNow()
        activity.shutdown()
        git.shutdown()
        for (f in files.values) f.connector?.close()
    }
}
