// One Herdr pane as a JediTerm TtyConnector, fed by `herdr terminal session control <pane> --takeover`.
//
// Protocol (verified upstream on herdr 0.9.1 - 0.9.3):
//  - stdout: NDJSON {"type":"terminal.frame","encoding":"ansi","bytes":<base64>,"full":bool,...} (a full
//    repaint, then incremental ones) and {"type":"terminal.closed","reason":...} ("detached" after a release).
//    Frames paint the screen and cursor only: the program's modes (mouse reporting, bracketed paste) are not
//    passed on, so JediTerm keeps its own selection and copy.
//  - stdin: NDJSON commands: terminal.input {bytes}, terminal.resize {cols, rows} (answered with a full frame),
//    terminal.scroll {direction, lines}, terminal.release (detach; the program keeps running in Herdr).
//  - One controller per terminal: --takeover replaces another one, which gets terminal.closed with reason
//    "terminal attach taken over". The tab then keeps showing the pane read-only through
//    `herdr terminal session observe <pane> --cols --rows` (verified on 0.9.3: same frames, stdin ignored, so a
//    resize means a new observer) with a note on the bottom row; the first key press takes control back.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Who holds a pane (Claims.kt), as the tab needs it. */
interface PaneHooks {
    /** This tab is taking control: record it, so the window that loses it can say who has it. */
    fun claim()
    /** This tab gave the pane up (closed, or lost it). */
    fun unclaim()
    /** Who has the pane now, if another window says so; `since` = when this tab took it. */
    fun holder(since: Long): String?
    /** Another client took the pane over; the tab now shows it read-only. */
    fun lost(who: String?)
}

