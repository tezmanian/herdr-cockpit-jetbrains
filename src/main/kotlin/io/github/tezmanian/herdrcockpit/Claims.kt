// Pane claims, shared with the VS Code extension (upstream src/hubFiles.ts, same files and fields): Herdr lets one
// client control a pane and tells the one it replaces only "taken over". So each window records the panes it takes
// control of in ~/.herdr-cockpit/claims/<hash>.json, and the window that lost one can say who has it now.
// Windows of one IDE share a process (pid), so `holders` tells them apart in-process. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class PaneClaim(val editor: String, val scheme: String, val pid: Long, val at: Long)

/** The JetBrains windows' claims, by claim file: which window (any per-window object) holds the pane. */
private val holders = ConcurrentHashMap<String, Any>()

class Claims(private val home: String = System.getProperty("user.home"), private val isAlive: (Long) -> Boolean = { ProcessHandle.of(it).isPresent }) {
    /** One file per pane of a Herdr session (its socket): pane ids repeat across sessions. */
    fun file(socket: String, pane: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("$socket\n$pane".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(home, ".herdr-cockpit/claims/$hash.json")
    }

    fun write(socket: String, pane: String, claim: PaneClaim, window: Any) {
        val f = file(socket, pane)
        holders[f.path] = window
        // best effort: without it the other window just can't name us
        runCatching {
            f.parentFile.mkdirs()
            val tmp = File(f.path + ".${claim.pid}.tmp")
            val json = JsonObject().apply { addProperty("editor", claim.editor); addProperty("scheme", claim.scheme); addProperty("pid", claim.pid); addProperty("at", claim.at) }
            tmp.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n")
            tmp.renameTo(f)
        }
    }

    fun read(socket: String, pane: String): PaneClaim? = runCatching {
        val o = JsonParser.parseString(file(socket, pane).readText()).asJsonObject
        PaneClaim(o.get("editor").asString.take(80), o.get("scheme").asString, o.get("pid").asLong, o.get("at").asLong)
    }.getOrNull()

    /** Drop this window's claim (it released the pane, or lost it); another window's newer claim stays. */
    fun remove(socket: String, pane: String, pid: Long, window: Any) {
        val f = file(socket, pane)
        if (holders[f.path] !== window) return
        holders.remove(f.path)
        if (read(socket, pane)?.pid == pid) f.delete()
    }

    /** Remove claims whose window is gone (windows that quit can't remove their own). */
    fun prune() {
        File(home, ".herdr-cockpit/claims").listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            val pid = runCatching { JsonParser.parseString(f.readText()).asJsonObject.get("pid").asLong }.getOrNull()
            if (pid == null || !isAlive(pid)) f.delete()
        }
    }

    /**
     * Who has the pane, from this window's point of view: another live window's claim. `since` = when this window
     * took the pane: a claim older than that is from before (that window lost the pane to us since).
     */
    fun holder(socket: String, pane: String, me: PaneClaim, window: Any, since: Long = 0): String? {
        val c = read(socket, pane) ?: return null
        if (c.at < since || !isAlive(c.pid)) return null
        if (c.pid == me.pid) return holders[file(socket, pane).path]?.takeIf { it !== window }?.let { "another ${c.editor} window" }
        return if (c.scheme == me.scheme) "another ${c.editor} window" else c.editor
    }
}
