// Everything the sidebar draws, as JSON in the shape media/panel.js (from the VS Code extension) expects.
// Built from the model plus what only the plugin knows (git, mounted roots, open tabs, pane activity).
// No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Agent kinds `herdr agent start --kind` accepts, with display names. */
val AGENT_KINDS = mapOf(
    "claude" to "Claude", "codex" to "Codex", "kiro" to "Kiro", "cursor" to "Cursor", "gemini" to "Gemini",
    "copilot" to "Copilot", "opencode" to "OpenCode", "amp" to "Amp", "agy" to "Antigravity", "cline" to "Cline",
    "devin" to "Devin", "droid" to "Droid", "grok" to "Grok", "hermes" to "Hermes", "kilo" to "Kilo Code",
    "kimi" to "Kimi", "mastracode" to "Mastra Code", "qodercli" to "Qoder", "qwen" to "Qwen", "pi" to "Pi",
    "omp" to "omp", "letta" to "Letta", "maki" to "Maki", "muse" to "Muse",
)

/** "claude" -> "Claude"; unknown kinds are title-cased. */
fun agentTitle(kind: String) = AGENT_KINDS[kind.lowercase()] ?: kind.replaceFirstChar { it.uppercase() }

private val home = System.getProperty("user.home")
private fun tilde(p: String?) = if (p != null && (p == home || p.startsWith("$home/"))) "~" + p.substring(home.length) else p

/**
 * The pane's name in the sidebar and on its editor tab (they must match). Agents: Herdr's display name,
 * else the agent kind ("Claude", "Claude 2"). Shells: the pane label, else the tab label, else "Tab N".
 */
/** Editor tab title: which space, then the pane's name as the sidebar shows it. */
fun editorTitle(space: Space, tab: Tab?, p: Pane) = "${space.label} · ${paneName(tab, p)}"

/** Appended to a handoff prompt: where the work came from, and how the receiving agent reads it (via Herdr). */
fun handoffContext(space: Space, tab: Tab?, p: Pane) =
    "\n\n(Handed off from ${editorTitle(space, tab, p)}${space.cwd?.let { " in $it" } ?: ""}. Read its output with Herdr: `herdr agent read ${p.id} --lines 200`)"

fun paneName(tab: Tab?, p: Pane): String {
    if (p.isAgent) {
        p.displayAgent?.let { return it }
        val kind = p.agentKind
        val generated = if (p.agentName != null && kind != null)
            Regex("^${Regex.escape(kind.replace(Regex("[^a-zA-Z0-9_-]"), ""))}(?:-(\\d+))?$").find(p.agentName) else null
        if (p.agentName != null && generated == null) return p.agentName
        val title = if (kind != null) agentTitle(kind) else p.label
        val n = generated?.groupValues?.get(1)
        return if (!n.isNullOrEmpty()) "$title $n" else title
    }
    p.paneLabel?.let { return it }
    if (tab == null) return "shell"
    return if (tab.label.all(Char::isDigit)) "Tab ${tab.label}" else tab.label
}

/** An agent's "<glyph> <Verb>ed for 1m 59s · done …" status line. */
private val STATUS_LINE = Regex("^[^\\w\\s]\\s+\\w+ for \\d+[smh]\\b")

/** Claude's "※ recap: ..." (hard-wrapped), else the last line, joined with the previous one when wrapped. */
fun headline(lines: List<String>): String? {
    val i = lines.indexOfLast { it.trimStart().startsWith("※ recap:") }
    if (i >= 0) return lines.drop(i).joinToString(" ") { it.trim() }.replace(Regex("^※ recap:\\s*"), "")
    val content = lines.filter { !STATUS_LINE.containsMatchIn(it.trim()) }
    val last = content.lastOrNull()?.trim()
    if (!last.isNullOrEmpty() && last[0].isLowerCase() && content.size > 1) return "${content[content.size - 2].trim()} $last"
    return last?.ifEmpty { null } ?: lines.lastOrNull()?.trim()?.ifEmpty { null }
}

