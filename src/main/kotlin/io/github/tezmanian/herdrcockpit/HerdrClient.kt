// Herdr socket API client. Protocol: newline-delimited JSON over a Unix socket (on Windows a named pipe). One request per line,
// responses echo the request id. events.subscribe keeps the connection open.
// No IntelliJ imports, so it can be tested standalone (see HerdrClientTest).
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HerdrError(val code: String, message: String) : Exception("$code: $message")

fun interface Subscription {
    fun dispose()
}

private fun expandHome(p: String): String =
    if (p.startsWith("~")) System.getProperty("user.home") + p.substring(1) else p

val IS_WINDOWS = System.getProperty("os.name").lowercase().startsWith("windows")

/** Mirrors Herdr's resolution order: HERDR_SOCKET_PATH, HERDR_SESSION, default (on Windows under %APPDATA%). */
fun resolveSocketPath(override: String?, env: Map<String, String> = System.getenv(), windows: Boolean = IS_WINDOWS): String {
    if (!override.isNullOrBlank()) return expandHome(override)
    env["HERDR_SOCKET_PATH"]?.takeIf { it.isNotEmpty() }?.let { return it }
    val home = System.getProperty("user.home")
    val config = if (windows) env["APPDATA"]?.takeIf { it.isNotEmpty() } ?: "$home/AppData/Roaming"
        else env["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() } ?: "$home/.config"
    val base = Paths.get(config, "herdr")
    val session = env["HERDR_SESSION"]?.takeIf { it.isNotEmpty() }
    return (if (session != null) base.resolve("sessions").resolve(session).resolve("herdr.sock") else base.resolve("herdr.sock")).toString()
}

/**
 * Windows Herdr serves a named pipe named after the full logical socket path (as upstream's client connects to it);
 * the CLI and HERDR_SOCKET_PATH keep the path itself.
 */
fun pipeName(socket: String): String =
    if (socket.startsWith("\\\\.\\pipe\\")) socket else "\\\\.\\pipe\\" + socket.replace('/', '\\').replace(Regex("\\\\{2,}"), "\\\\")

private val seq = AtomicLong()
private fun nextId(kind: String) = "hc_${kind}_${ProcessHandle.current().pid()}_${seq.incrementAndGet()}"

/** One connection to Herdr. close() ends a blocked read (on a pipe: see [open]). */
private class Conn(private val input: InputStream, private val output: OutputStream, private val closer: () -> Unit) : java.io.Closeable {
    fun send(obj: JsonObject) {
        output.write((obj.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun lines(): BufferedReader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))

    override fun close() = closer()
}

private fun open(socket: String): Conn {
    if (IS_WINDOWS) {
        val pipe = RandomAccessFile(pipeName(socket), "rw")
        // shortcut: Java can't cancel a read on a synchronous pipe, and closing it waits for that read; so close on a side
        // thread, and a timed-out read ends only when Herdr answers or exits (requests time out anyway, see request()).
        // Upgrade to overlapped I/O if java.nio ever accepts these pipe names (they contain the path's ':').
        return Conn(FileInputStream(pipe.fd), FileOutputStream(pipe.fd)) {
            Thread({ runCatching { pipe.close() } }, "herdr-pipe-close").apply { isDaemon = true }.start()
        }
    }
    val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
    try {
        ch.connect(UnixDomainSocketAddress.of(Path.of(socket)))
    } catch (e: Exception) {
        ch.close()
        throw e
    }
    return Conn(Channels.newInputStream(ch), Channels.newOutputStream(ch)) { ch.close() }
}

private fun parseLine(line: String): JsonObject? =
    runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()

private fun errorOf(msg: JsonObject): HerdrError? {
    val e = msg.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return HerdrError(e.get("code")?.asString ?: "error", e.get("message")?.asString ?: "unknown error")
}

class HerdrClient(private val socketPath: () -> String) {
    // Closes sockets whose request ran out of time (blocking channels have no read timeout).
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "herdr-timeouts").apply { isDaemon = true } }
    // Requests in flight, so dispose() can end long waits (agent.wait) instead of leaving them to their timeout.
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Conn>()
    // On Windows a request runs here, so its caller gets the timeout even while a pipe read can't be cut off.
    private val pipeWorkers by lazy { Executors.newCachedThreadPool { r -> Thread(r, "herdr-pipe").apply { isDaemon = true } } }

    /** One request on a short-lived connection. Blocks the calling thread; never call it on the EDT. */
    fun request(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 5000): JsonObject {
        if (!IS_WINDOWS) return exchange(method, params, timeoutMs)
        val f = pipeWorkers.submit(Callable { exchange(method, params, timeoutMs) })
        try {
            return f.get(timeoutMs + 1000, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw HerdrError("timeout", "herdr $method timed out")
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun exchange(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val id = nextId("req")
        val ch = open(socketPath())
        inFlight += ch
        val timedOut = AtomicBoolean(false)
        val guard = timer.schedule({ timedOut.set(true); runCatching { ch.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            ch.send(JsonObject().apply { addProperty("id", id); addProperty("method", method); add("params", params) })
            val reader = ch.lines()
            while (true) {
                val line = reader.readLine() ?: throw HerdrError("closed", "herdr socket closed before $method responded")
                val msg = parseLine(line.trim()) ?: continue
                val mid = msg.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                if (mid != id && mid != "") continue
                errorOf(msg)?.let { throw it }
                val result = msg.get("result")
                return if (result != null && result.isJsonObject) result.asJsonObject else JsonObject()
            }
        } catch (e: Exception) {
            if (timedOut.get()) throw HerdrError("timeout", "herdr $method timed out")
            throw e
        } finally {
            guard.cancel(false)
            inFlight -= ch
            runCatching { ch.close() }
        }
    }

    /** Ends the requests in flight (they fail) and the timeout thread. Subscriptions are disposed on their own. */
    fun dispose() {
        timer.shutdownNow()
        inFlight.forEach { runCatching { it.close() } }
    }

    /**
     * Long-lived event subscription on its own thread. Every pushed event goes to onEvent; callers treat
     * events as "something changed, re-read" signals (what Herdr's docs recommend). onEnd fires once on
     * close or error, but not after dispose().
     */
    fun subscribe(subscriptions: List<JsonObject>, onEvent: (JsonObject) -> Unit, onEnd: (Exception?) -> Unit): Subscription {
        val id = nextId("sub")
        val disposed = AtomicBoolean(false)
        // Closed from the caller's thread by dispose().
        val channel = java.util.concurrent.atomic.AtomicReference<Conn?>()
        val thread = Thread({
            var err: Exception? = null
            try {
                val ch = open(socketPath())
                channel.set(ch)
                if (disposed.get()) { ch.close(); return@Thread }
                val params = JsonObject().apply { add("subscriptions", com.google.gson.JsonArray().apply { subscriptions.forEach { add(it) } }) }
                ch.send(JsonObject().apply { addProperty("id", id); addProperty("method", "events.subscribe"); add("params", params) })
                val reader = ch.lines()
                var acked = false
                while (true) {
                    val line = reader.readLine() ?: break
                    val msg = parseLine(line.trim()) ?: continue
                    errorOf(msg)?.let { throw it }
                    if (!acked && msg.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id && msg.has("result")) {
                        acked = true
                        continue
                    }
                    onEvent(msg)
                }
            } catch (e: Exception) {
                err = e
            } finally {
                runCatching { channel.get()?.close() }
            }
            if (!disposed.get()) onEnd(err)
        }, "herdr-events")
        thread.isDaemon = true
        thread.start()
        return Subscription {
            disposed.set(true)
            runCatching { channel.get()?.close() }
        }
    }
}

/**
 * Lifecycle events that trigger a refresh. They take no parameters. Not `pane.updated`: it fires on every
 * terminal-title change (agents animate their titles). Pane renames made in Herdr emit no event at all
 * (herdr 0.9.3) and arrive with the safety poll.
 */
private val LIFECYCLE_EVENTS = listOf(
    "workspace.created", "workspace.closed", "workspace.renamed", "workspace.focused", "workspace.updated",
    "workspace.metadata_updated", "workspace.moved", "workspace.reordered",
    "worktree.created", "worktree.opened", "worktree.removed",
    "tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
    "pane.created", "pane.closed", "pane.moved", "pane.exited", "pane.agent_detected",
)

/**
 * The full events.subscribe list: lifecycle events plus each pane's agent status. Status changes fire no
 * global event, `pane.agent_status_changed` requires a pane_id, and one unknown pane id rejects the whole
 * request. So subscribe with the panes of the latest snapshot and resubscribe when they change.
 */
fun subscriptionsFor(paneIds: Collection<String>): List<JsonObject> =
    LIFECYCLE_EVENTS.map { JsonObject().apply { addProperty("type", it) } } +
        paneIds.map { JsonObject().apply { addProperty("type", "pane.agent_status_changed"); addProperty("pane_id", it) } }
