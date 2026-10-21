// Claude Code's saved sessions for a folder, for "Switch Claude Session…". No IntelliJ imports.
//
// Claude Code keeps one transcript per session in ~/.claude/projects/<cwd, every non-alphanumeric char as ->/<id>.jsonl.
// Its title records ("custom-title" from /rename, "ai-title", "last-prompt") are appended again and again, so the
// newest is near the end; transcripts reach 100+ MB, so only the tail is read.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A saved Claude Code session: its id (what `/resume` takes), a title, when it last changed. */
data class ClaudeSession(val id: String, val title: String, val modified: Long)

private val SESSION_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private const val TAIL_BYTES = 256 * 1024L

fun claudeProjectDir(home: File, cwd: String) = File(home, ".claude/projects/" + cwd.replace(Regex("[^A-Za-z0-9]"), "-"))

/** The newest `limit` sessions in `dir`, newest first. Only uuid-named files: the id is typed into the agent. */
fun claudeSessions(dir: File, limit: Int = 30): List<ClaudeSession> =
    (dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && SESSION_ID.matches(f.nameWithoutExtension) } ?: emptyArray())
        .map { it to it.lastModified() }
        .sortedByDescending { it.second }
        .take(limit)
        .map { (f, modified) -> ClaudeSession(f.nameWithoutExtension, sessionTitle(f) ?: "(untitled)", modified) }

// shortcut: a title written only before the last 256 KB is missed (shows the last prompt or "(untitled)"), upgrade if
// that turns out common
private fun sessionTitle(file: File): String? {
    val tail = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val from = maxOf(0L, f.length() - TAIL_BYTES)
            val bytes = ByteArray((f.length() - from).toInt())
            f.seek(from)
            f.readFully(bytes)
            String(bytes, Charsets.UTF_8).let { if (from > 0) it.substringAfter('\n') else it }
        }
    }.getOrNull() ?: return null
    val found = HashMap<String, String>()
    for (line in tail.lineSequence()) {
        val (type, key) = when {
            "\"custom-title\"" in line -> "custom-title" to "customTitle"
            "\"ai-title\"" in line -> "ai-title" to "aiTitle"
            "\"last-prompt\"" in line -> "last-prompt" to "lastPrompt"
            else -> continue
        }
        val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
        if (o.get("type")?.asString != type) continue
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let { found[type] = it }
    }
    return (found["custom-title"] ?: found["ai-title"] ?: found["last-prompt"])?.lineSequence()?.first()?.take(80)
}

/** "09:12" today, "yesterday", else "Oct 20". */
fun sessionWhen(modified: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(modified).atZone(zone)
    return when (t.toLocalDate()) {
        today -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        today.minusDays(1) -> "yesterday"
        else -> t.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    }
}