private fun meaningfulTab(tab: Tab?, name: String) = tab?.label?.takeIf { !it.all(Char::isDigit) && it != name }

data class ViewInputs(
    val model: Model?,
    val showShells: Boolean,
    val since: (String) -> Long?,
    val git: (String?) -> GitInfo?,
    val mounted: (Space) -> Boolean,
    val attached: (String) -> Boolean,
    val activity: (String) -> PaneActivity?,
    val connection: JsonObject,
    /** Plan usage (Usage.kt); only the agents running in Herdr right now are shown. */
    val usage: JsonArray = JsonArray(),
    /** "activity": busy and recently active spaces first (orderByActivity); "herdr": Herdr's order. */
    val order: String = "herdr",
    /** When a space was last active (an agent changed state, it was focused), if known. */
    val lastActive: (Space) -> Long? = { null },
    /** {cockpit, herdr?}: shown at the bottom of the sidebar. */
    val versions: JsonObject? = null,
)

/** Spaces with an agent working or waiting on you first, then the most recently active; ties keep Herdr's order. */
fun orderByActivity(spaces: List<Space>, lastActive: (Space) -> Long?): List<Space> =
    spaces.sortedWith(
        compareByDescending<Space> { s -> s.agents.any { it.status == AgentStatus.working || it.status == AgentStatus.blocked } }
            .thenByDescending { lastActive(it) ?: 0L },
    )

private fun strings(xs: List<String>) = JsonArray().apply { xs.forEach { add(it) } }

private fun counts(m: Map<AgentStatus, Int>) = JsonObject().apply { AgentStatus.entries.forEach { addProperty(it.name, m[it] ?: 0) } }

private fun agentView(space: Space, tab: Tab?, p: Pane, i: ViewInputs): JsonObject {
    val kind = p.agentKind
    val name = paneName(tab, p)
    // Terminal titles are often just "kiro: ~/path"; the path adds nothing next to the space.
    var detail = p.title ?: p.terminalTitle
    if (detail != null && kind != null && detail.lowercase().startsWith(kind.lowercase() + ":")) detail = detail.substring(kind.length + 1).trim()
    if (detail != null && (Regex("^[~/]").containsMatchIn(detail) || detail == name)) detail = null
    val lines = i.activity(p.id)?.output ?: emptyList()
    val status = p.status ?: AgentStatus.unknown
    val chips = listOfNotNull(p.tokens["model"]) + p.tokens.filterKeys { it != "model" }.values
    return JsonObject().apply {
        addProperty("id", p.id)
        addProperty("spaceId", space.id)
        addProperty("kind", "agent")
        addProperty("name", name)
        detail?.let { addProperty("detail", it) }
        addProperty("status", status.name)
        p.stateLabel?.let { addProperty("stateLabel", it) }
        kind?.let { addProperty("agentKind", it) }
        i.since(p.id)?.let { addProperty("since", it) }
        addProperty("focused", p.focused)
        addProperty("attached", i.attached(p.id))
        meaningfulTab(tab, name)?.let { addProperty("tab", it) }
        add("chips", strings(chips))
        headline(lines)?.let { addProperty("headline", it) }
        add("preview", strings(when (status) { AgentStatus.blocked -> lines.takeLast(5); AgentStatus.done -> lines.takeLast(3); else -> emptyList() }))
        tilde(p.cwd)?.let { addProperty("cwd", it) }
    }
}

