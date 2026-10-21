# Herdr Cockpit for JetBrains IDEs

A community (unofficial) client for [Herdr](https://herdr.dev) in PhpStorm and other JetBrains IDEs: Herdr's spaces,
agents and shells in the IDE. A port of [Herdr Cockpit for VS Code](https://github.com/sriharirao92/vscode-herdr-cockpit)
(MIT, see `LICENSE.upstream`). This port is MIT licensed too (`LICENSE`). The sidebar renderer (`media/panel.js`, `panel.css`), agent logos and codicons come
from that project unchanged (see `THIRD_PARTY_NOTICES.md`); `media/help.html` is adapted from its help page, and
`herdr-plugin/` reuses helpers of its Herdr plugin.

The plugin is a **client of the Herdr server**. It never owns agent processes; Herdr does.

## What works (MVP, v0.1.9)

- **Herdr tool window** (left side): spaces, agents and shells with status, "Needs you", shell activity
  (what's running / last command / last output), git line per space (branch, last commit, ahead/behind, changes).
  Spaces with an agent working or waiting on you come first, then the most recently active (setting "Order of
  spaces"). The plugin's and Herdr's versions are at the bottom.
  Disconnected screens explain why (not installed / not running / crashed / incompatible / unreachable) and can
  start the server.
- **Switch a space** (click its header, or *Tools | Herdr | Switch Herdr Space…*): adds its folder as a content root
  of the project (never your home folder or a folder above the project; only roots the plugin added are ever removed), focuses it in Herdr, and opens every pane as an
  **editor tab** titled "space · pane" (for example "core2 · Claude"): agents most urgent first, then shells.
- **Drop files on a pane's tab** (from the project view, an editor tab or Finder): their paths are typed in (as `@path`
  for Claude Code, which attaches the file), so you can ask the agent about them.
- **Pane tabs**: a JediTerm terminal fed by `herdr terminal session control <pane> --takeover`. Typing, colors,
  resize, selection and copy work; the mouse wheel, Shift+PageUp/PageDown and Ctrl/Cmd+Home/End scroll Herdr's
  scrollback. File paths a program prints (`src/app.ts:42`) are links to the file at that line. *Open Pane Output in
  Editor* (tab context menu, *Tools | Herdr*) shows the pane's whole history as a read-only document. Ctrl/Cmd+F opens a find bar over that history: the pane
  scrolls to each match, matches are highlighted, Tab lists the matching lines; smart case and `/regex/`. On the line
  being typed, Backspace/Delete deletes a selection and typing replaces it; Alt+click moves the cursor, Shift+click
  extends the selection, and a selection dragged past the top or bottom scrolls Herdr's history. When another
  client takes the pane over, the tab keeps showing it read-only (`herdr terminal session observe`); the first key
  takes it back, and a notification offers *Take Back*. Windows record the panes they take in
  `~/.herdr-cockpit/claims` (the same files as the VS Code extension), so the notification can say who took it, and a
  window that follows Herdr leaves a pane another window holds where it is. Closing a tab only detaches; the program keeps running in Herdr.
- Live updates via `events.subscribe` (re-snapshot on every event, 5 s safety poll), notifications when an agent is
  blocked or done, follow mode (Herdr's focus moves → the IDE follows, in projects you switched from the IDE).
- **New space** (`+` next to "Spaces"): in one of the window's projects (attached ones too), a folder you pick, or on a new git worktree (and branch) of an existing
  space's repository. **New tab** (in every space): a shell, a named shell, or an agent (your favorites first, the
  rest under "More agents…"). The new pane opens as an editor tab right away; an agent is started in it in the
  background. Herdr's own focus doesn't jump (`focus:false`).
- **Right-click a space or pane** in the sidebar: switch to it, unmount its folder (only a content root the plugin
  added), open, new tab, rename (space; shell label; agent name), close in Herdr after confirming (a pane, its tab
  when it's the only pane, a space, or a worktree space with its folder; forcing is offered when Herdr refuses because
  of uncommitted changes), "Show Raw" (Herdr's records for it as a read-only JSON tab), and for agents **Hand Off to
  Agent…** (also in the "is done" notification): pick an agent in any space and type what it should do; the prompt
  (`agent.prompt`) adds where the work came from and `herdr agent read <pane>` to read its output. For Claude agents,
  **Switch Claude Session…** starts a new session (`/clear`) or resumes one of Claude Code's saved sessions for the
  agent's folder (`/resume <id>`, newest first, with their titles).
- **Review Branch with Agent…** (right-click an agent): the agent reviews its folder's branch against a branch you
  pick (`git diff <target>...HEAD`) and writes its findings as JSON to `~/.herdr-cockpit/reviews/`. Nothing is posted
  to GitLab or anywhere else. When it's done, a notification lists the findings; each opens the file at its line.
  Findings pointing outside the repository are dropped. Only in trusted projects.
  With the GitLab plugin, **Review with Agent…** is also in the context menu of a merge request's changes in the GitLab
  tool window: the agent reviews those files between the merge request's commits, without checking out its branch.
- **Send to Agent…** (right-click in the editor, or on files in the project view): the selection (or the caret's line)
  or the files go to an agent you pick (the file's space first), with what it should do.
- **Review** (git line of a space, agent rows, right-click): every uncommitted change in that folder's repository as
  HEAD ↔ working tree diffs in one diff view (step through the files with the arrows). Uses the git CLI, so it also
  works for spaces that aren't mounted; only in trusted projects.
- **Usage** (top of the sidebar) for the agents running in Herdr right now, read from their local files once a minute
  while the sidebar is visible: Claude Code (5-hour/weekly plan limits once you allow the opt-in status line via
  "Show plan limits…", which never replaces an existing one; tokens of the current 5-hour session), Codex (live from
  `codex app-server`, at most every 5 minutes, else its session logs), Kiro (credits this month/today; the allowance
  is the setting "Kiro monthly credits"). Nothing leaves the machine.
- **Open Herdr TUI** (disconnected screens, *Tools | Herdr*): `herdr` in an editor tab (a real PTY), against the same
  session as the plugin, showing the project's space (created if missing). Closing the tab ends only that client.
  Optionally, Start Herdr and Open Herdr TUI create a space for every project in the window (setting "Create a space
  for every project in the window"); spaces are never closed for you.
- **Status bar** "⬢ 1 blocked · 2 done" (click: jump to that agent; otherwise a menu with Refresh, Start Herdr, Stop Herdr
  (asks first), Settings, Copy Diagnostics) and a red badge on the tool window icon while an agent is blocked.
- **Help** (`?` in the tool window's title bar, "Help" on the disconnected screens): "How to read the Herdr sidebar"
  in an editor tab, adapted from the VS Code extension's help page.
- **From Herdr to the IDE** with the Herdr plugin in `herdr-plugin/` (`herdr plugin install tezmanian/herdr-cockpit-jetbrains/herdr-plugin`):
  "Open in JetBrains IDE" (the space and pane you're on), "Open selected file" (`path:line:col` and similar forms) and
  "Review changes". It calls the IDE's built-in server (`/api/herdr-cockpit/…`) with the port and a random per-run
  token from `~/.herdr-cockpit/jetbrains.json` (owner-only); requests with an `Origin` (web pages) are refused, and
  links only select an existing space, pane or file of the IDE's own Herdr session. With two JetBrains IDEs open, the
  one started last gets the links.
- Optional: closing a pane's editor tab yourself also closes it in Herdr (setting "Closing a pane's tab yourself":
  never (default, only detaches) / ask / always). Tabs the plugin closes, and closing the project or IDE, only detach.
- Settings: *Settings | Tools | Herdr Cockpit* (binary, socket, a space per project, switching behaviour, space order, notifications, agents offered
  for new tabs, closing tabs, Kiro monthly credits).

## Issues

Bugs and ideas: [GitHub issues](https://github.com/tezmanian/herdr-cockpit-jetbrains/issues).

## Not ported

VS Code-only parts: the Cockpit window, terminal profiles (`+`/split in the terminal panel), the walkthrough. The
sidebar's "Set up Cockpit window" link shows "not available in the JetBrains port".

## Build and install

Needs JDK 21 (the Gradle wrapper downloads Gradle and the IntelliJ Platform).

```bash
./gradlew buildPlugin        # -> build/distributions/herdr-cockpit-jetbrains-<version>.zip
./gradlew test               # unit tests (fake Herdr socket server, real snapshot fixture)
```

Install in PhpStorm: *Settings | Plugins | ⚙ | Install Plugin from Disk…* and pick the zip. Targets 2025.3+
(build 253); depends only on `com.intellij.modules.platform`, so it also runs in IntelliJ IDEA, WebStorm, etc.

### CI

`.github/workflows/build.yml` (GitHub) and `.gitea/workflows/build.yml` (Gitea, on the `jdk21-runner` runner label, i.e. the image from Development/CI-Runner; Gitea ignores `.github/workflows` while `.gitea/workflows` exists): tests and
`buildPlugin` (the zip is uploaded as the `herdr-cockpit-plugin` artifact), then `verifyPlugin`, the JetBrains Plugin
Verifier against PhpStorm 2025.3.6.1 and 2026.2.3. A tag `v<version>` publishes a release with the zip on both. Gitea is
the source and push-mirrors to [GitHub](https://github.com/tezmanian/herdr-cockpit-jetbrains), so a tag pushed to Gitea
releases on GitHub too.
Locally: `./gradlew verifyPlugin` (downloads both PhpStorm versions once).

### Live test and sandbox IDE (use a throwaway session, never your own)

```bash
herdr --session hcjb server &                       # separate session (state is kept per session NAME)
HERDR_SOCKET_PATH=~/.config/herdr/sessions/hcjb/herdr.sock herdr workspace create --cwd /tmp --label demo
HERDR_LIVE_SOCKET=~/.config/herdr/sessions/hcjb/herdr.sock ./gradlew test --tests '*LiveTest*'
./gradlew runIde -PherdrSocket=$HOME/.config/herdr/sessions/hcjb/herdr.sock -PopenProject=/path/to/some/project
HERDR_SOCKET_PATH=~/.config/herdr/sessions/hcjb/herdr.sock herdr server stop
```

If you run this from inside a Herdr pane, `HERDR_SOCKET_PATH` points at your live session and wins over
`HERDR_SESSION`: always set the socket explicitly, as above.

## Layout (src/main/kotlin/io/github/tezmanian/herdrcockpit)

| File | Role |
|---|---|
| `HerdrClient.kt` | NDJSON over the Unix socket: `request()` (one short connection per call), `subscribe()` |
| `Model.kt` | `normalize()`: flat `session.snapshot` → Space → Tab → Pane |
| `Connection.kt` | finding `herdr`, `diagnose()`, `startServer()` (detached, clean env), backoff |
| `Activity.kt` | shell/agent summaries from `pane.process_info` + `pane.read` |
| `Usage.kt` | plan usage of Claude Code, Codex and Kiro from their local files (port of `usage.ts`) |
| `AgentReview.kt` | "Review Branch with Agent…": the prompt and the findings file (`parseReview()` checks the agent's output) |
| `ClaudeSessions.kt` | Claude Code's saved sessions for a folder, for "Switch Claude Session…" |
| `GitInfo.kt` | git CLI with the upstream's hardening (no fsmonitor, filters, hooks, lazy fetch); skipped in untrusted projects |
| `ViewState.kt` | JSON for `media/panel.js` (same shape as the VS Code extension's `viewState.ts`) |
| `HerdrService.kt` | per-project sync loop, connection states, switching, mounting, notifications |
| `HerdrToolWindow.kt` | JCEF tool window, `acquireVsCodeApi()` shim, theme → `--vscode-*` variables, actions |
| `HerdrPaneEditor.kt`, `HerdrTtyConnector.kt` | pane editor tabs: JBTerminalWidget + TtyConnector over `terminal session control` (`observe` while another client has it); the Herdr TUI tab (pty4j) |
| `TermLinks.kt` | file paths in pane output as links (port of `termLinks.ts`) |
| `TermFind.kt`, `HerdrPaneFind.kt` | find in a pane's whole history (port of `termFind.ts`) and the pane tab's find bar |
| `TermEdit.kt` | editing the typed line (delete/replace a selection, Alt+click) and selecting past the edge (port of `termScreen.ts`) |
| `Claims.kt` | `~/.herdr-cockpit/claims`: which window took a pane (shared with the VS Code extension) |
| `HerdrManage.kt` | dialogs and popups: new space/tab, rename, close, review, context menu, Show Raw, usage setup |
| `HerdrStatusBar.kt` | status bar widget and tool window badge |
| `HerdrHelp.kt` | help page (`media/help.html`) in a JCEF editor tab |
| `HerdrLinks.kt` | links from the Herdr plugin: built-in server endpoint, `~/.herdr-cockpit/jetbrains.json` |
| `HerdrSettings.kt` | application settings + configurable |
