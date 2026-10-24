// Turns Herdr's session.snapshot into a simple Space > Tab > Pane model.
// The snapshot is flat: workspaces, tabs, panes and agents are top-level arrays linked by id, and every
// agent also appears as a pane (AgentInfo is PaneInfo plus agent-only fields). No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonElement
import com.google.gson.JsonObject

enum class AgentStatus(val priority: Int, val urgency: Int) {
    idle(1, 3), working(2, 2), blocked(4, 0), done(3, 1), unknown(0, 4);

    companion object {
        fun of(s: String?): AgentStatus = entries.firstOrNull { it.name == s } ?: unknown
    }
}

data class Pane(
    val id: String,
    val tabId: String,
    val workspaceId: String,
    val label: String,
    val cwd: String?,
    val isAgent: Boolean,
    val agentKind: String?,
    val status: AgentStatus?,
    val focused: Boolean,
    /** Custom label for the current state (state_labels[status]). */
    val stateLabel: String?,
    /** Metadata title (e.g. a task name set by a hook). */
    val title: String?,
    /** Latest terminal title with the spinner stripped. */
    val terminalTitle: String?,
    /** Plugin/hook metadata tokens ($summary, $model, ...). */
    val tokens: Map<String, String>,
    /** Fields the view needs from the raw records. */
    val paneLabel: String?,
    val displayAgent: String?,
    val agentName: String?,
)

data class Tab(val id: String, val workspaceId: String, val label: String, val panes: MutableList<Pane> = mutableListOf())

data class Space(
    val id: String,
    val label: String,
    var cwd: String?,
    val focused: Boolean,
    val tokens: Map<String, String>,
    val linkedWorktree: Boolean,
    val tabs: MutableList<Tab> = mutableListOf(),
) {
    val panes: List<Pane> get() = tabs.flatMap { it.panes }
    val agents: List<Pane> get() = panes.filter { it.isAgent }
    val status: AgentStatus? get() = rollup(agents.map { it.status })
    val counts: Map<AgentStatus, Int> get() = AgentStatus.entries.associateWith { s -> agents.count { (it.status ?: AgentStatus.unknown) == s } }
}

data class Model(val spaces: List<Space>, val focusedSpaceId: String?, val version: String?, val protocol: Int?, val raw: JsonObject) {
    fun paneIds(): List<String> = spaces.flatMap { s -> s.panes.map { it.id } }
    fun find(paneId: String?): Pair<Space, Pane>? {
        for (s in spaces) for (p in s.panes) if (p.id == paneId) return s to p
        return null
    }
    fun space(id: String?): Space? = spaces.firstOrNull { it.id == id }

    /** The snapshot records behind a pane (its pane and agent record) or, without a pane, a space. */
    fun rawOf(spaceId: String?, paneId: String?): JsonObject? {
        val s = raw.obj("snapshot") ?: return null
        fun pick(arr: String, key: String, id: String?) = s.arr(arr)?.firstOrNull { it.s(key) == id }
        if (paneId == null) return pick("workspaces", "workspace_id", spaceId)
        return JsonObject().apply { add("pane", pick("panes", "pane_id", paneId)); add("agent", pick("agents", "pane_id", paneId)) }
    }
}

fun rollup(ss: List<AgentStatus?>): AgentStatus? = ss.filterNotNull().maxByOrNull { it.priority }

/** The space for project directory `base`: one rooted exactly there, else one in a subdirectory (nested projects). */
/** The home folder, "/" or a folder above the project: mounting it would pull all of it into the index. */
fun tooBroadToMount(cwd: String, home: String, base: String?): Boolean =
    cwd == "/" || cwd == home || (base != null && (base == cwd || base.startsWith("$cwd/")))

/** The other open project whose folder is [cwd], lies inside it or contains it: that space is that window's to mount. */
fun otherProjectOf(cwd: String, others: List<String>): String? =
    others.firstOrNull { cwd == it || cwd.startsWith("$it/") || it.startsWith("$cwd/") }

fun projectSpace(spaces: List<Space>, base: String): Space? =
    spaces.firstOrNull { it.cwd == base } ?: spaces.firstOrNull { it.cwd?.startsWith("$base/") == true }

/** The panes to attach when switching to a space: agents most urgent first, then shells in tab order. */
fun attachOrder(space: Space, withShells: Boolean): List<Pane> {
    val agents = space.agents.sortedBy { (it.status ?: AgentStatus.unknown).urgency }
    val shells = if (withShells) space.tabs.flatMap { t -> t.panes.filter { !it.isAgent } } else emptyList()
    return agents + shells
}

