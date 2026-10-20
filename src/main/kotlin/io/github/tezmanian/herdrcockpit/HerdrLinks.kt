// Links from Herdr's side (the Herdr plugin in herdr-plugin/) into the IDE. JetBrains IDEs have no --open-url, so the
// plugin calls the IDE's built-in web server: GET http://127.0.0.1:<port>/api/herdr-cockpit/<action>?token=…&…
// The port and a per-run random token are in ~/.herdr-cockpit/jetbrains.json (owner-only). Links only select what you
// already have (a space, a pane, an existing file); they never run commands or change settings.
package io.github.tezmanian.herdrcockpit

import com.google.gson.GsonBuilder
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.RestService
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")

sealed class Link {
    data class Open(val space: String, val pane: String?) : Link()
    data class Review(val space: String, val pane: String?) : Link()
    data class OpenFile(val path: String, val line: Int?, val col: Int?) : Link()
}

/** A link's action and query, validated. Returns the link, or why it was refused. */
/** The link's token is ours (constant-time); an empty token of ours never matches. */
fun tokenOk(given: String?, token: String): Boolean =
    token.isNotEmpty() && given != null && MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

fun parseLink(action: String, q: (String) -> String?, ourSession: String?): Result<Link> = runCatching {
    val session = q("session")?.takeIf { it.isNotEmpty() }
    require(session == ourSession) { "this link is for Herdr session ${session ?: "default"}, the IDE uses ${ourSession ?: "default"}" }
    fun id(name: String) = q(name)?.takeIf { it.isNotEmpty() }?.also { require(ID.matches(it)) { "bad $name" } }
    fun num(name: String) = q(name)?.takeIf { it.isNotEmpty() }?.let { requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 1..10_000_000 }) { "bad $name" } }
    when (action) {
        "open" -> Link.Open(requireNotNull(id("space")) { "no space" }, id("pane"))
        "review" -> Link.Review(requireNotNull(id("space")) { "no space" }, id("pane"))
        "file" -> {
            val path = requireNotNull(q("path")?.takeIf { it.startsWith("/") && '\u0000' !in it }) { "path must be absolute" }
            Link.OpenFile(path, num("line"), num("col"))
        }
        else -> throw IllegalArgumentException("unknown action $action")
    }
}

object HerdrLinks {
    val token: String = HexFormat.of().formatHex(ByteArray(24).also { SecureRandom().nextBytes(it) })
    @Volatile private var published = false

    fun linkFile(): File = File(System.getProperty("user.home"), ".herdr-cockpit/jetbrains.json")

    /** Tells the Herdr plugin where to reach this IDE. */
    // shortcut: one file, so with two JetBrains IDEs open the one started last gets the links; per-IDE records like upstream's editors/ when that matters.
    @Synchronized
    fun publish() {
        if (published) return
        val port = BuiltInServerManager.getInstance().waitForStart().port
        val f = linkFile()
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.${ProcessHandle.current().pid()}.tmp")
        tmp.delete()
        Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        tmp.writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "port" to port, "token" to token, "pid" to ProcessHandle.current().pid(),
            "ide" to ApplicationInfo.getInstance().fullApplicationName, "updated" to System.currentTimeMillis(),
        )))
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        published = true
    }

    fun handle(link: Link) {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        val project = when (link) {
            is Link.OpenFile -> LocalFileSystem.getInstance().refreshAndFindFileByPath(link.path)?.let { vf ->
                ApplicationManager.getApplication().runReadAction<Project?> { projects.firstOrNull { ProjectFileIndex.getInstance(it).isInContent(vf) } }
            }
            is Link.Open -> projects.firstOrNull { HerdrService.getInstance(it).currentModel()?.space(link.space) != null }
            is Link.Review -> projects.firstOrNull { HerdrService.getInstance(it).currentModel()?.space(link.space) != null }
        } ?: RestService.getLastFocusedOrOpenedProject() ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ProjectUtil.focusProjectWindow(project, true)
            run(project, link)
        }
    }

    private fun run(project: Project, link: Link) {
        val service = HerdrService.getInstance(project)
        when (link) {
            is Link.Open -> {
                service.showToolWindow()
                if (service.currentModel()?.space(link.space) == null) return service.notify("Herdr space ${link.space} isn't there (any more).", NotificationType.WARNING)
                service.handle("switch", link.space, null)
                link.pane?.let { service.handle("attach", null, it) }
            }
            is Link.Review -> service.handle("review", link.space, link.pane)
            is Link.OpenFile -> {
                val vf = LocalFileSystem.getInstance().findFileByPath(link.path)
                    ?: return service.notify("No such file: ${link.path}", NotificationType.WARNING)
                OpenFileDescriptor(project, vf, (link.line ?: 1) - 1, (link.col ?: 1) - 1).navigate(true)
            }
        }
    }
}

class HerdrLinkStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        runCatching { HerdrLinks.publish() }.onFailure { HerdrService.getInstance(project).log("links from Herdr unavailable: ${it.message}") }
    }
}

/** GET /api/herdr-cockpit/<action>?token=…: only for callers with the token, never for web pages. */
class HerdrLinkService : RestService() {
    override fun getServiceName() = "herdr-cockpit"

    // Browsers always send an Origin on cross-site fetches; the Herdr plugin (curl) never does.
    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder) = !request.headers().contains(HttpHeaderNames.ORIGIN)

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        if (!tokenOk(getStringParameter("token", urlDecoder), HerdrLinks.token)) return "bad token"
        val action = urlDecoder.path().substringAfter("/herdr-cockpit", "").trim('/')
        val ourSession = ProjectManager.getInstance().openProjects.firstOrNull()?.let { sessionOfSocket(HerdrService.getInstance(it).socketPath()) }
        val link = parseLink(action, { getStringParameter(it, urlDecoder) }, ourSession).getOrElse { return it.message ?: "bad link" }
        HerdrLinks.handle(link)
        sendOk(request, context)
        return null
    }
}
