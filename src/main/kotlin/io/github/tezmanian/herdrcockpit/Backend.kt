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

/** HerdrActions' surface. Methods a backend lacks throw [Unsupported]. */
interface BackendActions {
    fun createTab(workspaceId: String, cwd: String?, label: String?): Created
    fun createSpace(cwd: String, label: String?): Created
    fun splitPane(paneId: String, right: Boolean, cwd: String?): Created
    fun toggleZoom(paneId: String): Boolean
    fun paneScroll(paneId: String): PaneScroll
    fun scrollPaneTo(paneId: String, offset: Int): PaneScroll
    fun paneRevision(paneId: String): Long
    fun paneHistory(paneId: String, cols: Int): String
    fun focusSpace(workspaceId: String)
    fun focusAgent(paneId: String)
    fun createWorktreeSpace(workspaceId: String, branch: String, label: String?): Created
    fun renameTab(tabId: String, label: String)
    fun renameSpace(workspaceId: String, label: String)
    fun renamePane(paneId: String, label: String?)
    fun renameAgent(paneId: String, name: String?)
    fun promptAgent(paneId: String, text: String)
    fun promptAndWait(paneId: String, text: String, until: Set<AgentStatus>, timeoutMs: Long): AgentStatus
    fun waitAgent(paneId: String, until: Set<AgentStatus>, timeoutMs: Long): AgentStatus
    fun sendKeys(paneId: String, keys: List<String>)
    fun paneKeys(paneId: String, keys: List<String>)
    fun integrations(): List<Integration>
    fun installIntegration(target: String)
    fun uninstallIntegration(target: String)
    fun readAgent(paneId: String): String
    fun processInfo(paneId: String): ProcInfo?
    fun closePane(paneId: String)
    fun closeTab(tabId: String)
    fun closeSpace(workspaceId: String, group: Boolean = false)
    fun removeWorktree(workspaceId: String, force: Boolean = false)
    fun startAgent(paneId: String, kind: String, name: String)

    fun markSeen(paneId: String, turn: Long): Unit = throw Unsupported(Capability.seenMarkers)
    fun markUnread(paneId: String): Unit = throw Unsupported(Capability.seenMarkers)
    fun setPinned(paneId: String, pinned: Boolean): Unit = throw Unsupported(Capability.pins)
}
