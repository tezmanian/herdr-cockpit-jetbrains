// Against a real Gitea: finds the pull request, sends a pending review (one finding on a diff line, one off it),
// checks it on the server, then deletes it. Skipped unless these are set:
//   HC_GITEA_URL=https://gitea.example.com  HC_GITEA_TOKEN_FILE=<file with a token>  HC_GITEA_REPO=owner/name
//   HC_GITEA_BRANCH=<the pull request's source branch>
// The review stays pending (only you see it) and is deleted at the end.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GiteaLiveTest {
    private val url = System.getenv("HC_GITEA_URL")
    private val tokenFile = System.getenv("HC_GITEA_TOKEN_FILE")
    private val repoPath = System.getenv("HC_GITEA_REPO")
    private val branch = System.getenv("HC_GITEA_BRANCH")

    @Test
    fun pendingReviewRoundTrip() {
        assumeTrue("HC_GITEA_* not set", listOf(url, tokenFile, repoPath, branch).all { !it.isNullOrBlank() })
        val api = RestApi(GiteaForge().apiBase(url!!.trimEnd('/')), GiteaForge().auth(File(tokenFile).readText().trim()))
        val review = GiteaReview(api, RemoteRepo(java.net.URI(url).host, repoPath))

        val pr = review.candidates(branch, null).single()
        val cr = review.load(pr.number)
        val (path, file) = cr.files.entries.first { it.value.lines.values.any { old -> old == null } }
        val added = file.lines.entries.first { it.value == null }.key
        review.send(cr, listOf(Finding(path, added, "live test: on an added line"), Finding(path, 99999, "live test: off the diff")))

        val reviews = JsonParser.parseString(api.call("GET", "repos/$repoPath/pulls/${pr.number}/reviews", null)).asJsonArray.map { it.asJsonObject }
        val mine = reviews.last { it.str("state") == "PENDING" }
        val id = mine.get("id").asLong
        try {
            assertTrue(mine.str("body")!!.contains("`$path:99999`"))
            val comments = JsonParser.parseString(api.call("GET", "repos/$repoPath/pulls/${pr.number}/reviews/$id/comments", null)).asJsonArray.map { it.asJsonObject }
            val c = comments.single()
            assertEquals(path, c.str("path"))
            assertEquals(added, c.get("position").asInt)
            assertEquals("live test: on an added line", c.str("body"))
        } finally {
            api.call("DELETE", "repos/$repoPath/pulls/${pr.number}/reviews/$id", null)
        }
    }
}
