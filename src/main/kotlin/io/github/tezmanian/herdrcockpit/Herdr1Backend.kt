// Herdr 1 (the `herdr` server, protocol HERDR_PROTOCOL): the socket client, session.snapshot, the pane stream
// through `herdr terminal session`. Settings come in as functions, so this file has no IntelliJ imports.
package io.github.tezmanian.herdrcockpit

class Herdr1Backend(private val binary: () -> String, private val customSocket: () -> String?) : Backend {
    private val client = HerdrClient { socketPath() }
    private val watcher = ActivityWatcher(herdr1Read(client))

    override val kind = BackendKind.HERDR1
    override val capabilities: Set<Capability> = Capability.entries.toSet() - Capability.seenMarkers - Capability.pins
    override val actions = HerdrActions(client)

    fun socketPath(): String = resolveSocketPath(customSocket())

    override fun reachable() = serverStatus(binary(), env())?.running == true
    override fun diagnose(e: Exception) = diagnose(binary(), socketPath(), customSocket() != null, env(), e)
    override fun env() = herdrEnv(customSocket()?.let { resolveSocketPath(it) })

    override fun snapshot() = normalize(client.request("session.snapshot"))
    override fun subscriptionKey(model: Model?) = model?.paneIds().orEmpty().sorted().joinToString(" ")
    override fun subscribe(model: Model?, onChange: () -> Unit, onEnd: (Exception?) -> Unit) =
        client.subscribe(subscriptionsFor(model?.paneIds().orEmpty()), { onChange() }, onEnd)

    override fun attachCommand(paneId: String, observe: Boolean, cols: Int, rows: Int): List<String> {
        val how = if (observe) listOf("observe", paneId) else listOf("control", paneId, "--takeover")
        return listOf(binary(), "terminal", "session") + how + listOf("--cols", cols.toString(), "--rows", rows.toString())
    }

    override fun activity() = watcher

    override fun dispose() {
        client.dispose()
        watcher.shutdown()
    }
}
