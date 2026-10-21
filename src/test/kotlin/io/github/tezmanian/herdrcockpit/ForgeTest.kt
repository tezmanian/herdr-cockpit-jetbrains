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

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun knownHosts() {
        assertEquals(Forge.GitHub, knownForge("GitHub.com"))
        assertEquals(Forge.GitLab, knownForge("gitlab.com"))
        assertEquals(Forge.Gitea, knownForge("codeberg.org"))
        assertNull(knownForge("git.example.com"))
    }

    /** A fake CLI that succeeds for `auth status --hostname <host>` and prints `stdout` otherwise. */
    private fun cli(name: String, host: String?, stdout: String = "") = File(dir, name).apply {
        writeText("#!/bin/sh\nif [ \"$1 $2\" = \"auth status\" ]; then [ \"$4\" = \"$host\" ]; exit $?; fi\ncat <<'EOF'\n$stdout\nEOF\n")
        setExecutable(true)
    }.path

    @Test
    fun signedInCliTellsTheForge() {
        val none = File(dir, "missing").path
        assertEquals(Forge.GitLab, cliForge("git.example.com", ForgeClis(cli("glab", "git.example.com"), none, none), env))
        assertEquals(Forge.GitHub, cliForge("ghe.example.com", ForgeClis(none, cli("gh", "ghe.example.com"), none), env))
        val tea = cli("tea", null, """[{"name": "work", "url": "https://Git.Example.com", "user": "me"}]""")
        assertEquals(Forge.Gitea, cliForge("git.example.com", ForgeClis(cli("glab", "other.host"), none, tea), env))
        assertNull(cliForge("unknown.example.com", ForgeClis(cli("glab", "other.host"), cli("gh", "other.host"), tea), env))
        assertEquals(setOf("Git.Example.com"), teaLoginHosts("""[{"URL": "https://Git.Example.com"}, {"url": "not a url"}]"""))
        assertEquals(emptySet<String>(), teaLoginHosts("garbage"))
    }

    /** A server answering `routes` (path -> status, body, extra header) and 404 to the rest. */
    private fun probe(routes: Map<String, Triple<Int, String, Pair<String, String>?>>): Forge? {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val (code, body, header) = routes[ex.requestURI.path] ?: Triple(404, "<html>not found</html>", null)
            header?.let { ex.responseHeaders.add(it.first, it.second) }
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.length.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body.toByteArray()) }
        }
        server.start()
        try {
            return probeForge("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun serverTellsTheForge() {
        assertEquals(Forge.GitLab, probe(mapOf("/api/v4/version" to Triple(401, """{"message":"401 Unauthorized"}""", null))))
        assertEquals(Forge.Gitea, probe(mapOf("/api/v1/version" to Triple(200, """{"version":"1.24.0"}""", null))))
        assertEquals(Forge.GitHub, probe(mapOf("/api/v3/meta" to Triple(200, "{}", "X-GitHub-Enterprise-Version" to "3.14.0"))))
        assertNull(probe(emptyMap()))
        // A sign-in proxy redirecting everything: unknown, not a guess.
        assertNull(probe(mapOf("/api/v4/version" to Triple(302, "", "Location" to "https://sso.example.com/"))))
        // 401 without GitLab's JSON (basic auth in front of anything): unknown.
        assertNull(probe(mapOf("/api/v4/version" to Triple(401, "<html>login</html>", null))))
    }
}
