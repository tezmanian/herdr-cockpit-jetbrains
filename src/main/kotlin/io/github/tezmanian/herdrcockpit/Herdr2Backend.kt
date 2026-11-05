// Herdr 2 (herdr-core plus the Herdr app's layout document), experimental: the sidebar mirrors the Herdr app's
// window and terminals open with `hc attach`. Read-only otherwise: spaces, tabs and panes are made in the app
// (tez/herdr-cockpit#71). Settings and IDE state come in as functions, so this file has no IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Terminal addresses `hc attach` gets: local ones only, never an option. */
private val ADDRESS = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

class Herdr2Backend(
    private val hcBinary: () -> String,
    private val customSocket: () -> String?,
    /** A folder's git top level; null outside a repo and in untrusted projects. */
    private val gitTop: (String) -> String?,
) : Backend {
    private val core = CoreClient({ socketPath() })
    @Volatile private var coreVersion: String? = null
    /** The latest terminal records by address, for the shells' foreground process. */
    @Volatile private var terminals: Map<String, JsonObject> = emptyMap()
    private val watcher = ActivityWatcher { t ->
        val fg = terminals[t.paneId]?.get("foreground")?.takeIf { it.isJsonObject }?.asJsonObject
        val info = if (t.agent || fg == null) null else fg.get("pid")?.takeIf { it.isJsonPrimitive }?.asLong.let { pid ->
            ProcInfo(pid, listOf(Proc(pid, fg.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                fg.get("argv")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(), null)))
        }
        val read = core.request("terminal.read", JsonObject().apply { addProperty("terminal", t.paneId); addProperty("lines", if (t.agent) 60 else 40) })
        info to (read.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
    }

    override val kind = BackendKind.HERDR2
    override val capabilities: Set<Capability> = setOf(Capability.attach, Capability.activity, Capability.seenMarkers, Capability.pins)
    override val actions = object : BackendActions {
        override fun markSeen(paneId: String, turn: Long) = label(paneId, "herdr.seen", turn.toString())
        override fun setPinned(paneId: String, pinned: Boolean) = label(paneId, "herdr.pinned", if (pinned) "1" else "")
    }

    /** Sets one label; a terminal that is gone by now is fine. */
    private fun label(paneId: String, key: String, value: String) {
        try {
            core.request("terminal.label", JsonObject().apply {
                addProperty("terminal", paneId)
                add("labels", JsonObject().apply { addProperty(key, value) })
            })
        } catch (e: HerdrError) {
            if (e.code != "not_found") throw e
        }
    }

    fun socketPath(): String = resolveCoreSocket(customSocket())

    override fun reachable() = core.hello()?.also { coreVersion = it.version } != null

    override fun diagnose(e: Exception) = Diagnosis(
        DownReason.Unreachable, socketPath(), customSocket() != null, hcBinary(),
        detail = if (e is LayoutUnreadable) e.message else "herdr-core isn't reachable at ${socketPath()}: ${e.message}. It runs once the Herdr app has started.",
    )

    /** The IDE's environment without the Herdr 1 and hc terminal variables of a terminal it was started from. */
    override fun env(): Map<String, String> {
        val env = System.getenv().filterKeys { !it.startsWith("HERDR_") && it != "HC_TERMINAL_ID" }.toMutableMap()
        customSocket()?.let { env["HC_SOCKET"] = resolveCoreSocket(it) }
        return env
    }

    override fun snapshot(): Model {
        if (coreVersion == null) coreVersion = core.hello()?.version
        val doc = core.request("doc.get", JsonObject().apply { addProperty("name", "herdr-app") })
        val list = core.request("terminal.list").get("terminals")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        terminals = list.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapNotNull { t -> t.get("address")?.takeIf { it.isJsonPrimitive }?.asString?.let { it to t } }.toMap()
        return coreModel(doc, list, System.getProperty("user.home"), gitTop, coreVersion)
    }

    /** One unfiltered subscription for everything: it never needs renewing. */
    override fun subscriptionKey(model: Model?) = ""
    override fun subscribe(model: Model?, onChange: () -> Unit, onEnd: (Exception?) -> Unit) = core.subscribe(onChange, onEnd)

    // shortcut: subprocess attach; framed-mode attach in-process if resize or paste glitches show up
    override fun attachCommand(paneId: String, observe: Boolean, cols: Int, rows: Int): List<String> {
        if (!ADDRESS.matches(paneId)) throw HerdrError("bad_address", "not a local terminal address: $paneId")
        return listOf(hcBinary(), "attach", paneId)
    }

    override fun activity() = watcher

    override fun dispose() {
        core.dispose()
        watcher.shutdown()
    }
}

/**
 * Raises an agent's shared `herdr.seen` marker to its turn while you look at its tab, so every Herdr 2 client shows
 * it as read. `looking` says whether its tab is selected in a focused IDE window. At most one write per turn, and
 * after Mark unread none until the next turn.
 */
class SeenTracker(
    private val looking: (String) -> Boolean,
    private val onError: (Exception) -> Unit = {},
    private val write: (String, Long) -> Unit,
) {
    private val lastWritten = ConcurrentHashMap<String, Long>()

    /** `panes`: all agents right now. A gone address is forgotten, so a restarted core can reuse it. */
    fun observe(panes: List<Pane>) {
        lastWritten.keys.retainAll(panes.mapTo(HashSet()) { it.id })
        for (p in panes) {
            if (!p.isAgent || lastWritten[p.id] == p.turn) continue
            if ((p.seen == null || p.turn > p.seen) && looking(p.id)) {
                lastWritten[p.id] = p.turn // one try per turn, even if it fails
                try { write(p.id, p.turn) } catch (e: Exception) { onError(e) }
            }
        }
    }

    fun canMarkUnread(p: Pane) = p.isAgent && p.turn > 0

    fun markUnread(p: Pane) {
        if (!canMarkUnread(p)) return
        lastWritten[p.id] = p.turn // before the write: an observe() meanwhile must not undo it
        write(p.id, p.turn - 1)
    }
}
