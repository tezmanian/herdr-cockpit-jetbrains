// Which forge hosts a repository: known hosts, fake CLIs (shell scripts) and a local HTTP server answering like each forge.
package io.github.tezmanian.herdrcockpit

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class ForgeTest {
    private val dir = Files.createTempDirectory("hc").toFile()
    private val env = mapOf("PATH" to "/usr/bin:/bin")
    private val lab = GitLabForge()
    private val hub = GitHubForge()
    private val gitea = GiteaForge()
    private val forges = listOf(lab, gitea, hub)

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun knownHosts() {
        assertEquals(hub, knownForge("GitHub.com", forges))
        assertEquals(lab, knownForge("gitlab.com", forges))
        assertEquals(gitea, knownForge("codeberg.org", forges))
        assertNull(knownForge("git.example.com", forges))
    }

    /** A fake CLI that succeeds for `auth status --hostname <host>` and prints `stdout` otherwise. */
    private fun cli(name: String, host: String?, stdout: String = "") = File(dir, name).apply {
        writeText("#!/bin/sh\nif [ \"$1 $2\" = \"auth status\" ]; then [ \"$4\" = \"$host\" ]; exit $?; fi\ncat <<'EOF'\n$stdout\nEOF\n")
        setExecutable(true)
    }.path

    @Test
    fun signedInCliTellsTheForge() {
        val none = File(dir, "missing").path
        fun clis(glab: String, gh: String, tea: String) = mapOf("glab" to glab, "gh" to gh, "tea" to tea)
        assertEquals(lab, cliForge("git.example.com", clis(cli("glab", "git.example.com"), none, none), forges, env))
        assertEquals(hub, cliForge("ghe.example.com", clis(none, cli("gh", "ghe.example.com"), none), forges, env))
        val tea = cli("tea", null, """[{"name": "work", "url": "https://Git.Example.com", "user": "me"}]""")
        assertEquals(gitea, cliForge("git.example.com", clis(cli("glab", "other.host"), none, tea), forges, env))
        assertNull(cliForge("unknown.example.com", clis(cli("glab", "other.host"), cli("gh", "other.host"), tea), forges, env))
    }

    /** A server answering `routes` (path -> status, body, extra header) and 404 to the rest. */
    private fun probe(routes: Map<String, Triple<Int, String, Pair<String, String>?>>): ForgeProvider? {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val (code, body, header) = routes[ex.requestURI.path] ?: Triple(404, "<html>not found</html>", null)
            header?.let { ex.responseHeaders.add(it.first, it.second) }
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.length.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body.toByteArray()) }
        }
        server.start()
        try {
            return probeForge("http://127.0.0.1:${server.address.port}", forges)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun serverTellsTheForge() {
        assertEquals(lab, probe(mapOf("/api/v4/version" to Triple(401, """{"message":"401 Unauthorized"}""", null))))
        assertEquals(gitea, probe(mapOf("/api/v1/version" to Triple(200, """{"version":"1.24.0"}""", null))))
        assertEquals(hub, probe(mapOf("/api/v3/meta" to Triple(200, "{}", "X-GitHub-Enterprise-Version" to "3.14.0"))))
        assertNull(probe(emptyMap()))
        // A sign-in proxy redirecting everything: unknown, not a guess.
        assertNull(probe(mapOf("/api/v4/version" to Triple(302, "", "Location" to "https://sso.example.com/"))))
        // 401 without GitLab's JSON (basic auth in front of anything): unknown.
        assertNull(probe(mapOf("/api/v4/version" to Triple(401, "<html>login</html>", null))))
    }

    @Test
    fun oldCliPathsMoveToTheServers() {
        val one = ReviewServer("https://git.example.com", lab, ServerLogin.Cli)
        val own = ReviewServer("https://lab2.example.com", lab, ServerLogin.Auto, "/opt/glab")
        val tea = ReviewServer("https://tea.example.com", gitea, ServerLogin.Token)
        val (servers, left) = takeOverCliPaths(listOf(one, own, tea),
            mapOf("glab" to "~/bin/glab", "gh" to "/usr/local/bin/gh", "tea" to ""))
        assertEquals(listOf(one.copy(cli = "~/bin/glab"), own, tea), servers)
        assertEquals(mapOf("gh" to "/usr/local/bin/gh"), left)
    }

    @Test
    fun unknownForgeEntryIsNoServer() {
        val entry = ServerEntry().also { it.url = "https://bb.example.com"; it.forge = "Bitbucket" }
        assertNull(entry.toServer(forges))
        entry.forge = "gitea"
        assertEquals(gitea, entry.toServer(forges)?.forge)
    }
}
