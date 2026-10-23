// A kind of forge (GitLab, GitHub, Gitea, …) as an extension: everything the core needs to detect it, sign in to it
// and send an agent review there. The built-in ones register like any other plugin would (plugin.xml); the send flow,
// the servers table, tokens and the diff parser stay in the core. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import java.net.http.HttpResponse

/**
 * Register one in your plugin.xml as
 * `<extensions defaultExtensionNs="io.github.tezmanian.herdrcockpit"><forge implementation="…"/></extensions>`.
 * Experimental: it may still change while only the built-in forges use it (not annotated as such, because the Plugin
 * Verifier then flags every use by the built-in forges).
 */
interface ForgeProvider {
    /** Its name ("GitLab"). A server entry stores it, so it must not change once released. */
    val title: String
    /** How a merge/pull request is referenced: "!" for "!7", "#" for "#7". */
    val sign: String
    /** What a merge/pull request is called: "merge request". */
    val request: String
    /** What the unpublished comments are called in the forge's UI: "Drafts". */
    val pending: String
    /** The preview's words for what is sent: "Draft notes on" (followed by "!7 title"). */
    val sendsAs: String
    /** The preview's sentence on where findings on lines outside the diff go. */
    val offDiff: String
    /** Its CLI's binary name ("glab"), looked up in the usual install folders, then PATH. */
    val cli: String
    /** What kind of access token to create, for the token field's hint. */
    val tokenHint: String
    /** Public hosts known to run it ("gitlab.com"), lowercase. */
    val knownHosts: Set<String> get() = emptySet()
    /** Where the IDE keeps accounts for it ("Settings | Version Control | GitLab"); null when it doesn't. */
    val ideAccounts: String? get() = null

    /** The command that signs the CLI in to `host`. */
    fun cliSignIn(host: String): String
    /** The API through the CLI `bin` when it's signed in to `host`, else null; detection also takes that as "it's this forge". */
    fun cliApi(bin: String, host: String, env: Map<String, String>): ForgeApi?
    /**
     * Whether the server answers like this forge. `get(path)` is one anonymous GET on the server ("/api/v4/version"),
     * null when it didn't answer; redirects aren't followed, so a sign-in proxy in front stays unknown.
     */
    fun probe(get: (String) -> HttpResponse<String>?): Boolean
    /** The REST API root for the server at `web` ("https://host" or a subpath install, without a trailing slash). */
    fun apiBase(web: String): String
    /** The Authorization header's value for an access token. */
    fun auth(token: String) = "Bearer $token"
    /** Extra headers on every REST call. */
    fun headers(): Map<String, String> = emptyMap()
    /** The IDE's own account for `host`: (REST API root, token), or null. Called on a background thread. */
    fun ideAccount(host: String): Pair<String, String>? = null
    /** Its side of the send flow for `repo`, through `api`. */
    fun review(api: ForgeApi, repo: RemoteRepo): ForgeReview
}
