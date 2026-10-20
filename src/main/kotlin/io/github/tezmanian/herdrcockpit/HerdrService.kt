// Per-project wiring and the sync loop. Connection states: connecting -> connected -> reconnecting (keep the
// last view greyed for a few seconds) -> down (diagnosed; retry with backoff 1.5s -> 10s). Events are only
// invalidation signals: every change is re-read with session.snapshot (what Herdr's docs recommend).
// All Herdr I/O runs on one background thread (`loop`), UI work on the EDT.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
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
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
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
private const val ACTIVITY_KEY = "herdr.cockpit.spaceActivity"
private val PLUGIN_VERSION: String by lazy {
    (HerdrService::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version ?: "?"
}

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
    private val usageExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "herdr-usage-loop").apply { isDaemon = true } }
    private val client = HerdrClient { socketPath() }
    val actions = HerdrActions(client)
    private val manage = HerdrManage(project, this)
    fun sendToAgent(context: String, path: String?) = manage.sendToAgent(context, path)
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
    @Volatile private var usage = JsonArray()
    private var codexLive: JsonObject? = null
    private var codexLiveAt = 0L

    init {
        loop.execute { guard { claims.prune() } }
        loop.scheduleWithFixedDelay({ guard { tick() } }, 0, 1500, TimeUnit.MILLISECONDS)
        activityExec.scheduleWithFixedDelay({ guard { pollActivity() } }, 2, 4, TimeUnit.SECONDS)
        loop.scheduleWithFixedDelay({ guard { if (model != null) render() } }, 15, 15, TimeUnit.SECONDS)
        usageExec.scheduleWithFixedDelay({ guard { pollUsage() } }, 60, 60, TimeUnit.SECONDS)
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

    /** How many agents are blocked and done right now. */
    fun attention(): Pair<Int, Int> = model?.spaces?.flatMap { it.agents }.orEmpty()
        .let { a -> a.count { it.status == AgentStatus.blocked } to a.count { it.status == AgentStatus.done } }

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

    fun settingsChanged() {
        loop.execute { guard { refresh(); render() } }
        pollUsageNow()
    }

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
        // Renamed in Herdr (space or pane): rename its tab here too.
        for (sp in next.spaces) for (tab in sp.tabs) for (p in tab.panes) {
            val n = editorTitle(sp, tab, p)
            val was = paneNames[p.id]
            if (was != null && was != n) files[p.id]?.let { f -> f.title = n; edt { FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(f) } }
            paneNames[p.id] = n
        }
        paneNames.keys.retainAll(next.spaces.flatMap { it.panes.map { p -> p.id } }.toSet())

        // Notifications on agent transitions.
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        for (sp in next.spaces) for (a in sp.agents) {
            seen += a.id
            val since = stateSince[a.id]
            if (since == null || since.first != a.status) {
                stateSince[a.id] = a.status to now
                if (!firstLoad) touchSpace(sp.label) // an agent started, finished, needs you
            }
            val before = lastStatus[a.id]
            if (!firstLoad && before != null && a.status != null && before != a.status) notifyTransition(sp, a)
            lastStatus[a.id] = a.status
        }
        lastStatus.keys.retainAll(seen)
        // Focused in Herdr or switched to here: recently used.
        next.spaces.firstOrNull { it.focused }?.let { f ->
            if (if (firstLoad) f.label !in spaceActivity else prev?.spaces?.firstOrNull { it.focused }?.id != f.id) touchSpace(f.label)
        }
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
        if (runningKinds(prev) != runningKinds(next)) pollUsageNow()
    }

    // ---------- another client took a pane over ----------
    private val claims by lazy { Claims() } // lazy: init {} runs before this line
    private val me get() = PaneClaim(ApplicationNamesInfo.getInstance().fullProductName, "jetbrains", ProcessHandle.current().pid(), System.currentTimeMillis())

    fun paneHooks(paneId: String) = object : PaneHooks {
        override fun claim() = claims.write(socketPath(), paneId, me, this@HerdrService)
        override fun unclaim() = claims.remove(socketPath(), paneId, me.pid, this@HerdrService)
        override fun holder(since: Long) = claims.holder(socketPath(), paneId, me, this@HerdrService, since)
        override fun lost(who: String?) = tookOver(paneId, who)
    }

    private val takenOver = ConcurrentHashMap<String, String>()
    private var takenOverTimer: ScheduledFuture<*>? = null

    /** Say so once for the panes lost together (a space switch in another window takes all of them). */
    private fun tookOver(paneId: String, who: String?) {
        if (!settings.notifyOnTakeover) return
        takenOver[paneId] = who ?: "another Herdr client"
        takenOverTimer?.cancel(false)
        takenOverTimer = loop.schedule({ guard { showTakenOver() } }, 600, TimeUnit.MILLISECONDS)
    }

    private fun showTakenOver() {
        val lost = takenOver.toMap()
        takenOver.keys.removeAll(lost.keys)
        if (lost.isEmpty()) return
        val who = lost.values.toSet().singleOrNull() ?: "other Herdr clients"
        val names = lost.keys.map { files[it]?.title ?: it }
        val list = when {
            names.size == 1 -> names[0]
            names.size <= 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
            else -> names.take(2).joinToString(", ") + " and ${names.size - 2} more"
        }
        val one = names.size == 1
        val text = "${who.replaceFirstChar { it.uppercase() }} took over the Herdr ${if (one) "pane" else "panes"} $list. " +
            "${if (one) "Its tab shows it" else "Their tabs show them"} read-only here; type in a tab to take it back."
        edt {
            NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit").createNotification(text, NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Take Back") { lost.keys.forEach { files[it]?.connector?.takeBack() } })
                .addAction(NotificationAction.createSimpleExpiring("Don't Show Again") { settings.notifyOnTakeover = false })
                .notify(project)
        }
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
                .apply { if (st == AgentStatus.done) addAction(NotificationAction.createSimpleExpiring("Hand Off…") { model?.find(agent.id)?.let { (s, p) -> manage.handOff(s, p) } }) }
                .notify(project)
        }
    }

    private fun pollActivity() {
        val m = model ?: return
        if (!panelVisible) return
        val targets = m.spaces.flatMap { sp -> sp.panes.filter { it.isAgent || settings.showShells }.map { ActivityTarget(it.id, it.isAgent, it.status) } }
        if (activity.poll(targets)) scheduleRender()
    }

    private fun runningKinds(m: Model?) = m?.spaces?.flatMap { sp -> sp.agents.mapNotNull { it.agentKind?.lowercase() } }.orEmpty().toSet()

    /** Agents' plan usage from their local files: once a minute, only while the sidebar is visible, only for running agents. */
    private fun pollUsage() {
        val m = model ?: return
        if (!panelVisible) return
        val only = runningKinds(m)
        val home = System.getProperty("user.home")
        // Live Codex limits start a short-lived `codex app-server`: at most every 5 minutes.
        if ("codex" in only && System.currentTimeMillis() - codexLiveAt > 5 * 60_000) {
            codexLiveAt = System.currentTimeMillis()
            codexLive = codexLiveUsage(codexBinary(home))
            if (codexLive == null) log("usage: live Codex limits unavailable; using its session logs")
        }
        val next = readUsage(home, System.currentTimeMillis(), only, codexLive, settings.kiroMonthlyCredits)
        if (next != usage) {
            usage = next
            scheduleRender()
        }
    }

    fun pollUsageNow() = usageExec.execute { guard { pollUsage() } }

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
        if (v && !was) {
            activityExec.execute { guard { pollActivity() } }
            pollUsageNow()
        }
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
                usage = usage,
                order = settings.spaceOrder ?: "activity",
                lastActive = { spaceActivity[it.label] },
                versions = JsonObject().apply {
                    addProperty("cockpit", PLUGIN_VERSION)
                    model?.version?.let { addProperty("herdr", it) }
                },
            ),
        ).toString()
        if (state == lastState) return
        lastState = state
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { it(state) }
    }

    // ---------- mounting a space as a content root ----------
    private fun props() = PropertiesComponent.getInstance(project)

    /** When each space (by name: ids change across Herdr restarts) was last active; app-wide, kept across restarts. */
    private val spaceActivity: MutableMap<String, Long> by lazy {
        val saved = runCatching { com.google.gson.JsonParser.parseString(PropertiesComponent.getInstance().getValue(ACTIVITY_KEY, "{}")).asJsonObject }.getOrNull()
        java.util.concurrent.ConcurrentHashMap<String, Long>().apply { saved?.entrySet()?.forEach { (k, v) -> runCatching { put(k, v.asLong) } } }
    }

    private fun touchSpace(label: String) {
        spaceActivity[label] = System.currentTimeMillis()
        val keep = JsonObject().apply { spaceActivity.entries.sortedByDescending { it.value }.take(200).forEach { addProperty(it.key, it.value) } }
        PropertiesComponent.getInstance().setValue(ACTIVITY_KEY, keep.toString())
    }
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
        if (tooBroadToMount(cwd, System.getProperty("user.home"), project.basePath))
            return false.also { notify("Not adding $cwd to the project: it is your home folder or contains the project.", NotificationType.WARNING) }
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

    fun isOurMount(space: Space): Boolean = ourRoot(space) != null

    private fun ourRoot(space: Space) = space.cwd?.let { cwd ->
        val ours = mountedRoots()
        ModuleManager.getInstance(project).modules.firstNotNullOfOrNull { m ->
            ModuleRootManager.getInstance(m).contentEntries.firstOrNull { it.url in ours && it.file?.path == cwd }?.let { m to it.url }
        }
    }

    /** Removes the space's folder again, only if the plugin added it. */
    fun unmount(space: Space) {
        val (module, url) = ourRoot(space) ?: return
        ModuleRootModificationUtil.updateModel(module) { m -> m.contentEntries.filter { it.url == url }.forEach(m::removeContentEntry) }
        props().setList(MOUNTED_KEY, mountedRoots() - url)
        scheduleRender()
    }

    fun activityOf(paneId: String) = activity.get(paneId)

    // ---------- actions ----------
    fun switchTo(space: Space, fromHerdr: Boolean = false, autoAttach: Boolean = settings.autoAttachOnSwitch) {
        switching = true
        try {
            props().setValue(MANAGED_KEY, true)
            if (settings.mountOnSwitch && mount(space)) {
                space.cwd?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let { ProjectView.getInstance(project).select(null, it, false) }
            }
            if (!fromHerdr) {
                loop.execute { guard { actions.focusSpace(space.id) } }
                lastFocusedSpace = space.id // don't let follow mode bounce us
            }
            if (settings.closeTerminalsOnSwitch) {
                val fem = FileEditorManager.getInstance(project)
                for ((id, f) in files) if (f.spaceId != space.id) { files.remove(id); fem.closeFile(f) }
            }
            if (autoAttach) {
                val panes = attachOrder(space, settings.autoAttachShells)
                // Following Herdr: a pane another window holds stays there (shown read-only), it isn't taken.
                for (p in panes) openTab(space, p, focus = false, polite = fromHerdr)
                panes.firstOrNull()?.let { openTab(space, it, focus = true, polite = fromHerdr) }
            }
        } finally {
            switching = false
            scheduleRender()
        }
    }

    fun attachPane(space: Space, pane: Pane) {
        if (files[pane.id]?.connector == null && settings.mountOnSwitch && !isMounted(space) && space.cwd != null) switchTo(space, autoAttach = false)
        openTab(space, pane, focus = true)
        if (pane.isAgent) loop.execute { runCatching { actions.focusAgent(pane.id) } }
    }

    private fun openTab(space: Space, pane: Pane, focus: Boolean, polite: Boolean = false) {
        val fem = FileEditorManager.getInstance(project)
        var f = files[pane.id]
        if (f != null && (f.ended || !fem.isFileOpen(f))) {
            files.remove(pane.id)
            fem.closeFile(f)
            f = null
        }
        if (f == null) {
            f = HerdrPaneFile(pane.id, space.id, editorTitle(space, space.tabs.firstOrNull { it.id == pane.tabId }, pane), pane.isAgent).also { it.polite = polite }
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
            "stopServer" -> stopHerdr()
            "openInstallDocs" -> BrowserUtil.browse("https://herdr.dev")
            "openSettings" -> ShowSettingsUtil.getInstance().showSettingsDialog(project, HerdrConfigurable::class.java)
            "copyDiagnostics" -> copyDiagnostics()
            "newSpace" -> manage.newSpace()
            "newTab" -> m?.space(spaceId)?.let { manage.newTab(it) }
            "review" -> m?.space(spaceId)?.let { manage.review(it, it.panes.firstOrNull { p -> p.id == paneId }) }
            "menu" -> m?.space(spaceId)?.let { manage.menu(it, it.panes.firstOrNull { p -> p.id == paneId }) }
            "setupClaudeUsage" -> manage.setUpClaudeUsage()
            "setKiroCredits" -> manage.setKiroCredits()
            "openTui" -> openTui()
            "openHelp" -> openHelp()
            else -> notify("\"$command\" isn't available in the JetBrains port yet.", NotificationType.INFORMATION)
        }
    }

    private var tui: HerdrTuiFile? = null
    private var help: HerdrHelpFile? = null

    fun openHelp() {
        val fem = FileEditorManager.getInstance(project)
        fem.openFile(help?.takeIf { fem.isFileOpen(it) } ?: HerdrHelpFile().also { help = it }, true)
    }

    /** The Herdr TUI in an editor tab; it may start the server, so re-check the connection shortly after. */
    fun openTui() {
        if (!binaryFound(binary())) return notify("Herdr isn't installed (${binary()}).", NotificationType.WARNING)
        loop.execute {
            guard {
                // Started by the TUI itself, the server would put its first space in $HOME.
                if (startServer(binary(), herdrEnvNow(), serverLog()).ok) ensureProjectSpace(focus = true)
                edt {
                    val fem = FileEditorManager.getInstance(project)
                    fem.openFile(tui?.takeIf { fem.isFileOpen(it) } ?: HerdrTuiFile().also { tui = it }, true)
                }
                nextAttempt = 0
                lastDiagnosis = 0
                loop.schedule({ guard { refresh() } }, 1500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun serverLog() = File(System.getProperty("user.home"), ".herdr-cockpit/herdr-server.log")

    /** The folders of this window's projects: the project and the ones attached to it, not the plugin's own mounts. */
    fun projectFolders(): List<String> {
        val ours = mountedRoots()
        val roots = ApplicationManager.getApplication().runReadAction<List<String>> {
            ProjectRootManager.getInstance(project).contentRoots.filter { it.url !in ours }.map { it.path }
        }
        return (listOfNotNull(project.basePath) + roots).distinct()
    }

    /**
     * Herdr creates spaces without a cwd in $HOME, so a server with no spaces gets one for this project, and with
     * the setting every project folder of the window gets one. With `focus` (the TUI shows Herdr's focused
     * space), the project's space is created if missing and focused. Never closes a space.
     */
    private fun ensureProjectSpace(focus: Boolean = false) {
        val base = project.basePath ?: return
        runCatching {
            val spaces = normalize(client.request("session.snapshot")).spaces
            val folders = if (settings.spacePerProject) projectFolders() else if (focus || spaces.isEmpty()) listOf(base) else emptyList()
            // shortcut: matches on the first pane's current cwd, so a space whose shell left the project gets a twin; upgrade if that annoys
            var baseId = projectSpace(spaces, base)?.id
            for (dir in folders) if (projectSpace(spaces, dir) == null) {
                val id = actions.createSpace(dir, null).workspaceId
                if (dir == base) baseId = id
            }
            if (focus) baseId?.let(actions::focusSpace)
        }.onFailure { log("couldn't create the project spaces: ${it.message}") }
    }

    fun startHerdr() {
        conn = Conn.Starting
        scheduleRender()
        loop.execute {
            guard {
                val r = startServer(binary(), herdrEnvNow(), serverLog())
                log(if (r.ok) (if (r.already) "herdr was already running" else "started herdr server") else "start failed: ${r.error}")
                if (r.ok) ensureProjectSpace()
                conn = Conn.Connecting
                nextAttempt = 0
                lastDiagnosis = 0
                if (!r.ok) notify("Couldn't start Herdr: ${r.error}", NotificationType.ERROR)
                refresh()
            }
        }
    }

    /** `herdr server stop`, after asking: it ends every agent and shell, also those of other windows and terminals. */
    fun stopHerdr() {
        if (Messages.showOkCancelDialog(project, "Every agent and shell in Herdr stops, also in other IDE windows and terminals. The next start restores your spaces, tabs and folders.",
                "Stop Herdr?", "Stop Herdr", "Cancel", Messages.getWarningIcon()) != Messages.OK) return
        loop.execute {
            guard {
                val ok = runCommand(listOf(binary(), "server", "stop"), herdrEnvNow(), 15)?.first == 0
                log(if (ok) "stopped herdr server" else "stop failed")
                if (!ok) notify("Couldn't stop Herdr. Try <code>herdr server stop</code> in a terminal.", NotificationType.ERROR)
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
        usageExec.shutdownNow()
        activity.shutdown()
        git.shutdown()
        for (f in files.values) f.connector?.close()
    }
}
