// What is a pane doing right now? The snapshot only says a terminal exists, so for shell panes we ask
// Herdr for the foreground process (pane.process_info) and recent output (pane.read), and boil that down to
// "running X" or "at the prompt, last ran Y", plus the last lines of output. Agents get the tail of their
// screen the same way. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class PaneActivity(
    /** Shells only: "prompt" (waiting for input) or "running" (a program owns the terminal). */
    val state: String? = null,
    /** What's running now, or the last command run at the prompt. */
    val command: String? = null,
    /** Text typed at the prompt but not run yet. */
    val typed: String? = null,
    /** Last meaningful output lines, oldest first. */
    val output: List<String> = emptyList(),
    val failed: Boolean = false,
    /** When this pane's output last changed, as observed by polling (epoch ms). */
    val changedAt: Long? = null,
)

data class Proc(val pid: Long?, val name: String, val argv: List<String>, val cmdline: String?)
data class ProcInfo(val fgGroup: Long?, val procs: List<Proc>)

private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "tcsh", "csh", "nu", "pwsh", "xonsh", "elvish")
private val INTERPRETERS = Regex("^(python[\\d.]*|node|bun|deno|ruby|perl|php|java|uv|uvx|npx|npm|pnpm|yarn)$")
/** Full-screen programs whose screen isn't useful as an output preview. */
private val FULLSCREEN = Regex("^(n?vim?|nano|emacs|less|more|man|top|htop|btop|lazygit|tig|tmux|fzf|k9s|herdr.*)$")
private val FAILURE = Regex(
    "\\b(error|errors|failed|failure|refused|denied|not found|no such file|no matches found|traceback|exception|fatal|panic|timed out|cannot|can't)\\b",
    RegexOption.IGNORE_CASE,
)
/** [(venv) ]user@host dir %  |  ❯  |  ➜ ... with whatever was typed after it in group 1. */
private val PROMPT = Regex("^(?:\\([^)\\s]+\\)\\s+)*(?:\\S+@\\S+[^%$#\\n]*?[%$#]|\\S*[❯➜λ›»])(?:\\s+(.*))?$")
/** Virtualenv activation typed by editors (`source …/.venv/bin/activate`) and its wrapped tail. */
private val ACTIVATION = Regex("^(?:source|\\.)\\s+\\S*(?:venv|activate|/)\\S*$|^\\S*/bin/acti(?:vate)?\\S*$")
/** Agent UI hints and footers that say nothing about the work. */
private val AGENT_NOISE = Regex(
    "/clear to save|disable recaps|restart to update|^▸ credits:|shift\\+tab|esc to interrupt|\\? for shortcuts|ask a question or describe a task|how is claude doing|1: bad\\s+2: fine",
    RegexOption.IGNORE_CASE,
)
private val RULE = Regex("^[\\s─━═│┃║╭╮╰╯┌┐└┘├┤┬┴┼▔▁\\-_=]*$")
private val EDGES = Regex("^[\\s│┃║╭╮╰╯┌┐└┘├┤]+|[\\s│┃║╭╮╰╯┌┐└┘├┤]+$")

private fun base(p: String) = p.trimEnd('/').substringAfterLast('/')

/** A command line as typed, minus env assignments, continuation backslashes and long paths. */
private fun shortCommand(cmd: String) = cmd
    .replace(Regex("\\s*\\\\$"), "")
    .replace(Regex("^(?:[A-Z_][A-Z0-9_]*=\\S*\\s+)+"), "")
    .split(Regex("\\s+"))
    .joinToString(" ") { if (it.startsWith("/") && it.length > 1) base(it) else it }

private fun clean(lines: List<String>) = lines.map { it.replace(EDGES, "") }.filter { it.isNotEmpty() && !RULE.matches(it) }

/** "zsh", "-zsh", "/bin/zsh" and wrapped shells like "zsh (kiro-cli-term)" are shells. */
fun isShellProcess(name: String) = base(name.replace(Regex("\\s*\\(.*$"), "")).removePrefix("-").lowercase() in SHELLS

/** The process that owns the terminal: the foreground group leader, else the last non-shell. */
fun leaderProcess(info: ProcInfo?): Proc? {
    val procs = info?.procs ?: return null
    return procs.firstOrNull { it.pid != null && it.pid == info.fgGroup } ?: procs.lastOrNull { !isShellProcess(it.name) } ?: procs.firstOrNull()
}

/** "python app.py", "vi config.yaml", "npm run dev": short and recognizable. */
fun describeProcess(p: Proc): String {
    val argv = p.argv.ifEmpty { (p.cmdline ?: p.name).split(Regex("\\s+")) }
    val a0 = argv.firstOrNull()?.ifEmpty { null } ?: p.name
    val prog = if (base(a0).lowercase() == p.name.lowercase()) p.name else base(a0)
    val args = argv.drop(1).map { if (it.contains('/') && !it.startsWith("-")) base(it) else it }
    if (INTERPRETERS.matches(prog.lowercase())) {
        val m = args.indexOf("-m")
        val target = if (m >= 0) args.subList(m, minOf(args.size, m + 2)) else args.filter { !it.startsWith("-") }.take(2)
        return (listOf(prog.lowercase()) + target).joinToString(" ")
    }
    val s = (listOf(prog) + args).joinToString(" ")
    return if (s.length > 80) s.take(79) + "…" else s
}

private fun trimTrailingBlank(text: String): MutableList<String> {
    val lines = text.split('\n').map { it.trimEnd() }.toMutableList()
    while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
    return lines
}

