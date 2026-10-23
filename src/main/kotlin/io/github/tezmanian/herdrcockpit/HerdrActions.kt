// Creating Herdr spaces and tabs and starting agents. Typed wrappers over the socket API; every create
// passes focus:false so Herdr's own view (e.g. in your terminal) doesn't jump. No IntelliJ imports
// (tested against a fake server, see ActionsTest).
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject

/** Herdr's rule for agent names (they double as CLI targets): lowercase, digits, - and _. */
val AGENT_NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")

/** A git branch name `git check-ref-format` would refuse (the common cases). */
private val BAD_BRANCH = Regex("""\s|\.\.|[~^:?*\[\\]|^[-/]|[/.]$|@\{""")
fun isValidBranch(name: String) = name.isNotBlank() && !BAD_BRANCH.containsMatchIn(name.trim())

/** A valid agent name from any text ("Reviewer Bot" -> "reviewer-bot"); null when nothing usable is left. */
fun agentSlug(text: String): String? =
    text.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").replace(Regex("^[^a-z]+"), "").take(32).trimEnd('-').takeIf { AGENT_NAME.matches(it) }

/** A free agent name for a new agent of this kind: "claude", then "claude-2", "claude-3", ... */
fun agentName(kind: String, taken: Collection<String>): String {
    val base = kind.lowercase().replace(Regex("[^a-z0-9_-]"), "-").replace(Regex("^[^a-z]+"), "").take(28).ifEmpty { "agent" }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

/** A saved prompt: `category` "review" (the reviews), "general" (Send to Agent, Hand Off) or one of the review templates. */
data class SavedPrompt(val name: String, val category: String, val text: String)

const val BRANCH_TEMPLATE = "branch template"
const val CHANGES_TEMPLATE = "changes template"
val PROMPT_CATEGORIES = listOf("review", "general", BRANCH_TEMPLATE, CHANGES_TEMPLATE)

/** The old "Prompts" setting (one "category: text" per line, no category = "general") as entries named after their text. */
fun promptsFromLines(setting: String?): List<SavedPrompt> =
    (setting ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val m = Regex("^([A-Za-z]+):\\s*(.+)$").find(line)
        val (cat, text) = if (m != null) m.groupValues[1].lowercase() to m.groupValues[2] else "general" to line
        SavedPrompt(if (text.length > 40) text.take(40).substringBeforeLast(' ').trimEnd(',', ':') + "…" else text, cat, text)
    }

/** The presets before 0.1.23, as the old setting held them. */
const val LEGACY_PROMPTS =
    "review: Focus on security: injection, authorization, secrets, unsafe input.\n" +
        "review: Only real bugs; skip style and naming.\n" +
        "review: Focus on tests: missing cases and edge cases.\n" +
        "general: Explain what this does and point out anything risky.\n" +
        "general: Write unit tests for this.\n"

/** The presets since 0.1.23, in the order of LEGACY_PROMPTS. */
val DEFAULT_PROMPTS = listOf(
    SavedPrompt("Security", "review",
        "Focus on security. Look for injection (SQL, shell commands, file paths, templates), missing or wrong authorization " +
            "checks, secrets in code, config or logs, unsafe deserialization, and user input that reaches queries, files or " +
            "commands without validation. For each finding, name the input that gets through and what an attacker gains."),
    SavedPrompt("Real bugs only", "review",
        "Report only real bugs: code that crashes, returns wrong results, loses or corrupts data, leaks resources, or breaks " +
            "under concurrency or unexpected input. For each one, describe a concrete input or state that triggers it and " +
            "what goes wrong. Skip style, naming, formatting and refactoring ideas; if you aren't sure it's a bug, leave it out."),
    SavedPrompt("Missing tests", "review",
        "Focus on tests. Which changed behaviour has no test? Which edge cases (empty, null, limits, errors, concurrency) and " +
            "error paths are missing? Do the existing tests actually assert the new behaviour, or would they still pass if " +
            "it broke? Point at the changed line that needs a test and say what the test should check."),
    SavedPrompt("Explain and flag risks", "general",
        "Explain what this code does: its purpose, the main flow and how it fits into the code around it. Then list anything " +
            "risky (possible bugs, security issues, performance traps, hidden assumptions), each with where it is and why it matters."),
    SavedPrompt("Write unit tests", "general",
        "Write unit tests for this with the project's existing test framework and conventions. Cover the normal cases, edge " +
            "cases and error paths. Run them; fix a test that is wrong, but if one fails because of a bug in the code, don't " +
            "change the code: report the bug."),
)

/** The review templates as library entries, added once next to the presets. */
val DEFAULT_TEMPLATES = listOf(
    SavedPrompt("Branch review", BRANCH_TEMPLATE, DEFAULT_BRANCH_TEMPLATE),
    SavedPrompt("Merge request review", CHANGES_TEMPLATE, DEFAULT_CHANGES_TEMPLATE),
)

/** `prompts` with each untouched legacy preset replaced by its successor; your own and edited ones stay. */
fun upgradePresets(prompts: List<SavedPrompt>): List<SavedPrompt> {
    val legacy = promptsFromLines(LEGACY_PROMPTS)
    return prompts.map { p -> legacy.indexOf(p).takeIf { it >= 0 }?.let { DEFAULT_PROMPTS[it] } ?: p }
}

/** The agents offered first when picking (setting "Agents"), known kinds only, in the given order. */
fun favoriteAgents(setting: String?): List<String> =
    (setting ?: "").split(',', ' ').map { it.trim().lowercase() }.filter { it in AGENT_KINDS }.distinct()

/** Herdr's view of a pane: `offset` rows above the bottom, at most `max`; `rows` on screen. */
data class PaneScroll(val offset: Int, val max: Int, val rows: Int)

/** A new tab or space: its first pane (attach it like any other pane). */
data class Created(val paneId: String, val tabId: String?, val workspaceId: String?)

/** How long Herdr may take to report an agent ready (its default is 30s, max 300s). */
private const val AGENT_START_TIMEOUT_MS = 60_000L
/** On top of a wait's own timeout, for the reply to arrive. */
private const val WAIT_SLACK_MS = 10_000L
/** A pane Herdr just created isn't at its prompt yet ("agent_pane_busy"); retry this long. */
private const val PANE_READY_TIMEOUT_MS = 15_000L

fun interface Requester {
    fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject
}

class HerdrActions(private val client: Requester, private val sleep: (Long) -> Unit = Thread::sleep) {
    constructor(client: HerdrClient, sleep: (Long) -> Unit = Thread::sleep) : this(Requester { m, p, t -> client.request(m, p, t) }, sleep)

    private fun params(vararg kv: Pair<String, Any?>) = JsonObject().apply {
        addProperty("focus", false)
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> addProperty(k, v)
            is Number -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            else -> error("unsupported param $k")
        }
    }

    private fun created(r: JsonObject): Created {
        val pane = r.getAsJsonObject("root_pane") ?: throw HerdrError("bad_response", "herdr didn't return the new pane")
        val str = { o: JsonObject?, k: String -> o?.get(k)?.takeIf { it.isJsonPrimitive }?.asString }
        val paneId = str(pane, "pane_id") ?: throw HerdrError("bad_response", "herdr didn't return the new pane")
        return Created(paneId, str(r.getAsJsonObject("tab"), "tab_id") ?: str(pane, "tab_id"), str(r.getAsJsonObject("workspace"), "workspace_id") ?: str(pane, "workspace_id"))
    }

    /** New tab in a space: a fresh shell in `cwd` (or the space's default directory). */
    fun createTab(workspaceId: String, cwd: String?, label: String?): Created =
        created(client.request("tab.create", params("workspace_id" to workspaceId, "cwd" to cwd, "label" to label), 5000))

    /** New space rooted at `cwd`. */
    fun createSpace(cwd: String, label: String?): Created =
        created(client.request("workspace.create", params("cwd" to cwd, "label" to label), 5000))

    private fun scrollOf(r: JsonObject): PaneScroll {
        val s = r.getAsJsonObject("pane")?.getAsJsonObject("scroll") ?: throw HerdrError("bad_response", "Herdr gave no scroll position for this pane")
        return PaneScroll(s.get("offset_from_bottom").asInt, s.get("max_offset_from_bottom").asInt, s.get("viewport_rows").asInt)
    }

    /** Where Herdr's view of a pane is in its history. */
    fun paneScroll(paneId: String): PaneScroll = scrollOf(client.request("pane.get", JsonObject().apply { addProperty("pane_id", paneId) }, 5000))

    /** Scrolls Herdr's view of a pane to `offset` rows above the bottom; answers where it is now. */
    fun scrollPaneTo(paneId: String, offset: Int): PaneScroll = scrollOf(client.request("pane.scroll", JsonObject().apply {
        addProperty("pane_id", paneId)
        addProperty("offset_from_bottom", offset.coerceAtLeast(0))
    }, 5000))

    /** Changes whenever the pane's content does (`pane.copy_motion` without a revision just answers it). */
    fun paneRevision(paneId: String): Long = client.request("pane.copy_motion", JsonObject().apply {
        addProperty("pane_id", paneId)
        add("cursor", JsonObject().apply { addProperty("row", 0); addProperty("col", 0) })
        addProperty("motion", "line_end")
    }, 5000).get("content_revision").asLong

    /** Everything Herdr keeps for a pane (scrollback and screen) as text. `cols` is the pane's width. */
    fun paneHistory(paneId: String, cols: Int): String {
        val scroll = paneScroll(paneId)
        val last = scroll.max + scroll.rows - 1
        fun at(row: Int, col: Int) = JsonObject().apply { addProperty("row", row); addProperty("col", col) }
        fun read(col: Int) = client.request("pane.selection.read", JsonObject().apply {
            addProperty("pane_id", paneId)
            add("anchor", at(0, 0))
            add("cursor", at(last, col))
        }, 30_000).get("text").asString
        // Herdr refuses a column past the pane's width: a narrower pane than the tab drops the last row's tail.
        return runCatching { read(cols - 1) }.getOrElse { read(0) }
    }

    /** Focuses space `workspaceId` in Herdr's own view (what the TUI shows). */
    fun focusSpace(workspaceId: String) {
        client.request("workspace.focus", JsonObject().apply { addProperty("workspace_id", workspaceId) }, 5000)
    }

    /** Focuses an agent's pane in Herdr's own view; marks its "done" as seen. */
    fun focusAgent(paneId: String) = call("agent.focus", "target" to paneId)

    /**
     * New space on a new git worktree (and branch) of the repo of space `workspaceId`. Herdr takes the repo
     * from either workspace_id or cwd and refuses both ("only one of workspace_id or cwd may be supplied").
     */
    fun createWorktreeSpace(workspaceId: String, branch: String, label: String?): Created =
        created(client.request("worktree.create", params("workspace_id" to workspaceId, "branch" to branch, "label" to label), 30_000))

    /** Renames and closes: `kv` as given, null values sent as JSON null (Herdr's "clear the name"). */
    private fun call(method: String, vararg kv: Pair<String, Any?>, timeoutMs: Long = 5000) {
        client.request(method, JsonObject().apply {
            for ((k, v) in kv) when (v) {
                null -> add(k, com.google.gson.JsonNull.INSTANCE)
                is Boolean -> addProperty(k, v)
                else -> addProperty(k, v.toString())
            }
        }, timeoutMs)
    }

    fun renameTab(tabId: String, label: String) = call("tab.rename", "tab_id" to tabId, "label" to label)
    fun renameSpace(workspaceId: String, label: String) = call("workspace.rename", "workspace_id" to workspaceId, "label" to label)
    /** null falls back to the tab name / "Tab N". */
    fun renamePane(paneId: String, label: String?) = call("pane.rename", "pane_id" to paneId, "label" to label)
    /** `name` must match AGENT_NAME (agent names are CLI targets); null clears it. */
    fun renameAgent(paneId: String, name: String?) = call("agent.rename", "target" to paneId, "name" to name)
    /** Text + Enter as one submission; Herdr refuses an agent waiting at a question ("agent_blocked"). */
    fun promptAgent(paneId: String, text: String) = call("agent.prompt", "target" to paneId, "text" to text)

    /**
     * [promptAgent], then waits up to `timeoutMs` until the agent is in one of `until`; its status then. Herdr first
     * waits a few seconds for the prompt to start work ("agent_prompt_stalled" if it doesn't). Blocks the thread.
     */
    fun promptAndWait(paneId: String, text: String, until: Set<AgentStatus>, timeoutMs: Long): AgentStatus =
        statusOf(client.request("agent.prompt", JsonObject().apply {
            addProperty("target", paneId)
            addProperty("text", text)
            add("wait", waitParams(until, timeoutMs))
        }, timeoutMs + WAIT_SLACK_MS))

    /** Waits up to `timeoutMs` until the agent is in one of `until` (at once if it already is); its status then. */
    fun waitAgent(paneId: String, until: Set<AgentStatus>, timeoutMs: Long): AgentStatus =
        statusOf(client.request("agent.wait", waitParams(until, timeoutMs).apply { addProperty("target", paneId) }, timeoutMs + WAIT_SLACK_MS))

    private fun waitParams(until: Set<AgentStatus>, timeoutMs: Long) = JsonObject().apply {
        add("until", com.google.gson.JsonArray().apply { until.forEach { add(it.name) } })
        addProperty("timeout_ms", timeoutMs)
    }

    private fun statusOf(result: JsonObject) = AgentStatus.of(result.getAsJsonObject("agent")?.get("agent_status")?.asString)

    /** Key presses ("enter", "esc", "up", "1") to the agent's own UI; refused when that agent no longer holds the pane. */
    fun sendKeys(paneId: String, keys: List<String>) {
        client.request("agent.send_keys", JsonObject().apply {
            addProperty("target", paneId)
            add("keys", com.google.gson.JsonArray().apply { keys.forEach { add(it) } })
        }, 5000)
    }

    /** Key presses to the pane, encoded by Herdr for the keyboard mode the program in it asked for (e.g. kitty's). */
    fun paneKeys(paneId: String, keys: List<String>) {
        client.request("pane.send_keys", JsonObject().apply {
            addProperty("pane_id", paneId)
            add("keys", com.google.gson.JsonArray().apply { keys.forEach { add(it) } })
        }, 5000)
    }

    /** What the agent's screen shows now (plain text), e.g. the dialog it is waiting at. */
    fun readAgent(paneId: String): String =
        client.request("agent.read", JsonObject().apply { addProperty("target", paneId); addProperty("source", "visible") }, 5000)
            .getAsJsonObject("read")?.get("text")?.asString ?: ""

    fun processInfo(paneId: String): ProcInfo? =
        parseProcInfo(client.request("pane.process_info", JsonObject().apply { addProperty("pane_id", paneId) }, 5000))

    /** Closing ends the processes in it. */
    fun closePane(paneId: String) = call("pane.close", "pane_id" to paneId)
    fun closeTab(tabId: String) = call("tab.close", "tab_id" to tabId)
    /** Fails with "workspace_group_close_required" when the space has worktree spaces, unless `group` closes them too. */
    fun closeSpace(workspaceId: String, group: Boolean = false) =
        if (group) call("workspace.close", "workspace_id" to workspaceId, "close_group" to true) else call("workspace.close", "workspace_id" to workspaceId)
    /** Closes a worktree space and deletes its folder. Herdr refuses uncommitted changes unless `force` (which discards them). */
    fun removeWorktree(workspaceId: String, force: Boolean = false) =
        if (force) call("worktree.remove", "workspace_id" to workspaceId, "force" to true, timeoutMs = 30_000) else call("worktree.remove", "workspace_id" to workspaceId, timeoutMs = 30_000)

    /**
     * Starts an agent in a pane at its shell prompt; returns once Herdr reports it ready. `name` must match
     * AGENT_NAME and be unused (see agentName()).
     */
    fun startAgent(paneId: String, kind: String, name: String) {
        val deadline = System.currentTimeMillis() + PANE_READY_TIMEOUT_MS
        val p = JsonObject().apply {
            addProperty("pane_id", paneId)
            addProperty("kind", kind)
            addProperty("name", name)
            addProperty("timeout_ms", AGENT_START_TIMEOUT_MS)
        }
        while (true) {
            try {
                client.request("agent.start", p, AGENT_START_TIMEOUT_MS + 5000)
                return
            } catch (e: HerdrError) {
                if (e.code != "agent_pane_busy" || System.currentTimeMillis() > deadline) throw e
                sleep(250)
            }
        }
    }
}
