// Finding herdr/git, telling "not installed / not running / crashed / incompatible / unreachable" apart, and
// starting the headless server in the background. No IntelliJ imports.
//
// Verified upstream against herdr 0.9.x:
//  - `herdr status server --json` reports {running, compatible, protocol, version, socket, session, ...} and
//    honors HERDR_SOCKET_PATH / HERDR_SESSION, so it is the authoritative check.
//  - Sessions are separated by session NAME, not socket path: a second server for the same session restores
//    and then overwrites that session's state. So never start a server unless status says none is running.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit

/** The Herdr protocol this build was written against (herdr 0.9.1 - 0.9.3). */
const val HERDR_PROTOCOL = 22

private val home: String = System.getProperty("user.home")

/** Install folders to check before PATH: an IDE started from a launcher gets a minimal PATH. */
fun toolDirs(): List<String> {
    val user = listOf("$home/.local/bin", "$home/.cargo/bin", "$home/.nix-profile/bin")
    return if (System.getProperty("os.name").lowercase().contains("mac"))
        listOf("/opt/homebrew/bin", "/usr/local/bin") + user + "/run/current-system/sw/bin"
    else
        listOf(user[0], "/usr/local/bin", "/usr/bin", "/home/linuxbrew/.linuxbrew/bin", "$home/.linuxbrew/bin") + user.drop(1) +
            listOf("/run/current-system/sw/bin", "/snap/bin")
}

/** The first existing `name` in the install folders, else the bare name (looked up on PATH). */
fun findTool(name: String): String =
    toolDirs().map { "$it/$name" }.firstOrNull { File(it).exists() } ?: name

fun resolveBinary(override: String?): String =
    if (override.isNullOrBlank()) findTool("herdr") else if (override.startsWith("~")) home + override.substring(1) else override

fun binaryFound(bin: String): Boolean =
    if (File(bin).isAbsolute) File(bin).exists()
    else (System.getenv("PATH") ?: "").split(File.pathSeparator).any { it.isNotEmpty() && File(it, bin).exists() }

/** `<anything>/sessions/<name>/herdr.sock` is named session <name>'s socket. */
fun sessionOfSocket(socket: String): String? = Regex("""[/\\]sessions[/\\]([^/\\]+)[/\\]herdr\.sock$""").find(socket)?.groupValues?.get(1)

/** The environment herdr commands need so they target the same session/socket we use. */
fun herdrEnv(socketOverride: String?): Map<String, String> {
    val env = System.getenv().toMutableMap()
    if (!socketOverride.isNullOrBlank()) {
        env["HERDR_SOCKET_PATH"] = socketOverride
        sessionOfSocket(socketOverride)?.let { env["HERDR_SESSION"] = it }
    }
    return env
}

/** Runs a command and returns stdout, or null when it can't run or times out. */
fun runCommand(cmd: List<String>, env: Map<String, String>, timeoutSec: Long = 5, cwd: File? = null): Pair<Int, String>? = try {
    val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
    if (cwd != null) pb.directory(cwd)
    pb.environment().clear()
    pb.environment().putAll(env)
    val p = pb.start()
    p.outputStream.close()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else p.exitValue() to out
} catch (_: Exception) {
    null
}

data class ServerStatus(val running: Boolean, val compatible: Boolean?, val protocol: Int?, val version: String?, val socket: String?)

/** `herdr status server --json`; null when the command can't run or answer. */
fun serverStatus(bin: String, env: Map<String, String>): ServerStatus? {
    val (_, out) = runCommand(listOf(bin, "status", "server", "--json"), env) ?: return null
    val o = runCatching { JsonParser.parseString(out).asJsonObject }.getOrNull() ?: return null
    val running = o.get("running")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return null
    fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    return ServerStatus(running, o.get("compatible")?.takeIf { it.isJsonPrimitive }?.asBoolean, o.get("protocol")?.takeIf { it.isJsonPrimitive }?.asInt, o.str("version"), o.str("socket"))
}

enum class DownReason(val id: String) { NotInstalled("not-installed"), NotRunning("not-running"), Crashed("crashed"), Incompatible("incompatible"), Unreachable("unreachable") }

