// Sending a review to GitLab, GitHub and Gitea: the remote, finding the request, the diff, what is posted, and the
// transports (fake CLIs as shell scripts, a local HTTP server). No real forge.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class ForgeReviewTest {
    private val dir = Files.createTempDirectory("hc").toFile()
    private val repo = RemoteRepo("git.example.com", "group/app")

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun repoFromRemote() {
        val sub = RemoteRepo("gitlab.example.com", "group/sub/app")
        assertEquals(sub, remoteRepo("git@gitlab.example.com:group/sub/app.git"))
        assertEquals(sub, remoteRepo("https://oauth2:x@GitLab.example.com/group/sub/app.git"))
        assertEquals(sub, remoteRepo("ssh://git@gitlab.example.com:2222/group/sub/app"))
        assertEquals("projects/group%2Fsub%2Fapp", sub.gitlab)
        assertEquals("repos/group/app", repo.repos)
        assertNull(remoteRepo("/srv/repos/app.git"))
        assertNull(remoteRepo("https://gitlab.example.com/app"))
        assertNull(remoteRepo("https://gitlab.example.com/group/../app"))
        assertNull(remoteRepo("git@gitlab.example.com:group/a?b"))
    }

    @Test
    fun linesOfADiff() {
        val diff = "@@ -10,4 +10,5 @@ fun x\n context\n-gone\n+new one\n+new two\n context2\n\\ No newline at end of file\n@@ -30 +31 @@\n-a\n+b\n"
        assertEquals(mapOf(10 to 10, 11 to null, 12 to null, 13 to 12, 31 to null), diffLines(diff))
        assertEquals(emptyMap<Int, Int?>(), diffLines(""))
    }

    @Test
    fun filesOfAWholeDiff() {
        val diff = """
            diff --git a/src/a.kt b/src/a.kt
            index 1..2 100644
            --- a/src/a.kt
            +++ b/src/a.kt
            @@ -1,2 +1,3 @@
             keep
            +added
            --- not a header: a removed line starting with two dashes
            diff --git a/old.kt b/new.kt
            similarity index 90%
            rename from old.kt
            rename to new.kt
            --- a/old.kt
            +++ b/new.kt
            @@ -5 +5 @@
            -x
            +y
            diff --git a/gone.kt b/gone.kt
            deleted file mode 100644
            --- a/gone.kt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            diff --git a/moved.kt b/elsewhere.kt
            similarity index 100%
            rename from moved.kt
            rename to elsewhere.kt
            diff --git a/fresh.kt b/fresh.kt
            new file mode 100644
            --- /dev/null
            +++ b/fresh.kt
            @@ -0,0 +1 @@
            +hi
        """.trimIndent() + "\n"
        assertEquals(mapOf(
            "src/a.kt" to ChangedFile("src/a.kt", mapOf(1 to 1, 2 to null)),
            "new.kt" to ChangedFile("old.kt", mapOf(5 to null)),
            "elsewhere.kt" to ChangedFile("moved.kt", emptyMap()),
            "fresh.kt" to ChangedFile("fresh.kt", mapOf(1 to null)),
        ), splitUnifiedDiff(diff))
    }

    @Test
    fun notesCantActOnTheForge() {
        assertEquals("fine\n\\/merge\n  \\/approve now\nsee a/b", inertNote("fine\n/merge\n  /approve now\nsee a/b"))
        assertEquals("ping @​all and @​me.too, a @ b", inertNote("ping @all and @me.too, a @ b"))
    }

    @Test
    fun bestCandidatesNeverGuess() {
        fun ref(n: Int, src: String, sha: String) = ChangeRef(n, "T$n", src, "main", sha)
        val a = ref(7, "y", "h")
        val b = ref(8, "y", "h2")
        val old = ref(9, "z", "new")
        assertEquals(listOf(a), bestCandidates(listOf(a), listOf(a, b), "h"))
        assertEquals(listOf(a, b), bestCandidates(emptyList(), listOf(a, b), "unpushed"))
        assertEquals(listOf(old), bestCandidates(listOf(old), emptyList(), "old"))
    }

    private val cr = ChangeRequest(7, "Feature", "https://x/7", "h", mapOf("src/a.kt" to ChangedFile("src/old_a.kt", mapOf(3 to null, 4 to 2))), "b", "s")

    // ---------- GitLab ----------

    @Test
    fun gitlabDraftNotes() {
        val added = json(draftNote(Finding("src/a.kt", 3, "added"), cr))
        val pos = added.getAsJsonObject("position")
        assertEquals("added", added.get("note").asString)
        assertEquals(listOf("text", "b", "s", "h", "src/old_a.kt", "src/a.kt"),
            listOf("position_type", "base_sha", "start_sha", "head_sha", "old_path", "new_path").map { pos.get(it).asString })
        assertEquals(3, pos.get("new_line").asInt)
        assertFalse(pos.has("old_line"))
        assertEquals(2, json(draftNote(Finding("src/a.kt", 4, "context"), cr)).getAsJsonObject("position").get("old_line").asInt)
        val general = json(draftNote(Finding("src/a.kt", 99, "far away"), cr))
        assertFalse(general.has("position"))
        assertEquals("`src/a.kt:99`\n\nfar away", general.get("note").asString)
    }

    @Test
    fun gitlabRefusedLineBecomesGeneralDraft() {
        val sent = mutableListOf<String>()
        val api = ForgeApi { _, path, body ->
            assertEquals("projects/group%2Fapp/merge_requests/7/draft_notes", path)
            if (body!!.contains("position")) throw ForgeError("HTTP 400", 400)
            sent += body
            "{}"
        }
        assertEquals(1, GitLabReview(api, repo).send(cr, listOf(Finding("src/a.kt", 3, "x"))))
        assertEquals(listOf(generalNote(Finding("src/a.kt", 3, "x"))), sent)
        try {
            GitLabReview({ _, _, _ -> throw ForgeError("HTTP 401", 401) }, repo).send(cr, listOf(Finding("src/a.kt", 3, "x")))
            fail("401 must not be retried")
        } catch (e: ForgeError) {
            assertEquals(401, e.status)
        }
    }

    private val p = "projects/group%2Fapp"

    /** A GitLab with MR 7 (feature/y, head h), MR 8 (feature/y into release, head h2), MR 9 (feature/z, contains old). */
    private val fakeGitLab = ForgeApi { _, path, _ ->
        fun mr(iid: Int, src: String, sha: String, state: String = "opened") =
            """{"iid": $iid, "title": "T$iid", "source_branch": "$src", "target_branch": "main", "sha": "$sha", "state": "$state"}"""
        when (path) {
            "$p/repository/commits/h/merge_requests?state=opened" -> "[${mr(7, "feature/y", "h")}]"
            "$p/repository/commits/old/merge_requests?state=opened" -> "[${mr(9, "feature/z", "new")}, ${mr(5, "x", "o", "merged")}]"
            "$p/merge_requests?state=opened&per_page=100&source_branch=feature%2Fy" -> "[${mr(7, "feature/y", "h")}, ${mr(8, "feature/y", "h2")}]"
            "$p/merge_requests/7" -> """{"iid": 7, "title": "Feature", "web_url": "u", "diff_refs": {"base_sha": "b", "start_sha": "s", "head_sha": "h"}}"""
            "$p/merge_requests/7/diffs?per_page=100&page=1" ->
                """[{"old_path": "o.kt", "new_path": "n.kt", "diff": "@@ -1 +1 @@\n-a\n+b\n"}, {"old_path": "gone.kt", "new_path": "gone.kt", "deleted_file": true, "diff": ""}]"""
            else -> throw ForgeError("HTTP 404", 404) // e.g. a commit that was never pushed
        }
    }

    @Test
    fun gitlabFindsAndLoadsTheMergeRequest() {
        val gl = GitLabReview(fakeGitLab, repo)
        assertEquals(listOf(7), gl.candidates("feature/y", "h").map { it.number })
        assertEquals(listOf(7, 8), gl.candidates("feature/y", "unpushed").map { it.number })
        assertEquals(listOf(9), gl.candidates(null, "old").map { it.number })
        assertEquals(emptyList<Int>(), gl.candidates(null, "unknown").map { it.number })
        assertEquals(ChangeRequest(7, "Feature", "u", "h", mapOf("n.kt" to ChangedFile("o.kt", mapOf(1 to null))), "b", "s"), gl.load(7))
        try {
            GitLabReview({ _, _, _ -> throw ForgeError("HTTP 401", 401) }, repo).candidates(null, "h")
            fail("401 must not read as no merge request")
        } catch (e: ForgeError) {
            assertEquals(401, e.status)
        }
    }

    // ---------- GitHub ----------

    private fun pull(n: Int, ref: String, sha: String, state: String = "open") =
        """{"number": $n, "title": "P$n", "state": "$state", "html_url": "https://github.com/group/app/pull/$n", "head": {"ref": "$ref", "sha": "$sha"}, "base": {"ref": "main"}}"""

    @Test
    fun githubPendingReview() {
        val posted = mutableListOf<String>()
        val api = ForgeApi { m, path, body ->
            when ("$m $path") {
                "GET repos/group/app/commits/h/pulls" -> "[${pull(3, "y", "h")}, ${pull(2, "y", "h", "closed")}]"
                "GET repos/group/app/pulls?state=open&per_page=100&head=group%3Ay" -> "[${pull(3, "y", "h")}, ${pull(4, "y", "h4")}]"
                "GET repos/group/app/pulls/3" -> pull(3, "y", "h")
                "GET repos/group/app/pulls/3/files?per_page=100&page=1" ->
                    """[{"filename": "src/a.kt", "status": "modified", "patch": "@@ -1,2 +1,3 @@\n keep\n+added\n"}, {"filename": "gone.kt", "status": "removed"}, {"filename": "new.kt", "previous_filename": "old.kt", "status": "renamed"}]"""
                "POST repos/group/app/pulls/3/reviews" -> { posted += body!!; "{}" }
                else -> throw ForgeError("HTTP 404", 404)
            }
        }
        val gh = GitHubReview(api, repo)
        assertEquals(listOf(3), gh.candidates("y", "h").map { it.number })
        assertEquals(listOf(3, 4), gh.candidates("y", "unpushed").map { it.number })
        val loaded = gh.load(3)
        assertEquals(mapOf("src/a.kt" to ChangedFile("src/a.kt", mapOf(1 to 1, 2 to null)), "new.kt" to ChangedFile("old.kt", emptyMap())), loaded.files)
        assertEquals("h", loaded.headSha)
        assertEquals(2, gh.send(loaded, listOf(Finding("src/a.kt", 2, "on the line"), Finding("src/a.kt", 40, "@all off it"))))
        val review = json(posted.single())
        assertFalse(review.has("event")) // no event: GitHub keeps it pending
        assertEquals("h", review.get("commit_id").asString)
        assertEquals("`src/a.kt:40`\n\n@​all off it", review.get("body").asString)
        val c = review.getAsJsonArray("comments").single().asJsonObject
        assertEquals(listOf("src/a.kt", "2", "RIGHT", "on the line"), listOf("path", "line", "side", "body").map { c.get(it).asString })
    }

    // ---------- Gitea ----------

    @Test
    fun giteaPendingReview() {
        val posted = mutableListOf<String>()
        val api = ForgeApi { m, path, body ->
            when ("$m $path") {
                "GET repos/group/app/pulls?state=open&limit=50&page=1" -> "[${pull(5, "y", "h")}, ${pull(6, "y", "h6")}, ${pull(7, "z", "h7")}]"
                "GET repos/group/app/pulls/5" -> pull(5, "y", "h")
                "GET repos/group/app/pulls/5.diff" -> "diff --git a/src/a.kt b/src/a.kt\n--- a/src/a.kt\n+++ b/src/a.kt\n@@ -1 +1,2 @@\n keep\n+added\n"
                "POST repos/group/app/pulls/5/reviews" -> { posted += body!!; if (posted.size == 1) throw ForgeError("HTTP 422", 422) else "{}" }
                else -> throw ForgeError("HTTP 404", 404)
            }
        }
        val gt = GiteaReview(api, repo)
        assertEquals(listOf(5), gt.candidates("y", "h").map { it.number })
        assertEquals(listOf(5, 6), gt.candidates("y", "unpushed").map { it.number })
        assertEquals(emptyList<Int>(), gt.candidates(null, "unpushed").map { it.number })
        val loaded = gt.load(5)
        assertEquals(mapOf("src/a.kt" to ChangedFile("src/a.kt", mapOf(1 to 1, 2 to null))), loaded.files)
        gt.send(loaded, listOf(Finding("src/a.kt", 2, "on the line")))
        // First with a line comment; refused, then once more with everything in the text.
        val first = json(posted[0])
        assertEquals("PENDING", first.get("event").asString)
        val c = first.getAsJsonArray("comments").single().asJsonObject
        assertEquals(listOf("src/a.kt", "2", "on the line"), listOf("path", "new_position", "body").map { c.get(it).asString })
        val second = json(posted[1])
        assertFalse(second.has("comments"))
        assertEquals("`src/a.kt:2`\n\non the line", second.get("body").asString)
    }

    // ---------- transports ----------

    /** A fake CLI logging its arguments and stdin, answering `out`. */
    private fun fake(name: String, out: String = "{\"ok\":1}"): Pair<String, File> {
        val log = File(dir, "$name.log")
        val bin = File(dir, name).apply {
            writeText("#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\n' \"\$a\"; done > '$log'\necho --- >> '$log'\ncat >> '$log'\necho '$out'\n")
            setExecutable(true)
        }
        return bin.path to log
    }

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    @Test
    fun clisGetArgumentsAndBodyOnStdin() {
        val body = "{\"note\":\"x; \$(rm -rf /)\"}"
        val (glab, glabLog) = fake("glab")
        glabApi(glab, "git.example.com", env).call("POST", "projects/p/merge_requests/7/draft_notes", body)
        assertEquals(listOf("api", "--hostname", "git.example.com", "--method", "POST", "-H", "Content-Type: application/json",
            "--input", "-", "projects/p/merge_requests/7/draft_notes", "---", body), glabLog.readLines())
        val (gh, ghLog) = fake("gh")
        ghApi(gh, "github.com", env).call("GET", "repos/o/r/pulls/3", null)
        assertEquals(listOf("api", "--hostname", "github.com", "--method", "GET", "repos/o/r/pulls/3", "---"), ghLog.readLines())
        val (tea, teaLog) = fake("tea")
        teaApi(tea, "work", env).call("POST", "repos/o/r/pulls/5/reviews", body)
        assertEquals(listOf("api", "--login", "work", "-X", "POST", "-d", "@-", "/repos/o/r/pulls/5/reviews", "---", body), teaLog.readLines())

        File(glab).writeText("#!/bin/sh\necho 'glab: 404 Not Found (HTTP 404)' >&2\nexit 1\n")
        try {
            glabApi(glab, "git.example.com", env).call("GET", "projects/p", null)
            fail("a failing CLI must throw")
        } catch (e: ForgeError) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun teaLoginsByHost() {
        assertEquals(mapOf("git.example.com" to "work", "codeberg.org" to "cb"),
            parseTeaLogins("""[{"Name": "work", "URL": "https://Git.Example.com", "user": "me"}, {"name": "cb", "url": "https://codeberg.org"}, {"name": "x", "url": "nope"}]"""))
        assertEquals(emptyMap<String, String>(), parseTeaLogins("garbage"))
    }

    @Test
    fun restSendsTheToken() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/api/v1/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("Authorization")} ${ex.requestBody.readAllBytes().decodeToString()}"
            val (code, body) = if (ex.requestHeaders.getFirst("Authorization") == "token good") 201 to "{}" else 401 to "{\"message\":\"401 Unauthorized\"}"
            ex.sendResponseHeaders(code, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val web = "http://127.0.0.1:${server.address.port}"
            val base = GiteaForge().apiBase(web)
            assertEquals("{}", RestApi(base, GiteaForge().auth("good")).call("POST", "repos/o/r/pulls/5/reviews", "{\"body\":\"n\"}"))
            assertEquals("POST /api/v1/repos/o/r/pulls/5/reviews token good {\"body\":\"n\"}", seen.single())
            try {
                RestApi(base, GiteaForge().auth("bad")).call("GET", "user", null)
                fail("401 must throw")
            } catch (e: ForgeError) {
                assertEquals(401, e.status)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiRoots() {
        assertEquals("https://gitlab.example.com/api/v4", GitLabForge().apiBase("https://gitlab.example.com"))
        assertEquals("https://api.github.com", GitHubForge().apiBase("https://github.com"))
        assertEquals("https://ghe.example.com/api/v3", GitHubForge().apiBase("https://ghe.example.com"))
        assertEquals("https://example.com/gitea/api/v1", GiteaForge().apiBase("https://example.com/gitea"))
        assertEquals("Bearer t", GitHubForge().auth("t"))
        assertEquals("token t", GiteaForge().auth("t"))
    }
}
