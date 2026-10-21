// Findings as GitLab draft notes: the remote, the merge request lookup, which findings go where, and the two transports
// (a fake glab script and a local HTTP server). No real GitLab.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class GitLabDraftsTest {
    private val dir = Files.createTempDirectory("hc").toFile()
    private val project = GitLabProject("gitlab.example.com", "group/sub/app")

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun projectFromRemote() {
        assertEquals(project, gitlabProject("git@gitlab.example.com:group/sub/app.git"))
        assertEquals(project, gitlabProject("https://oauth2:x@GitLab.example.com/group/sub/app.git"))
        assertEquals(project, gitlabProject("ssh://git@gitlab.example.com:2222/group/sub/app"))
        assertEquals("projects/group%2Fsub%2Fapp", project.api)
        assertNull(gitlabProject("/srv/repos/app.git"))
        assertNull(gitlabProject("https://gitlab.example.com/app"))
        assertNull(gitlabProject("https://gitlab.example.com/group/../app"))
        assertNull(gitlabProject("git@gitlab.example.com:group/a?b"))
    }

    @Test
    fun linesOfADiff() {
        val diff = "@@ -10,4 +10,5 @@ fun x\n context\n-gone\n+new one\n+new two\n context2\n\\ No newline at end of file\n@@ -30 +31 @@\n-a\n+b\n"
        assertEquals(mapOf(10 to 10, 11 to null, 12 to null, 13 to 12, 31 to null), diffLines(diff))
        assertEquals(emptyMap<Int, Int?>(), diffLines(""))
    }

    @Test
    fun notesCantActOnGitLab() {
        assertEquals("fine\n\\/merge\n  \\/approve now\nsee a/b", inertNote("fine\n/merge\n  /approve now\nsee a/b"))
        assertEquals("ping @​all and @​me.too, a @ b", inertNote("ping @all and @me.too, a @ b"))
    }

    private val mr = MergeRequest(7, "Feature", "https://gitlab.example.com/group/sub/app/-/merge_requests/7", "b", "s", "h",
        mapOf("src/a.kt" to ("src/old_a.kt" to mapOf(3 to null, 4 to 2))))

    @Test
    fun draftsOnlyForChangedFiles() {
        val drafts = draftsFor(listOf(Finding("src/a.kt", 3, "added"), Finding("src/a.kt", 4, "context"),
            Finding("src/a.kt", 99, "far away"), Finding("other.kt", 1, "not in the MR")), mr)
        assertEquals(listOf(3, 4, 99), drafts.map { it.first.line })
        val added = JsonParser.parseString(drafts[0].second).asJsonObject
        val pos = added.getAsJsonObject("position")
        assertEquals("added", added.get("note").asString)
        assertEquals(listOf("text", "b", "s", "h", "src/old_a.kt", "src/a.kt"),
            listOf("position_type", "base_sha", "start_sha", "head_sha", "old_path", "new_path").map { pos.get(it).asString })
        assertEquals(3, pos.get("new_line").asInt)
        assertFalse(pos.has("old_line"))
        assertEquals(2, JsonParser.parseString(drafts[1].second).asJsonObject.getAsJsonObject("position").get("old_line").asInt)
        val general = JsonParser.parseString(drafts[2].second).asJsonObject
        assertFalse(general.has("position"))
        assertEquals("`src/a.kt:99`\n\nfar away", general.get("note").asString)
    }

    @Test
    fun refusedLineBecomesGeneralDraft() {
        val sent = mutableListOf<String>()
        val api = GitLabApi { _, path, body ->
            assertEquals("projects/group%2Fsub%2Fapp/merge_requests/7/draft_notes", path)
            if (body!!.contains("position")) throw GitLabError("HTTP 400", 400)
            sent += body
            "{}"
        }
        assertEquals(1, sendDrafts(api, project, mr, draftsFor(listOf(Finding("src/a.kt", 3, "x")), mr)))
        assertEquals(listOf(generalNote(Finding("src/a.kt", 3, "x"))), sent)
        try {
            sendDrafts({ _, _, _ -> throw GitLabError("HTTP 401", 401) }, project, mr, draftsFor(listOf(Finding("src/a.kt", 3, "x")), mr))
            fail("401 must not be retried")
        } catch (e: GitLabError) {
            assertEquals(401, e.status)
        }
    }

    private fun fakeGitLab(): GitLabApi {
        val calls = mutableListOf<String>()
        return GitLabApi { method, path, _ ->
            calls += "$method $path"
            when {
                path.startsWith("projects/group%2Fsub%2Fapp/merge_requests?") ->
                    """[{"iid": 6, "sha": "other", "source_branch": "x"}, {"iid": 7, "sha": "h", "source_branch": "feature/y"}]"""
                path == "projects/group%2Fsub%2Fapp/merge_requests/7" ->
                    """{"iid": 7, "title": "Feature", "web_url": "u", "diff_refs": {"base_sha": "b", "start_sha": "s", "head_sha": "h"}}"""
                path.startsWith("projects/group%2Fsub%2Fapp/merge_requests/7/diffs?") ->
                    """[{"old_path": "o.kt", "new_path": "n.kt", "diff": "@@ -1 +1 @@\n-a\n+b\n"}, {"old_path": "gone.kt", "new_path": "gone.kt", "deleted_file": true, "diff": ""}]"""
                else -> throw GitLabError("unexpected $path", 404)
            }
        }
    }

    @Test
    fun findsTheMergeRequestByHeadOrBranch() {
        val byHead = findMergeRequest(fakeGitLab(), project, null, "h")!!
        assertEquals(MergeRequest(7, "Feature", "u", "b", "s", "h", mapOf("n.kt" to ("o.kt" to mapOf(1 to null)))), byHead)
        assertEquals(7, findMergeRequest(fakeGitLab(), project, "feature/y", "not pushed")!!.iid)
        assertNull(findMergeRequest(fakeGitLab(), project, null, "unknown"))
    }

    @Test
    fun glabGetsArgumentsAndBodyOnStdin() {
        val log = File(dir, "log")
        val glab = File(dir, "glab").apply {
            writeText("#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\n' \"\$a\"; done > '$log'\necho --- >> '$log'\ncat >> '$log'\necho '{\"ok\":1}'\n")
            setExecutable(true)
        }
        val api = GlabApi(glab.path, "gitlab.example.com", mapOf("PATH" to "/usr/bin:/bin"))
        assertEquals("{\"ok\":1}\n", api.call("POST", "projects/p/merge_requests/7/draft_notes", "{\"note\":\"x; \$(rm -rf /)\"}"))
        assertEquals(listOf("api", "--hostname", "gitlab.example.com", "--method", "POST", "-H", "Content-Type: application/json",
            "--input", "-", "projects/p/merge_requests/7/draft_notes", "---", "{\"note\":\"x; \$(rm -rf /)\"}"), log.readLines())

        glab.writeText("#!/bin/sh\necho 'HTTP 404 Not Found' >&2\nexit 1\n")
        try {
            api.call("GET", "projects/p", null)
            fail("a failing glab must throw")
        } catch (e: GitLabError) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun restSendsTheToken() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/api/v4/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("Authorization")} ${ex.requestBody.readAllBytes().decodeToString()}"
            val (code, body) = if (ex.requestHeaders.getFirst("Authorization") == "Bearer good") 201 to "{}" else 401 to "{\"message\":\"401 Unauthorized\"}"
            ex.sendResponseHeaders(code, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/api/v4"
            assertEquals("{}", RestApi(base, "good").call("POST", "projects/group%2Fapp/merge_requests/7/draft_notes", "{\"note\":\"n\"}"))
            assertEquals("POST /api/v4/projects/group%2Fapp/merge_requests/7/draft_notes Bearer good {\"note\":\"n\"}", seen.single())
            try {
                RestApi(base, "bad").call("GET", "projects/x", null)
                fail("401 must throw")
            } catch (e: GitLabError) {
                assertEquals(401, e.status)
            }
        } finally {
            server.stop(0)
        }
    }
}
