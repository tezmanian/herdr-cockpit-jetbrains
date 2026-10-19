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

/** The agents offered first when picking (setting "Agents"), known kinds only, in the given order. */
fun favoriteAgents(setting: String?): List<String> =
    (setting ?: "").split(',', ' ').map { it.trim().lowercase() }.filter { it in AGENT_KINDS }.distinct()

/** A new tab or space: its first pane (attach it like any other pane). */
data class Created(val paneId: String, val tabId: String?, val workspaceId: String?)

/** How long Herdr may take to report an agent ready (its default is 30s, max 300s). */
private const val AGENT_START_TIMEOUT_MS = 60_000L
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

    /** Focuses space `workspaceId` in Herdr's own view (what the TUI shows). */
    fun focusSpace(workspaceId: String) {
        client.request("workspace.focus", JsonObject().apply { addProperty("workspace_id", workspaceId) }, 5000)
    }

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