private fun shellView(space: Space, tab: Tab?, p: Pane, i: ViewInputs): JsonObject {
    val act = i.activity(p.id)
    val name = paneName(tab, p)
    return JsonObject().apply {
        addProperty("id", p.id)
        addProperty("spaceId", space.id)
        addProperty("kind", "shell")
        addProperty("name", name)
        addProperty("focused", p.focused)
        addProperty("attached", i.attached(p.id))
        meaningfulTab(tab, name)?.let { addProperty("tab", it) }
        add("chips", strings(p.tokens.values.toList()))
        add("preview", JsonArray())
        add("shell", JsonObject().apply {
            addProperty("state", act?.state ?: "unknown")
            act?.command?.let { addProperty("command", it) }
            act?.typed?.let { addProperty("typed", it) }
            add("output", strings(act?.output ?: emptyList()))
            addProperty("failed", act?.failed ?: false)
            act?.changedAt?.let { addProperty("changedAt", it) }
        })
        tilde(p.cwd)?.let { addProperty("cwd", it) }
    }
}

private fun gitView(g: GitInfo) = JsonObject().apply {
    g.branch?.let { addProperty("branch", it) }
    g.upstream?.let { addProperty("upstream", it) }
    addProperty("detached", g.detached)
    if (g.ahead > 0) addProperty("ahead", g.ahead)
    if (g.behind > 0) addProperty("behind", g.behind)
    addProperty("staged", g.staged)
    addProperty("modified", g.modified)
    addProperty("untracked", g.untracked)
    addProperty("conflicts", g.conflicts)
    addProperty("changes", g.changes)
    if (g.lastCommitAt != null) add("lastCommit", JsonObject().apply { addProperty("at", g.lastCommitAt); addProperty("subject", g.lastCommitSubject ?: "") })
}

fun buildViewState(i: ViewInputs): JsonObject {
    val totals = AgentStatus.entries.associateWith { 0 }.toMutableMap()
    var agentCount = 0
    var shellCount = 0
    val attention = mutableListOf<JsonObject>()
    val spaces = JsonArray()
    val all = i.model?.spaces ?: emptyList()
    for (sp in if (i.order == "herdr") all else orderByActivity(all, i.lastActive)) {
        val agents = JsonArray()
        val shells = JsonArray()
        for (tab in sp.tabs) for (p in tab.panes) {
            if (p.isAgent) {
                val v = agentView(sp, tab, p, i)
                agents.add(v)
                totals.merge(p.status ?: AgentStatus.unknown, 1, Int::plus)
                agentCount++
                if (p.status == AgentStatus.blocked || p.status == AgentStatus.done) attention.add(v)
            } else {
                shellCount++
                if (i.showShells) shells.add(shellView(sp, tab, p, i))
            }
        }
        spaces.add(JsonObject().apply {
            addProperty("id", sp.id)
            addProperty("label", sp.label)
            tilde(sp.cwd)?.let { addProperty("path", it) }
            sp.status?.let { addProperty("status", it.name) }
            addProperty("focused", sp.focused)
            addProperty("mounted", i.mounted(sp))
            add("counts", counts(sp.counts))
            i.git(sp.cwd)?.let { add("git", gitView(it)) }
            addProperty("worktree", sp.linkedWorktree)
            add("tokens", strings(sp.tokens.values.toList()))
            add("agents", agents)
            add("shells", shells)
        })
    }
    // Blocked agents first, then done; oldest first within each.
    attention.sortWith(compareBy<JsonObject> { if (it.get("status").asString == "blocked") 0 else 1 }.thenBy { it.get("since")?.asLong ?: 0L })
    return JsonObject().apply {
        addProperty("connected", i.model != null)
        add("connection", i.connection)
        addProperty("showShells", i.showShells)
        add("totals", counts(totals).apply { addProperty("agents", agentCount); addProperty("shells", shellCount) })
        add("attention", JsonArray().apply { attention.forEach { add(it) } })
        add("spaces", spaces)
        val running = i.model?.spaces?.flatMap { sp -> sp.agents.mapNotNull { it.agentKind?.lowercase() } }.orEmpty().toSet()
        add("usage", JsonArray().apply { i.usage.filter { it.asJsonObject.get("id").asString in running }.forEach { add(it) } })
        i.versions?.let { add("versions", it) }
    }
}
