// Plan usage of the coding agents (Claude Code, Codex, Kiro), read from each tool's own local files. A port of the
// upstream's src/usage.ts: nothing leaves the machine, no credentials are read, only numeric usage fields are kept.
// Produces the JSON panel.js draws (ProviderUsage in the upstream). No IntelliJ imports (see UsageTest).
//
//  - Codex (live): `codex app-server` (stdio JSON-RPC) `account/rateLimits/read`, authenticated by Codex itself.
//  - Codex (fallback): `rate_limits` in ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl, as of Codex's last request.
//  - Claude Code: plan limits only reach a status-line command; the opt-in one saves them to claudeCaptureFile().
//    Tokens per message are in ~/.claude/projects/**/*.jsonl (`message.usage`), summed per 5-hour block.
//  - Kiro: ~/.kiro/sessions/cli/*.json, only `user_turn_metadatas[].metering_usage` (the files also hold tool
//    arguments). The allowance isn't stored locally, so the user enters it (setting kiroMonthlyCredits).
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val HOUR = 3_600_000L

fun claudeCaptureFile(home: String) = File(home, ".herdr-cockpit/claude-usage.json")

private fun JsonElement?.obj() = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonElement?.num() = this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
private fun JsonElement?.str() = this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun parse(line: String) = runCatching { JsonParser.parseString(line).obj() }.getOrNull()
private fun time(s: String?) = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun windowLabel(minutes: Int) = when {
    minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

/** A usage window; a snapshot older than its reset has started over (0%, "reset"). */
private fun window(label: String, usedPercent: Double?, resetsAtSec: Double?, now: Long): JsonObject? {
    usedPercent ?: return null
    val resetsAt = resetsAtSec?.let { (it * 1000).toLong() }
    val reset = resetsAt != null && resetsAt <= now
    return JsonObject().apply {
        addProperty("label", label)
        addProperty("usedPercent", if (reset) 0.0 else maxOf(0.0, usedPercent))
        if (reset) addProperty("reset", true) else resetsAt?.let { addProperty("resetsAt", it) }
    }
}

private fun provider(id: String, name: String, windows: List<JsonObject> = emptyList()) = JsonObject().apply {
    addProperty("id", id)
    addProperty("name", name)
    add("windows", JsonArray().apply { windows.forEach { add(it) } })
}

/** Files under `dir` (to `depth` levels) ending in `ext`, modified at or after `since`, newest first. */
private fun recentFiles(dir: File, ext: String, since: Long, depth: Int): List<File> =
    dir.walkTopDown().maxDepth(depth + 1).filter { it.isFile && it.name.endsWith(ext) && it.lastModified() >= since }
        .sortedByDescending { it.lastModified() }.toList()

/** The last `bytes` of a file as lines (the first, possibly partial, line dropped). */
private fun tailLines(f: File, bytes: Long): List<String> = RandomAccessFile(f, "r").use { r ->
    val start = maxOf(0L, r.length() - bytes)
    val buf = ByteArray((r.length() - start).toInt())
    r.seek(start)
    r.readFully(buf)
    val lines = String(buf, Charsets.UTF_8).split('\n')
    if (start > 0) lines.drop(1) else lines
}

// ---------- Codex ----------

fun codexBinary(home: String): String =
    listOf("$home/.local/bin/codex", "$home/.codex/packages/standalone/current/bin/codex").firstOrNull { File(it).exists() } ?: findTool("codex")

/** Live limits from a short-lived `codex app-server`; null when Codex isn't installed, logged out or slow. */
fun codexLiveUsage(bin: String, timeoutMs: Long = 10_000, now: () -> Long = System::currentTimeMillis): JsonObject? {
    val proc = runCatching { ProcessBuilder(bin, "app-server").redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() ?: return null
    try {
        val out = proc.outputStream.bufferedWriter()
        val send = { s: String -> out.write(s + "\n"); out.flush() }
        val result = CompletableFuture.supplyAsync {
            send("""{"id":1,"method":"initialize","params":{"clientInfo":{"name":"herdr-cockpit","title":"Herdr Cockpit","version":"1"}}}""")
            for (line in proc.inputStream.bufferedReader().lineSequence()) {
                val m = parse(line) ?: continue
                when (m.get("id")?.takeIf { it.isJsonPrimitive }?.asString) {
                    "1" -> {
                        if (m.has("error")) return@supplyAsync null
                        send("""{"method":"initialized"}""")
                        send("""{"id":2,"method":"account/rateLimits/read","params":{"excludeResetCreditDetails":true}}""")
                    }
                    "2" -> return@supplyAsync codexRateLimits(m.get("result").obj()?.get("rateLimits").obj(), now())
                }
            }
            null
        }
        return runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    } finally {
        proc.destroyForcibly()
    }
}

/** `account/rateLimits/read`'s rateLimits as a usage entry. */
fun codexRateLimits(rl: JsonObject?, now: Long): JsonObject? {
    rl ?: return null
    val windows = listOf("primary", "secondary").mapNotNull { k ->
        val w = rl.get(k).obj() ?: return@mapNotNull null
        window(windowLabel(w.get("windowDurationMins").num()?.toInt() ?: 0), w.get("usedPercent").num(), w.get("resetsAt").num(), now)
    }
    return provider("codex", "Codex", windows).apply {
        rl.get("planType").str()?.let { addProperty("plan", it) }
        addProperty("asOf", now)
        addProperty("live", true)
    }
}

fun codexUsage(home: String, now: Long): JsonObject? {
    val root = File(home, ".codex/sessions")
    if (!root.isDirectory) return null
    val files = recentFiles(root, ".jsonl", now - 30 * 24 * HOUR, 3)
    for (f in files.take(12)) {
        val lines = runCatching { tailLines(f, 512 * 1024) }.getOrDefault(emptyList())
        for (line in lines.asReversed()) {
            if ("\"rate_limits\"" !in line) continue
            val o = parse(line) ?: continue
            val rl = o.get("payload").obj()?.get("rate_limits").obj() ?: continue
            val windows = listOf("primary", "secondary").mapNotNull { k ->
                val w = rl.get(k).obj() ?: return@mapNotNull null
                val minutes = w.get("window_minutes").num() ?: return@mapNotNull null
                window(windowLabel(minutes.toInt()), w.get("used_percent").num(), w.get("resets_at").num(), now)
            }
            return provider("codex", "Codex", windows).apply {
                rl.get("plan_type").str()?.let { addProperty("plan", it) }
                time(o.get("timestamp").str())?.let { addProperty("asOf", it) }
            }
        }
    }
    return if (files.isNotEmpty()) provider("codex", "Codex") else null
}

// ---------- Claude Code ----------

data class TokenEntry(val ts: Long, val tokens: Long, val cacheRead: Long)
data class Block(val startsAt: Long, val endsAt: Long, val tokens: Long, val cacheRead: Long, val exact: Boolean)

/** The current 5-hour block, the way Claude's session windows start: at the hour of the first message. */
fun currentBlock(entries: List<TokenEntry>, now: Long): Block? {
    var block: Block? = null
    for (e in entries.sortedBy { it.ts }) {
        val b = block?.takeIf { e.ts < it.endsAt } ?: (e.ts / HOUR * HOUR).let { Block(it, it + 5 * HOUR, 0, 0, false) }
        block = b.copy(tokens = b.tokens + e.tokens, cacheRead = b.cacheRead + e.cacheRead)
    }
    return block?.takeIf { it.endsAt > now }
}

/** A transcript's usage lines as (dedupe key, entry); null when the line has none. */
private fun claudeEntry(line: String): Pair<String, TokenEntry>? {
    if ("\"usage\"" !in line) return null
    val o = parse(line) ?: return null
    val msg = o.get("message").obj() ?: return null
    val u = msg.get("usage").obj() ?: return null
    val ts = time(o.get("timestamp").str()) ?: return null
    fun n(k: String) = u.get(k).num()?.toLong() ?: 0L
    val key = "${msg.get("id").str() ?: ""}:${o.get("requestId").str() ?: ""}"
    return key to TokenEntry(ts, n("input_tokens") + n("output_tokens") + n("cache_creation_input_tokens"), n("cache_read_input_tokens"))
}

/** A transcript read up to `offset` (the end of its last full line), and the usage entries found there. */
private class Transcript(var offset: Long = 0, val entries: ArrayList<Pair<String, TokenEntry>> = ArrayList())

/** Shared by every window's poll: a transcript (often tens of MB) is read once, then only what was appended. */
private val transcripts = HashMap<String, Transcript>()

/** The usage entries of `files` since `since`: cached ones plus what was appended (a shrunk file is read again). */
private fun claudeEntries(files: List<File>, since: Long): List<Pair<String, TokenEntry>> = synchronized(transcripts) {
    val out = ArrayList<Pair<String, TokenEntry>>()
    for (f in files) runCatching {
        val t = transcripts[f.path]?.takeIf { f.length() >= it.offset } ?: Transcript().also { transcripts[f.path] = it }
        val bytes = RandomAccessFile(f, "r").use { r ->
            r.seek(t.offset)
            ByteArray((r.length() - t.offset).toInt()).also { r.readFully(it) }
        }
        val full = bytes.lastIndexOf('\n'.code.toByte()) + 1
        String(bytes, 0, full, Charsets.UTF_8).lineSequence().mapNotNullTo(t.entries) { claudeEntry(it) }
        t.offset += full
        t.entries.removeAll { it.second.ts < since }
        out += t.entries
        // A last line still being written counts now, and is cached once it's complete.
        if (full < bytes.size) claudeEntry(String(bytes, full, bytes.size - full, Charsets.UTF_8))?.let { out += it }
    }
    transcripts.keys.retainAll(files.map { it.path }.toSet())
    out.filter { it.second.ts >= since }
}

fun claudeUsage(home: String, now: Long): JsonObject? {
    if (!File(home, ".claude").isDirectory) return null
    val usage = provider("claude", "Claude Code")
    val capture = claudeCaptureFile(home)
    val rl = if (capture.isFile) runCatching { JsonParser.parseString(capture.readText()).obj()?.get("rate_limits").obj() }.getOrNull() else null
    var fiveHourReset: Long? = null
    if (rl != null) {
        val windows = listOf("5h" to "five_hour", "7d" to "seven_day").mapNotNull { (label, k) ->
            val w = rl.get(k).obj()
            window(label, w?.get("used_percentage").num(), w?.get("resets_at").num(), now)
        }
        usage.add("windows", JsonArray().apply { windows.forEach { add(it) } })
        usage.addProperty("asOf", capture.lastModified())
        fiveHourReset = windows.firstOrNull { it.get("label").asString == "5h" }?.get("resetsAt")?.asLong
    } else {
        usage.addProperty("canSetUpLimits", true)
    }

    // A block is at most 5h old, so transcripts untouched for 10h can't matter.
    val since = now - 10 * HOUR
    val seen = HashSet<String>()
    val entries = ArrayList<TokenEntry>()
    for ((key, e) in claudeEntries(recentFiles(File(home, ".claude/projects"), ".jsonl", since, 4), since)) {
        // Streaming writes the same message more than once.
        if (key == ":" || seen.add(key)) entries += e
    }
    // Claude reports when its 5-hour session resets: count exactly that window. Otherwise estimate it.
    val block = fiveHourReset?.let { end ->
        val inWindow = entries.filter { it.ts >= end - 5 * HOUR }
        Block(end - 5 * HOUR, end, inWindow.sumOf { it.tokens }, inWindow.sumOf { it.cacheRead }, true)
    } ?: currentBlock(entries, now)
    block?.let { b ->
        usage.add("block", JsonObject().apply {
            addProperty("tokens", b.tokens)
            addProperty("cacheRead", b.cacheRead)
            addProperty("startsAt", b.startsAt)
            addProperty("endsAt", b.endsAt)
            addProperty("exact", b.exact)
        })
    }
    return usage
}

// ---------- Kiro ----------

/** A Kiro session file's turns as (end time, credits), kept until the file changes (it's rewritten whole). */
private class KiroSession(val modified: Long, val length: Long, val turns: List<Pair<Long, Double>>)
private val kiroSessions = HashMap<String, KiroSession>()

private fun kiroTurns(files: List<File>): List<Pair<Long, Double>> = synchronized(kiroSessions) {
    val out = ArrayList<Pair<Long, Double>>()
    for (f in files) {
        val (modified, length) = f.lastModified() to f.length()
        val s = kiroSessions[f.path]?.takeIf { it.modified == modified && it.length == length } ?: run {
            val turns = runCatching {
                JsonParser.parseString(f.readText()).obj()?.get("session_state").obj()?.get("conversation_metadata").obj()?.get("user_turn_metadatas")
            }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { t ->
                val turn = t.obj() ?: return@mapNotNull null
                val ts = time(turn.get("end_timestamp").str()) ?: return@mapNotNull null
                ts to (turn.get("metering_usage")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.obj()?.takeIf { m -> m.get("unit").str() == "credit" }?.get("value").num() }?.sum() ?: 0.0)
            }.orEmpty()
            KiroSession(modified, length, turns).also { kiroSessions[f.path] = it }
        }
        out += s.turns
    }
    kiroSessions.keys.retainAll(files.map { it.path }.toSet())
    out
}

fun kiroUsage(home: String, now: Long, monthlyLimit: Int, zone: ZoneId = ZoneId.systemDefault()): JsonObject? {
    val dir = File(home, ".kiro/sessions/cli")
    if (!dir.isDirectory) return null
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val monthStart = day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    var month = 0.0
    var today = 0.0
    val files = recentFiles(dir, ".json", monthStart, 0)
    for ((ts, used) in kiroTurns(files)) {
        if (ts < monthStart) continue
        month += used
        if (ts >= dayStart) today += used
    }
    // Kiro's allowance renews monthly; the calendar month is the closest local stand-in for the billing cycle.
    val nextMonth = day.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val windows = if (monthlyLimit > 0) listOf(JsonObject().apply {
        addProperty("label", "mo")
        addProperty("usedPercent", month / monthlyLimit * 100)
        addProperty("resetsAt", nextMonth)
    }) else emptyList()
    return provider("kiro", "Kiro", windows).apply {
        add("credits", JsonObject().apply {
            addProperty("today", today)
            addProperty("month", month)
            if (monthlyLimit > 0) addProperty("limit", monthlyLimit)
        })
    }
}

/** Usage of each agent in `only` that has data, in a stable order. Agents outside `only` aren't read at all. */
fun readUsage(home: String, now: Long, only: Set<String>, codexLive: JsonObject?, kiroMonthlyCredits: Int): JsonArray = JsonArray().apply {
    listOf(
        "claude" to { claudeUsage(home, now) },
        "codex" to { codexLive ?: codexUsage(home, now) },
        "kiro" to { kiroUsage(home, now, kiroMonthlyCredits) },
    ).forEach { (id, read) -> if (id in only) runCatching(read).getOrNull()?.let { add(it) } }
}

/**
 * The opt-in Claude Code status line (same script as the VS Code extension's): saves the JSON Claude Code passes it
 * (only `rate_limits` with jq, else as-is) and prints the limits.
 */
fun claudeStatusLineScript(capture: File) = """#!/bin/sh
# Installed by Herdr Cockpit. Claude Code shares plan usage (rate_limits) only with a status
# line command: this saves it for the Herdr sidebar and prints it. Remove "statusLine" from
# ~/.claude/settings.json to turn it off.
f="$capture"
input=${'$'}(cat)
if command -v jq >/dev/null 2>&1; then
  printf '%s' "${'$'}input" | jq -c '{rate_limits}' > "${'$'}f.tmp" 2>/dev/null && mv "${'$'}f.tmp" "${'$'}f"
  printf '%s' "${'$'}input" | jq -r '[(.rate_limits.five_hour.used_percentage // empty | "5h \(floor)%"), (.rate_limits.seven_day.used_percentage // empty | "7d \(floor)%")] | join(" · ")' 2>/dev/null
else
  printf '%s' "${'$'}input" > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f"
fi
"""