data class Diagnosis(
    val reason: DownReason,
    val socket: String,
    val customSocket: Boolean,
    val binary: String,
    val version: String? = null,
    val protocol: Int? = null,
    val detail: String? = null,
)

/** Why we can't reach Herdr. `error` is the failed request's error, if any. */
fun diagnose(bin: String, socket: String, customSocket: Boolean, env: Map<String, String>, error: Throwable?): Diagnosis {
    val base = Diagnosis(DownReason.Unreachable, socket, customSocket, bin)
    if (!binaryFound(bin)) return base.copy(reason = DownReason.NotInstalled)
    val st = serverStatus(bin, env) ?: return base.copy(detail = error?.message ?: "`herdr status server` did not answer")
    if (!st.running) return base.copy(reason = if (File(socket).exists()) DownReason.Crashed else DownReason.NotRunning)
    if (st.compatible == false || (st.protocol != null && st.protocol != HERDR_PROTOCOL))
        return base.copy(reason = DownReason.Incompatible, version = st.version, protocol = st.protocol)
    return base.copy(version = st.version, protocol = st.protocol, detail = error?.message)
}

/**
 * The environment to start the server with: like a terminal's, not the IDE's, since panes inherit it.
 * Drops IDE/JetBrains variables and stops Kiro's shell integration from wrapping every pane shell (which
 * hides agents from Herdr's detection) unless the IDE was itself started from a Kiro-wrapped terminal.
 */
fun serverEnv(env: Map<String, String>): Map<String, String> {
    val out = env.filterKeys { k ->
        !Regex("^(IDEA_|JETBRAINS_|INTELLIJ_|PHPSTORM_|__INTELLIJ_|TERMINAL_EMULATOR$|TERM_SESSION_ID$)").containsMatchIn(k)
    }.toMutableMap()
    if (out["Q_TERM"] == null) out["Q_TERM_DISABLED"] = "1"
    return out
}

data class StartResult(val ok: Boolean, val already: Boolean = false, val error: String? = null)

/**
 * Starts `herdr server` detached (it keeps running after the IDE quits) and waits until it answers.
 * Refuses to start a second server for a session that already has one.
 */
fun startServer(bin: String, env: Map<String, String>, logFile: File, timeoutMs: Long = 15_000): StartResult {
    if (!binaryFound(bin)) return StartResult(false, error = "herdr not found ($bin)")
    if (serverStatus(bin, env)?.running == true) return StartResult(true, already = true)
    val custom = env["HERDR_SOCKET_PATH"]
    if (custom != null && sessionOfSocket(custom) == null) {
        val other = serverStatus(bin, env - "HERDR_SOCKET_PATH")
        if (other?.running == true)
            return StartResult(false, error = "Herdr is already running for this session at ${other.socket ?: "its default socket"}; starting another server on $custom would share its state.")
    }
    logFile.parentFile.mkdirs()
    // setsid detaches the server from the IDE's process group so it survives the IDE.
    val cmd = if (File("/usr/bin/setsid").exists()) listOf("/usr/bin/setsid", bin, "server") else listOf(bin, "server")
    val proc = try {
        ProcessBuilder(cmd).apply {
            environment().clear()
            environment().putAll(serverEnv(env))
            redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            redirectErrorStream(true)
        }.start()
    } catch (e: Exception) {
        return StartResult(false, error = "could not start $bin: ${e.message}")
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(300)
        if (!proc.isAlive && proc.exitValue() != 0) return StartResult(false, error = "herdr server exited (${proc.exitValue()}); see $logFile")
        if (serverStatus(bin, env)?.running == true) return StartResult(true)
    }
    return StartResult(false, error = "herdr server didn't come up within ${timeoutMs / 1000}s; see $logFile")
}

/** Wait before the next reconnect attempt: 1.5s, then backing off to 10s. */
fun backoffDelay(failures: Int): Long = minOf(10_000L, (1500.0 * Math.pow(2.0, maxOf(0, failures - 1).toDouble())).toLong())
