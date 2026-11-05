// herdr-core (Herdr 2) socket client: the same newline-delimited JSON transport as Herdr 1 (HerdrClient), with
// core's error codes (`error.data.kind`) and its event stream (`event`, `heartbeat` every 15 s, `missed`).
// No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** A quiet event stream counts as lost after this long (the core sends a heartbeat every 15 s). */
const val CORE_IDLE_MS = 45_000L

/**
 * herdr-core's socket: the setting, HC_SOCKET, then the session socket under XDG_RUNTIME_DIR, /run/user/<uid>
 * (if it exists) or the state folder. The session is HC_SESSION or "default".
 */
// shortcut: pipe name unverified on Windows, verify with hc server status
fun resolveCoreSocket(override: String?, env: Map<String, String> = System.getenv(), uid: Long? = currentUid()): String {
    val home = System.getProperty("user.home")
    if (!override.isNullOrBlank()) return if (override.startsWith("~")) home + override.trim().substring(1) else override.trim()
    env["HC_SOCKET"]?.takeIf { it.isNotEmpty() }?.let { return it }
    val s = env["HC_SESSION"]?.takeIf { it.isNotEmpty() } ?: "default"
    env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotEmpty() }?.let { return "$it/herdr-core/$s.sock" }
    if (uid != null && File("/run/user/$uid").isDirectory) return "/run/user/$uid/herdr-core/$s.sock"
    val state = env["XDG_STATE_HOME"]?.takeIf { it.isNotEmpty() } ?: "$home/.local/state"
    return "$state/herdr-core/$s/$s.sock"
}

private fun currentUid(): Long? = runCatching { (Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Number).toLong() }.getOrNull()

/** Core errors carry a generic numeric code; the stable one is `data.kind` (not_found, unavailable, …). */
fun coreErrorOf(msg: JsonObject): HerdrError? {
    val e = msg.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val kind = e.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
    return HerdrError(kind ?: e.get("code")?.asString ?: "error", e.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "unknown error")
}

data class CoreHello(val version: String?)

class CoreClient(val socketPath: () -> String, private val idleMs: Long = CORE_IDLE_MS) {
    /** An explicit socket, no resolution (CoreLiveTest, `-PcoreSocket`). */
    constructor(socket: String) : this({ socket })

    private val client = HerdrClient(::coreErrorOf, socketPath)

    fun request(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 5000): JsonObject = client.request(method, params, timeoutMs)

    /** A core this plugin can read: protocol generation 1 with an event stream. Null when unreachable or different. */
    fun hello(): CoreHello? = runCatching {
        val r = request("hello", timeoutMs = 2000)
        val features = r.get("features")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        if (r.get("generation")?.takeIf { it.isJsonPrimitive }?.asInt != 1 || "events" !in features) null
        else CoreHello(r.get("version")?.takeIf { it.isJsonPrimitive }?.asString)
    }.getOrNull()

    /** Unfiltered (so layout `doc` events arrive); every event or `missed` means "re-read". */
    fun subscribe(onChange: () -> Unit, onEnd: (Exception?) -> Unit): Subscription =
        client.subscribe(JsonObject(), idleMs, { msg ->
            when (msg.get("method")?.takeIf { it.isJsonPrimitive }?.asString) {
                "event", "missed" -> onChange()
            }
        }, onEnd)

    fun dispose() = client.dispose()
}