class HerdrTtyConnector(
    private val binary: String,
    private val paneId: String,
    private val env: Map<String, String>,
    private val displayName: String,
    private val hooks: PaneHooks? = null,
    /** Opened by following Herdr: if another window holds the pane, only watch it instead of taking it. */
    private val polite: Boolean = false,
    /** The stream ended on its own (Herdr stopped, the pane closed), or changed hands. */
    private val onEnded: (String) -> Unit,
) : TtyConnector {
    @Volatile private var proc: Process? = null
    private var stdin: OutputStream? = null
    private val chunks = LinkedBlockingQueue<CharArray>()
    private var current: CharArray? = null
    private var pos = 0
    private val done = CountDownLatch(1)
    @Volatile private var closedByUser = false
    @Volatile private var ended = false
    private var size = TermSize(120, 32)
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteBuffer.allocate(0)

    /** Another client controls the pane: this tab only shows it. */
    @Volatile var observing = false
        private set

    /** Herdr shows the live screen (not scrolled back), so the cursor is where the program put it. */
    @Volatile var live = true

    /** Who has the pane while observing (null: a client that didn't say). */
    @Volatile private var holderName: String? = null
    private var controlSince = 0L

    /** Starts the stream on first use (JediTerm resizes, then reads); the deprecated init() isn't needed. */
    @Synchronized
    fun start() {
        if (proc != null) return
        holderName = if (polite) hooks?.holder(0) else null
        if (holderName != null) onEnded("$holderName has it; showing it read-only")
        launch(observe = holderName != null)
    }

    /** Starts a controller (taking the pane over) or an observer, replacing the current stream. */
    @Synchronized
    private fun launch(observe: Boolean) {
        if (ended || closedByUser) return
        proc?.let { old -> proc = null; stdin = null; runCatching { old.outputStream.close() }; old.destroy() }
        observing = observe
        if (!observe) {
            // Before Herdr hands it over, so the window losing it can read who took it.
            controlSince = System.currentTimeMillis()
            hooks?.claim()
        }
        val how = if (observe) listOf("observe", paneId) else listOf("control", paneId, "--takeover")
        val cmd = listOf(binary, "terminal", "session") + how + listOf("--cols", size.columns.toString(), "--rows", size.rows.toString())
        val p = try {
            ProcessBuilder(cmd).apply {
                environment().clear()
                environment().putAll(env)
                redirectError(ProcessBuilder.Redirect.PIPE)
            }.start()
        } catch (e: Exception) {
            finish("couldn't stream this pane: ${e.message}")
            return
        }
        proc = p
        stdin = p.outputStream
        val stderr = StringBuilder()
        Thread({ runCatching { p.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.append(it).append('\n') } } } }, "herdr-pane-err").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                p.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line -> if (p === proc) handleLine(line) }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (p !== proc) return@Thread // replaced (taken over, taken back, resized observer)
            val err = synchronized(stderr) { stderr.toString().trim().takeLast(500) }
            finish(if (code != 0) "couldn't stream this pane: ${err.ifEmpty { "herdr exited with $code" }}" else "the stream ended")
        }, "herdr-pane-$paneId").apply { isDaemon = true }.start()
    }

    private fun handleLine(line: String) {
        val rec = runCatching { JsonParser.parseString(line.trim()).asJsonObject }.getOrNull() ?: return
        when (rec.get("type")?.asString) {
            "terminal.frame" -> rec.get("bytes")?.asString?.let {
                // Only the first full frame clears the screen; the full ones after a resize paint just their own rows.
                // When Herdr's pane is smaller than this tab (another client set its size), the rows below would keep
                // stale text: clear before every full frame.
                if (rec.get("full")?.asBoolean == true) emit(CLEAR_SCREEN)
                emit(Base64.getDecoder().decode(it))
                if (observing) emit(takenOverNote(size.rows, holderName).toByteArray(StandardCharsets.UTF_8))
            }
            "terminal.closed" -> {
                val reason = rec.get("reason")?.asString ?: "closed"
                when {
                    !observing && isTakenOver(reason) -> {
                        hooks?.unclaim()
                        holderName = hooks?.holder(controlSince)
                        onEnded("${holderName ?: "another client"} took the pane over; showing it read-only")
                        hooks?.lost(holderName)
                        launch(observe = true)
                    }
                    else -> finish(if (reason == "detached") "detached" else "the stream ended ($reason)")
                }
            }
        }
    }

    /** Decodes UTF-8 across frame boundaries and queues the text for JediTerm. */
    @Synchronized
    private fun emit(bytes: ByteArray) {
        val input = ByteBuffer.allocate(pending.remaining() + bytes.size).put(pending).put(bytes)
        input.flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = input.slice()
        out.flip()
        if (out.hasRemaining()) chunks.put(CharArray(out.remaining()).also { out.get(it) })
    }

    private fun finish(why: String) {
        if (ended) return
        ended = true
        if (!observing) hooks?.unclaim()
        if (!closedByUser) {
            chunks.put("\u001b[0m\r\n\u001b[2m[Herdr Cockpit] $why. Close this tab, or click the pane in the Herdr sidebar to open it again.\u001b[0m\r\n".toCharArray())
            onEnded(why)
        }
        done.countDown()
    }

    val isEnded: Boolean get() = ended

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        start()
        while (true) {
            val c = current
            if (c != null && pos < c.size) {
                val n = minOf(length, c.size - pos)
                System.arraycopy(c, pos, buf, offset, n)
                pos += n
                return n
            }
            // After the stream ended, keep JediTerm's emulator idle (returning -1 would make it close the
            // widget and hide the note above).
            // JediTerm interrupts this thread when the tab closes.
            current = try { chunks.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return -1 }
                ?: if (closedByUser) return -1 else continue
            pos = 0
        }
    }

    @Synchronized
    private fun send(cmd: JsonObject) {
        if (ended) return
        val out = stdin ?: return
        runCatching {
            out.write((cmd.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Read-only: the first key takes control back and isn't sent (it could answer an agent's question).
        if (observing) return takeBack()
        live = true // input returns Herdr's view to the live screen
        send(JsonObject().apply { addProperty("type", "terminal.input"); addProperty("bytes", Base64.getEncoder().encodeToString(bytes)) })
    }

    override fun write(string: String) = write(string.toByteArray(StandardCharsets.UTF_8))

    /** Take control of the pane back from whoever has it. */
    fun takeBack() {
        if (!observing) return
        onEnded("taking control back${holderName?.let { " from $it" } ?: ""}")
        holderName = null
        launch(observe = false)
    }

    override fun resize(termSize: TermSize) {
        size = termSize
        start()
        if (observing) return launch(observe = true) // an observer ignores stdin: a new one at the new size
        send(JsonObject().apply {
            addProperty("type", "terminal.resize")
            addProperty("cols", maxOf(1, termSize.columns))
            addProperty("rows", maxOf(1, termSize.rows))
        })
    }

    /** Scrolls Herdr's scrollback for this pane (the screen JediTerm shows is Herdr's viewport). */
    fun scroll(up: Boolean, lines: Int) {
        if (up) live = false
        send(JsonObject().apply {
            addProperty("type", "terminal.scroll")
            addProperty("direction", if (up) "up" else "down")
            addProperty("lines", lines.coerceIn(1, 65535))
        })
    }

    override fun isConnected(): Boolean = !closedByUser

    override fun waitFor(): Int {
        done.await()
        return 0
    }

    override fun ready(): Boolean = (current?.let { pos < it.size } ?: false) || chunks.isNotEmpty()

    override fun getName(): String = displayName

    /** Detach (the program keeps running in Herdr), then stop the helper process. An observer just stops. */
    override fun close() {
        if (closedByUser) return
        if (!observing) {
            send(JsonObject().apply { addProperty("type", "terminal.release") })
            hooks?.unclaim()
        }
        closedByUser = true
        runCatching { stdin?.close() }
        val p = proc
        if (p != null) Thread({ if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy() }, "herdr-pane-release").apply { isDaemon = true }.start()
        done.countDown()
    }
}

fun isTakenOver(reason: String?): Boolean = reason != null && Regex("taken over", RegexOption.IGNORE_CASE).containsMatchIn(reason)

private val CLEAR_SCREEN = "\u001b[H\u001b[2J".toByteArray(StandardCharsets.UTF_8)

/** The bottom-row note while another client has the pane: drawn over the frame, cursor kept where it was. */
fun takenOverNote(rows: Int, who: String? = null): String {
    val w = who ?: "another Herdr client"
    return "\u001b7\u001b[${maxOf(1, rows)};1H\u001b[0;7m ${w.replaceFirstChar { it.uppercase() }} is using this pane · type here to take it back \u001b[0m\u001b[K\u001b8"
}