/** Summarize a shell pane from its foreground process and recent (unwrapped) output. */
fun summarizeShell(info: ProcInfo?, text: String): PaneActivity {
    var lines: List<String> = trimTrailingBlank(text)
    val leader = leaderProcess(info)
    if (leader != null && !isShellProcess(leader.name)) {
        val full = FULLSCREEN.matches(base(leader.argv.firstOrNull() ?: leader.name)) || FULLSCREEN.matches(leader.name)
        val output = if (full) emptyList() else clean(lines).takeLast(2)
        return PaneActivity("running", describeProcess(leader), null, output, output.any { FAILURE.containsMatchIn(it) })
    }
    // At the prompt: the last prompt line may carry typed-but-unsent text; the prompt line before it holds
    // the last command, and everything between them is that command's output.
    lines = lines.flatMap { l ->
        if (ACTIVATION.matches(l.trim())) return@flatMap emptyList()
        val cmd = PROMPT.find(l.trim())?.groupValues?.get(1)?.trim()
        if (!cmd.isNullOrEmpty() && ACTIVATION.matches(cmd)) listOf(l.substring(0, l.lastIndexOf(cmd)).trimEnd()) else listOf(l)
    }
    val prompts = lines.mapIndexedNotNull { i, l -> PROMPT.find(l.trim())?.let { i to it } }
    val last = prompts.lastOrNull()
    var typed: String? = null
    var command: String? = null
    var body = lines
    if (last != null && last.first == lines.size - 1) {
        val prev = prompts.lastOrNull { it.first < last.first && it.second.groupValues[1].isNotBlank() }
        command = prev?.let { shortCommand(it.second.groupValues[1].trim()) }
        typed = last.second.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(::shortCommand)
        if (typed == command) typed = null // recalled from history, not news
        body = lines.subList(if (prev != null) prev.first + 1 else 0, last.first)
    }
    val output = clean(body.filter { !PROMPT.matches(it.trim()) }).takeLast(2)
    return PaneActivity("prompt", command, typed, output, output.any { FAILURE.containsMatchIn(it) })
}

/** The last meaningful lines of an agent's screen: its question when blocked, else the tail of its reply. */
fun summarizeAgent(text: String, status: AgentStatus): List<String> {
    var lines: List<String> = text.split('\n').map { it.trimEnd() }
    if (status != AgentStatus.blocked) {
        // Drop the input box and footer: everything from the first rule near the bottom.
        val tail = maxOf(0, lines.size - 8)
        val cut = lines.indices.firstOrNull { it >= tail && lines[it].isNotBlank() && RULE.matches(lines[it]) } ?: -1
        if (cut > 0) lines = lines.subList(0, cut)
    }
    return clean(lines).filter { !Regex("^\\s*[❯›>]\\s*$").matches(it) && !AGENT_NOISE.containsMatchIn(it) }.takeLast(5)
}

data class ActivityTarget(val paneId: String, val agent: Boolean, val status: AgentStatus?)

fun parseProcInfo(r: JsonObject): ProcInfo? {
    val info = r.get("process_info")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val procs = info.get("foreground_processes")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        Proc(
            pid = o.get("pid")?.takeIf { it.isJsonPrimitive }?.asLong,
            name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
            argv = o.get("argv")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList(),
            cmdline = o.get("cmdline")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    } ?: emptyList()
    return ProcInfo(info.get("foreground_process_group_id")?.takeIf { it.isJsonPrimitive }?.asLong, procs)
}

/** Polls pane.process_info / pane.read for the given panes and remembers what it saw. */
class ActivityWatcher(private val client: HerdrClient) {
    private class Seen(val activity: PaneActivity, val sig: String)

    private val byPane = ConcurrentHashMap<String, Seen>()
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "herdr-activity").apply { isDaemon = true } }
    @Volatile private var busy = false

    fun get(paneId: String): PaneActivity? = byPane[paneId]?.activity

    /** Blocks until done. Returns true when anything visible changed. */
    fun poll(targets: List<ActivityTarget>): Boolean {
        if (busy) return false
        busy = true
        try {
            val futures: List<Future<Boolean>> = targets.map { t -> pool.submit<Boolean> { pollOne(t) } }
            var changed = futures.map { runCatching { it.get() }.getOrDefault(false) }.any { it }
            val live = targets.map { it.paneId }.toSet()
            for (id in byPane.keys.toList()) if (id !in live) { byPane.remove(id); changed = true }
            return changed
        } finally {
            busy = false
        }
    }

    private fun pollOne(t: ActivityTarget): Boolean = try {
        val info = if (!t.agent) parseProcInfo(client.request("pane.process_info", JsonObject().apply { addProperty("pane_id", t.paneId) })) else null
        val read = client.request("pane.read", JsonObject().apply {
            addProperty("pane_id", t.paneId)
            addProperty("source", if (t.agent) "detection" else "recent_unwrapped")
            addProperty("lines", if (t.agent) 60 else 40)
        })
        val text = read.getAsJsonObject("read")?.get("text")?.asString ?: ""
        val summary = if (!t.agent) summarizeShell(info, text) else PaneActivity(output = summarizeAgent(text, t.status ?: AgentStatus.unknown))
        val prev = byPane[t.paneId]
        // The read revision isn't populated, so detect output changes by content.
        val sig = "${summary.command ?: ""}\u0000$text"
        val changedAt = if (prev != null && prev.sig != sig) System.currentTimeMillis() else prev?.activity?.changedAt
        val next = summary.copy(changedAt = changedAt)
        byPane[t.paneId] = Seen(next, sig)
        prev == null || prev.activity != next
    } catch (_: Exception) {
        false // pane closed between snapshot and poll, or the server went away; the next snapshot sorts it out
    }

    fun shutdown() = pool.shutdownNow()
}