private fun JsonObject.s(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
private fun JsonObject.arr(key: String): List<JsonObject>? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.filter(JsonElement::isJsonObject)?.map { it.asJsonObject }

private fun first(vararg vals: String?): String? = vals.firstOrNull { !it.isNullOrEmpty() }

private fun tokensOf(vararg maps: JsonObject?): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (m in maps) m?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive && v.asString.isNotEmpty()) out[k] = v.asString }
    return out
}

private fun toPane(p: JsonObject?, a: JsonObject?, focusedPaneId: String?): Pane {
    // AgentInfo carries every PaneInfo field we read except `label`, so either record works as the base.
    val r = (a ?: p)!!
    val agentKind = first(a?.s("agent"), p?.s("agent"))
    val isAgent = a != null || agentKind != null
    val status = if (isAgent) AgentStatus.of(r.s("agent_status")) else null
    val labels = a?.obj("state_labels") ?: p?.obj("state_labels")
    val title = first(a?.s("title"), p?.s("title"))
    val id = r.s("pane_id") ?: ""
    return Pane(
        id = id,
        tabId = r.s("tab_id") ?: "",
        workspaceId = r.s("workspace_id") ?: "",
        cwd = first(r.s("foreground_cwd"), r.s("cwd")),
        isAgent = isAgent,
        agentKind = agentKind,
        status = status,
        stateLabel = status?.let { labels?.s(it.name) },
        title = title,
        terminalTitle = first(r.s("terminal_title_stripped"), r.s("terminal_title")),
        tokens = tokensOf(p?.obj("tokens"), a?.obj("tokens")),
        label = first(a?.s("display_agent"), p?.s("display_agent"), a?.s("name"), p?.s("label"), title, r.s("terminal_title_stripped"), agentKind) ?: id,
        focused = r.bool("focused") || id == focusedPaneId,
        paneLabel = p?.s("label"),
        displayAgent = first(a?.s("display_agent"), p?.s("display_agent")),
        agentName = a?.s("name"),
    )
}

/** Normalizes the `result` of a `session.snapshot` request. Throws on a result missing the snapshot arrays. */
fun normalize(res: JsonObject): Model {
    val s = res.obj("snapshot")
    val missing = listOf("workspaces", "tabs", "panes", "agents").filter { s?.arr(it) == null }
    if (s == null || missing.isNotEmpty())
        throw IllegalStateException("unexpected session.snapshot shape (missing ${missing.joinToString()}); herdr ${s?.s("version") ?: "?"} protocol ${s?.get("protocol") ?: "?"}")

    val focusedPane = s.s("focused_pane_id")
    val agentByPane = s.arr("agents")!!.associateBy { it.s("pane_id") ?: "" }

    // Panes, plus any agent whose pane record is missing, so it still gets a row.
    val panes = linkedMapOf<String, Pane>()
    for (p in s.arr("panes")!!) {
        val id = p.s("pane_id") ?: continue
        if (id !in panes) panes[id] = toPane(p, agentByPane[id], focusedPane)
    }
    for ((id, a) in agentByPane) if (id !in panes) panes[id] = toPane(null, a, focusedPane)

    val tabs = linkedMapOf<String, Tab>()
    for (t in s.arr("tabs")!!) {
        val id = t.s("tab_id") ?: continue
        tabs[id] = Tab(id, t.s("workspace_id") ?: "", t.s("label") ?: id)
    }
    for (p in panes.values) tabs.getOrPut(p.tabId) { Tab(p.tabId, p.workspaceId, "tab") }.panes.add(p)

    val focusedWs = s.s("focused_workspace_id")
    val spaces = linkedMapOf<String, Space>()
    for (w in s.arr("workspaces")!!) {
        val id = w.s("workspace_id") ?: continue
        val wt = w.obj("worktree")
        spaces[id] = Space(
            id = id,
            label = w.s("label") ?: id,
            cwd = wt?.s("checkout_path"),
            focused = w.bool("focused") || id == focusedWs,
            tokens = tokensOf(w.obj("tokens")),
            linkedWorktree = wt?.bool("is_linked_worktree") ?: false,
        )
    }
    for (t in tabs.values)
        spaces.getOrPut(t.workspaceId) { Space(t.workspaceId, t.workspaceId, null, t.workspaceId == focusedWs, emptyMap(), false) }.tabs.add(t)
    // WorkspaceInfo has no cwd; outside a worktree, use the first pane's (foreground) cwd.
    for (sp in spaces.values) if (sp.cwd == null) sp.cwd = sp.panes.firstOrNull { it.cwd != null }?.cwd

    val list = spaces.values.toList()
    return Model(
        spaces = list,
        focusedSpaceId = focusedWs ?: list.firstOrNull { it.focused }?.id,
        version = s.s("version"),
        protocol = s.get("protocol")?.takeIf { it.isJsonPrimitive }?.asInt,
        raw = res,
    )
}
