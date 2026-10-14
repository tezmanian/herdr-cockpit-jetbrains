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

    /**
     * New space on a new git worktree (and branch) of the repo of space `workspaceId`. Herdr takes the repo
     * from either workspace_id or cwd and refuses both ("only one of workspace_id or cwd may be supplied").
     */
    fun createWorktreeSpace(workspaceId: String, branch: String, label: String?): Created =
        created(client.request("worktree.create", params("workspace_id" to workspaceId, "branch" to branch, "label" to label), 30_000))

    fun renameTab(tabId: String, label: String) {
        client.request("tab.rename", JsonObject().apply { addProperty("tab_id", tabId); addProperty("label", label) }, 5000)
    }

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
