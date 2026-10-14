# Herdr Cockpit for JetBrains IDEs

A community (unofficial) client for [Herdr](https://herdr.dev) in PhpStorm and other JetBrains IDEs: Herdr's spaces,
agents and shells in the IDE. A port of [Herdr Cockpit for VS Code](https://github.com/sriharirao92/vscode-herdr-cockpit)
(MIT, see `LICENSE.upstream`). This port is MIT licensed too (`LICENSE`). The sidebar renderer (`media/panel.js`, `panel.css`), agent logos and codicons come
from that project unchanged (see `THIRD_PARTY_NOTICES.md`).

The plugin is a **client of the Herdr server**. It never owns agent processes; Herdr does.

## What works (MVP, v0.1.0)

- **Herdr tool window** (right side): spaces, agents and shells with status, "Needs you", shell activity
  (what's running / last command / last output), git line per space (branch, last commit, ahead/behind, changes).
  Disconnected screens explain why (not installed / not running / crashed / incompatible / unreachable) and can
  start the server.
- **Switch a space** (click its header, or *Tools | Herdr | Switch Herdr Space…*): adds its folder as a content root
  of the project (only roots the plugin added are ever removed), focuses it in Herdr, and opens every pane as an
  **editor tab**: agents most urgent first, then shells.
- **Pane tabs**: a JediTerm terminal fed by `herdr terminal session control <pane> --takeover`. Typing, colors,
  resize, selection and copy work; the mouse wheel scrolls Herdr's scrollback. Closing a tab only detaches; the
  program keeps running in Herdr.
- Live updates via `events.subscribe` (re-snapshot on every event, 5 s safety poll), notifications when an agent is
  blocked or done, follow mode (Herdr's focus moves → the IDE follows, in projects you switched from the IDE).
- **New space** (`+` next to "Spaces"): in a folder you pick, or on a new git worktree (and branch) of an existing
  space's repository. **New tab** (in every space): a shell, a named shell, or an agent (your favorites first, the
  rest under "More agents…"). The new pane opens as an editor tab right away; an agent is started in it in the
  background. Herdr's own focus doesn't jump (`focus:false`).
- **Right-click a space or pane** in the sidebar: open, new tab, rename (space; shell label; agent name), close in
  Herdr after confirming (a pane, its tab when it's the only pane, a space, or a worktree space with its folder;
  forcing is offered when Herdr refuses because of uncommitted changes).
- **Review** (git line of a space, agent rows, right-click): every uncommitted change in that folder's repository as
  HEAD ↔ working tree diffs in one diff view (step through the files with the arrows). Uses the git CLI, so it also
  works for spaces that aren't mounted; only in trusted projects.
- Optional: closing a pane's editor tab yourself also closes it in Herdr (setting "Closing a pane's tab yourself":
  never (default, only detaches) / ask / always). Tabs the plugin closes, and closing the project or IDE, only detach.
- Settings: *Settings | Tools | Herdr Cockpit* (binary, socket, switching behaviour, notifications, agents offered
  for new tabs, closing tabs).

## Not ported yet

Plan usage (Claude/Codex/Kiro), the
Herdr plugin for jumping from Herdr to the IDE (deep links), Open Herdr TUI.
These buttons exist in the sidebar but show "not available in the JetBrains port yet".

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
Verifier against PhpStorm 2025.3.6.1 and 2026.2.3. A tag `v<version>` on GitHub also publishes a release with the zip.
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
| `GitInfo.kt` | git CLI with the upstream's hardening (no fsmonitor, filters, hooks, lazy fetch); skipped in untrusted projects |
| `ViewState.kt` | JSON for `media/panel.js` (same shape as the VS Code extension's `viewState.ts`) |
| `HerdrService.kt` | per-project sync loop, connection states, switching, mounting, notifications |
| `HerdrToolWindow.kt` | JCEF tool window, `acquireVsCodeApi()` shim, theme → `--vscode-*` variables, actions |
| `HerdrPaneEditor.kt`, `HerdrTtyConnector.kt` | pane editor tabs: JBTerminalWidget + TtyConnector over `terminal session control` |
| `HerdrSettings.kt` | application settings + configurable |
