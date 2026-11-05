// The boundary between the shared plugin parts (sidebar, editor tabs, settings, dialogs) and what depends on the
// Herdr version. Herdr 1 is Herdr1Backend. No IntelliJ imports, so backends can be tested against fake servers.
package io.github.tezmanian.herdrcockpit

enum class BackendKind { HERDR1, HERDR2 }

/** Action groups a backend supports; the UI offers nothing outside its backend's set. */
@Suppress("EnumEntryName")
enum class Capability {
    structure, worktrees, agentStart, agentNaming, agentPrompt, agentWait, links, integrations, serverLifecycle,
    attach, paneFind, activity, seenMarkers, pins,
}

/** An action the active backend doesn't have. Reaching the user means a missing capability check. */
class Unsupported(val capability: Capability) : HerdrError("unsupported", "not available with this Herdr version ($capability)")

interface Backend {
    val kind: BackendKind
    val capabilities: Set<Capability>
    val actions: BackendActions

    /** Cheap probe; never starts a server. */
    fun reachable(): Boolean
    /** Why the backend can't be reached, as the sidebar's "down" screens show it. */
    fun diagnose(e: Exception): Diagnosis
    /** The environment for processes the plugin spawns (pane streams, tools). */
    fun env(): Map<String, String>

    /** A full re-read. Throws when the server can't be reached. */
    fun snapshot(): Model
    /** Changes when the subscription for `model` must be renewed (Herdr 1: its pane set). */
    fun subscriptionKey(model: Model?): String
    /** onChange on every event (a "re-read" signal); onEnd once on close or error, not after dispose(). */
    fun subscribe(model: Model?, onChange: () -> Unit, onEnd: (Exception?) -> Unit): Subscription

    /** The command that streams a pane into an editor tab. */
    fun attachCommand(paneId: String, observe: Boolean, cols: Int, rows: Int): List<String>
    fun activity(): ActivityWatcher

    fun dispose()
}

/** HerdrActions' surface. A backend lacking a method's capability keeps its default, which throws [Unsupported]. */
interface BackendActions {
    fun createTab(workspaceId: String, cwd: String?, label: String?): Created = unsupported(Capability.structure)
    fun createSpace(cwd: String, label: String?): Created = unsupported(Capability.structure)
    fun splitPane(paneId: String, right: Boolean, cwd: String?): Created = unsupported(Capability.structure)
    fun toggleZoom(paneId: String): Boolean = unsupported(Capability.structure)
    fun paneScroll(paneId: String): PaneScroll = unsupported(Capability.paneFind)
    fun scrollPaneTo(paneId: String, offset: Int): PaneScroll = unsupported(Capability.paneFind)
    fun paneRevision(paneId: String): Long = unsupported(Capability.paneFind)
    fun paneHistory(paneId: String, cols: Int): String = unsupported(Capability.paneFind)
    /** Focus in the Herdr window counts as structure: Herdr 2 keeps focus per screen. */
    fun focusSpace(workspaceId: String): Unit = unsupported(Capability.structure)
    fun focusAgent(paneId: String): Unit = unsupported(Capability.structure)
    fun createWorktreeSpace(workspaceId: String, branch: String, label: String?): Created = unsupported(Capability.worktrees)
    fun renameTab(tabId: String, label: String): Unit = unsupported(Capability.structure)
    fun renameSpace(workspaceId: String, label: String): Unit = unsupported(Capability.structure)
    fun renamePane(paneId: String, label: String?): Unit = unsupported(Capability.structure)
    fun renameAgent(paneId: String, name: String?): Unit = unsupported(Capability.agentNaming)
    fun promptAgent(paneId: String, text: String): Unit = unsupported(Capability.agentPrompt)
    fun promptAndWait(paneId: String, text: String, until: Set<AgentStatus>, timeoutMs: Long): AgentStatus = unsupported(Capability.agentWait)
    fun waitAgent(paneId: String, until: Set<AgentStatus>, timeoutMs: Long): AgentStatus = unsupported(Capability.agentWait)
    fun sendKeys(paneId: String, keys: List<String>): Unit = unsupported(Capability.agentPrompt)
    fun paneKeys(paneId: String, keys: List<String>): Unit = unsupported(Capability.agentPrompt)
    fun integrations(): List<Integration> = unsupported(Capability.integrations)
    fun installIntegration(target: String): Unit = unsupported(Capability.integrations)
    fun uninstallIntegration(target: String): Unit = unsupported(Capability.integrations)
    fun readAgent(paneId: String): String = unsupported(Capability.agentPrompt)
    fun processInfo(paneId: String): ProcInfo? = unsupported(Capability.structure)
    fun closePane(paneId: String): Unit = unsupported(Capability.structure)
    fun closeTab(tabId: String): Unit = unsupported(Capability.structure)
    fun closeSpace(workspaceId: String, group: Boolean = false): Unit = unsupported(Capability.structure)
    fun removeWorktree(workspaceId: String, force: Boolean = false): Unit = unsupported(Capability.worktrees)
    fun startAgent(paneId: String, kind: String, name: String): Unit = unsupported(Capability.agentStart)

    fun markSeen(paneId: String, turn: Long): Unit = unsupported(Capability.seenMarkers)
    fun setPinned(paneId: String, pinned: Boolean): Unit = unsupported(Capability.pins)
}

private fun unsupported(c: Capability): Nothing = throw Unsupported(c)

/** Which backend the "Herdr version" setting picks right now. `kind` null: none reachable (Down, no fallback). */
data class Choice(val kind: BackendKind?, val notice: Boolean = false)

/**
 * auto prefers Herdr 1 and says once that Herdr 2 runs too; an explicit version never falls back to the other.
 * The probes run lazily, Herdr 1's first, and never start a server.
 */
fun chooseBackend(setting: String?, herdr1Up: () -> Boolean, coreUp: () -> Boolean): Choice = when (setting) {
    "herdr1" -> Choice(if (herdr1Up()) BackendKind.HERDR1 else null)
    "herdr2" -> Choice(if (coreUp()) BackendKind.HERDR2 else null)
    else -> when {
        herdr1Up() -> Choice(BackendKind.HERDR1, notice = coreUp())
        coreUp() -> Choice(BackendKind.HERDR2)
        else -> Choice(null)
    }
}
