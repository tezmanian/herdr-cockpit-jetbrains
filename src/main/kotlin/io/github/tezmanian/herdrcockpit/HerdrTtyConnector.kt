// One Herdr pane as a JediTerm TtyConnector, fed by `herdr terminal session control <pane> --takeover`.
//
// Protocol (verified upstream on herdr 0.9.1 - 0.9.3):
//  - stdout: NDJSON {"type":"terminal.frame","encoding":"ansi","bytes":<base64>,"full":bool,...} (a full
//    repaint, then incremental ones) and {"type":"terminal.closed","reason":...} ("detached" after a release).
//    Frames paint the screen and cursor only: the program's modes (mouse reporting, bracketed paste) are not
//    passed on, so JediTerm keeps its own selection and copy.
//  - stdin: NDJSON commands: terminal.input {bytes}, terminal.resize {cols, rows} (answered with a full frame),
//    terminal.scroll {direction, lines}, terminal.release (detach; the program keeps running in Herdr).
//  - One controller per terminal: --takeover replaces another one.
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

class HerdrTtyConnector(
    private val binary: String,
    private val paneId: String,
    private val env: Map<String, String>,
    private val displayName: String,
    /** The stream ended on its own (Herdr stopped, the pane closed, another client took over). */
    private val onEnded: (String) -> Unit,
) : TtyConnector {
    private var proc: Process? = null
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

    override fun init(q: com.jediterm.terminal.Questioner?): Boolean {
        start()
        return true
    }

    @Synchronized
    private fun start() {
        if (proc != null) return
        val cmd = listOf(binary, "terminal", "session", "control", paneId, "--takeover", "--cols", size.columns.toString(), "--rows", size.rows.toString())
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
                p.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line -> handleLine(line) }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            val err = synchronized(stderr) { stderr.toString().trim().takeLast(500) }
            finish(if (code != 0) "couldn't stream this pane: ${err.ifEmpty { "herdr exited with $code" }}" else "the stream ended")
        }, "herdr-pane-$paneId").apply { isDaemon = true }.start()
    }

    private fun handleLine(line: String) {
        val rec = runCatching { JsonParser.parseString(line.trim()).asJsonObject }.getOrNull() ?: return
        when (rec.get("type")?.asString) {
            "terminal.frame" -> rec.get("bytes")?.asString?.let { emit(Base64.getDecoder().decode(it)) }
            "terminal.closed" -> {
                val reason = rec.get("reason")?.asString ?: "closed"
                finish(if (reason == "detached") "detached" else "the stream ended ($reason)")
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
        if (!closedByUser) {
            chunks.put("\u001b[0m\r\n\u001b[2m[Herdr Cockpit] $why. Close this tab, or click the pane in the Herdr sidebar to open it again.\u001b[0m\r\n".toCharArray())
            onEnded(why)
        }
        done.countDown()
    }

    val isEnded: Boolean get() = ended

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
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
            current = chunks.poll(200, TimeUnit.MILLISECONDS) ?: if (closedByUser) return -1 else continue
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
        send(JsonObject().apply { addProperty("type", "terminal.input"); addProperty("bytes", Base64.getEncoder().encodeToString(bytes)) })
    }

    override fun write(string: String) = write(string.toByteArray(StandardCharsets.UTF_8))

    override fun resize(termSize: TermSize) {
        size = termSize
        send(JsonObject().apply {
            addProperty("type", "terminal.resize")
            addProperty("cols", maxOf(1, termSize.columns))
            addProperty("rows", maxOf(1, termSize.rows))
        })
    }

    /** Scrolls Herdr's scrollback for this pane (the screen JediTerm shows is Herdr's viewport). */
    fun scroll(up: Boolean, lines: Int) {
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

    /** Detach (the program keeps running in Herdr), then stop the helper process. */
    override fun close() {
        if (closedByUser) return
        send(JsonObject().apply { addProperty("type", "terminal.release") })
        closedByUser = true
        runCatching { stdin?.close() }
        val p = proc
        if (p != null) Thread({ if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy() }, "herdr-pane-release").apply { isDaemon = true }.start()
        done.countDown()
    }
}
