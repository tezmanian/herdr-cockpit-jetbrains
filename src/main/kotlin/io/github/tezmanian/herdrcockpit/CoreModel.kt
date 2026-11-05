// Herdr 2: the Herdr app's layout document ("herdr-app", from herdr-core's doc.get) plus herdr-core's terminal list,
// turned into the same Space > Tab > Pane model as Herdr 1. The layout orders and groups; terminals carry names,
// folders and status. Names follow herdr-app's rules. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** The layout document doesn't have the shape this plugin knows; nothing of it is shown. */
class LayoutUnreadable(detail: String) : HerdrError("unreadable", "the Herdr 2 layout can't be read ($detail)")

private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
private fun JsonObject.objAt(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

/** herdr-app's folder_name: the last path segment; "~" for the home folder or an empty path. */
fun folderName(path: String?, home: String): String {
    val p = path?.trimEnd('/').orEmpty()
    if (p.isEmpty() || p == home.trimEnd('/')) return "~"
    return p.substringAfterLast('/')
}

/** A terminal's status as the sidebar shows it: "done" while its last turn wasn't seen (`herdr.seen`). */
fun coreStatus(state: String?, turn: Long, seen: Long?): AgentStatus = when (state) {
    "working" -> AgentStatus.working
    "blocked", "error" -> AgentStatus.blocked
    else -> when {
        seen != null -> if (turn > seen) AgentStatus.done else AgentStatus.idle
        else -> AgentStatus.of(state)
    }
}

/** The terminal addresses of a layout node, in tree order. Blocks with other content (mod panes) are skipped. */
private fun terminalsIn(node: JsonObject?, out: MutableList<String>) {
    if (node == null) return
    if (node.text("type") == "block") {
        node.objAt("content")?.text("terminal")?.let(out::add)
        return
    }
    node.get("children")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { terminalsIn(it.objOrNull(), out) }
}

private fun liveCwd(t: JsonObject): String? = t.objAt("foreground")?.text("cwd") ?: t.text("cwd")

private fun toPane(t: JsonObject, tabId: String, spaceId: String): Pane {
    val address = t.text("address")!!
    val labels = t.objAt("labels")
    val agent = t.get("reported")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    val status = t.objAt("status")
    val fg = t.objAt("foreground")
    val name = t.text("name")
    val kind = if (agent) status?.text("app") ?: fg?.text("name") else null
    val turn = t.text("turn")?.toLongOrNull() ?: 0
    val seen = labels?.text("herdr.seen")?.toLongOrNull()
    return Pane(
        id = address,
        tabId = tabId,
        workspaceId = spaceId,
        label = name ?: t.text("title") ?: fg?.text("name") ?: address,
        cwd = liveCwd(t),
        isAgent = agent,
        agentKind = kind,
        status = if (agent) coreStatus(status?.text("state"), turn, seen) else null,
        focused = false, // focus is per screen in Herdr 2, not shared
        stateLabel = null,
        title = null,
        terminalTitle = t.text("title"),
        tokens = emptyMap(),
        paneLabel = name,
        displayAgent = if (agent) name else null,
        agentName = null,
        pinned = labels?.text("herdr.pinned") != null,
        turn = turn,
        seen = seen,
    )
}

/**
 * `doc` is doc.get's result ({value, version}); `terminals` is terminal.list's `terminals`. `gitTop` gives a folder's
 * git top level (null outside a repo or in untrusted projects); unnamed spaces are named after it, as the app does.
 * Throws [LayoutUnreadable] on a layout of another shape.
 */
fun coreModel(doc: JsonObject, terminals: JsonArray, home: String, gitTop: (String) -> String?, coreVersion: String?): Model {
    val raw = JsonObject().apply { add("doc", doc); add("terminals", terminals) }
    val version = "2 (experimental) · core ${coreVersion ?: "?"}"
    val value = doc.get("value")
    if (value == null || value.isJsonNull) return Model(emptyList(), null, version, null, raw)
    val layout = value.objOrNull() ?: throw LayoutUnreadable("not an object")
    val spacesJson = layout.get("spaces")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw LayoutUnreadable("no spaces list")

    // Only live, local terminals: an address with "/" is on another machine.
    val byAddress = terminals.mapNotNull { it.objOrNull() }
        .filter { it.text("state") == "live" && it.text("address")?.contains('/') == false }
        .associateBy { it.text("address")!! }

    val spaces = mutableListOf<Space>()
    for (se in spacesJson) {
        val sj = se.objOrNull() ?: throw LayoutUnreadable("a space is not an object")
        val spaceId = sj.text("id") ?: throw LayoutUnreadable("a space without id")
        val tabsJson = sj.get("tabs")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw LayoutUnreadable("space $spaceId has no tabs list")
        val tabs = mutableListOf<Tab>()
        for ((i, te) in tabsJson.withIndex()) {
            val tj = te.objOrNull() ?: throw LayoutUnreadable("a tab of $spaceId is not an object")
            val tabId = tj.text("id") ?: throw LayoutUnreadable("a tab of $spaceId without id")
            val addresses = mutableListOf<String>().also { terminalsIn(tj.objAt("root"), it) }
            val tab = Tab(tabId, spaceId, tj.text("name") ?: "${i + 1}")
            addresses.mapNotNull(byAddress::get).forEach { tab.panes.add(toPane(it, tabId, spaceId)) }
            tabs.add(tab)
        }
        if (tabs.isEmpty()) continue
        val first = tabs.flatMap { it.panes }.firstOrNull()?.let { byAddress[it.id] }
        val firstCwd = first?.let(::liveCwd)
        val label = sj.text("name") ?: folderName(firstCwd?.let(gitTop) ?: firstCwd ?: sj.text("folder"), home)
        spaces.add(Space(spaceId, label, firstCwd ?: sj.text("folder"), false, emptyMap(), false, tabs))
    }
    return Model(spaces, null, version, null, raw)
}
