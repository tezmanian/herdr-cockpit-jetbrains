# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

JetBrains (PhpStorm, 2025.3+/build 253) port of the VS Code extension Herdr Cockpit. The plugin is a **client** of the
Herdr server: it talks to Herdr's Unix socket and never owns agent processes. See README.md for the feature list.

## Commands

Needs JDK 21. If `java` isn't on PATH, set `JAVA_HOME` first. On the dev box that is
`JAVA_HOME=~/.local/jdk/jdk-21.0.12.1+1`.

```bash
./gradlew test                                   # unit tests
./gradlew test --tests '*ActionsTest.renameTab'  # a single test
./gradlew buildPlugin                            # -> build/distributions/herdr-cockpit-jetbrains-<version>.zip
./gradlew verifyPlugin                           # Plugin Verifier vs PhpStorm 2025.3.6.1 and 2026.2.3 (what CI runs)
./gradlew test buildPlugin verifyPlugin --console=plain -q   # full CI check locally
```

CI: `.gitea/workflows/build.yml` (runner label `jdk21-runner`) and `.github/workflows/build.yml`. Keep the two in sync.

## Never touch the user's own Herdr session

Tests and the sandbox IDE must use a throwaway session. `LiveTest` types into panes, and closing or renaming acts on
real panes. Inside a Herdr pane, `HERDR_SOCKET_PATH` points at the live session and takes precedence over
`HERDR_SESSION`, so always pass the socket explicitly:

```bash
herdr --session hcjb server &
HERDR_LIVE_SOCKET=~/.config/herdr/sessions/hcjb/herdr.sock ./gradlew test --tests '*LiveTest*'   # skipped when unset
./gradlew runIde -PherdrSocket=$HOME/.config/herdr/sessions/hcjb/herdr.sock -PopenProject=/path/to/project
```

Never start real agents (`agent.start`) in tests: they cost money and use the user's account.

## Architecture

Package `io.github.tezmanian.herdrcockpit`. The README's "Layout" table lists the role of each file.

**Data flow (Herdr → UI):** `HerdrClient` speaks NDJSON over the socket. `request()` opens one short connection per
call, and `subscribe()` holds `events.subscribe` open. On every event (plus a 5 s safety poll), `HerdrService`
re-snapshots: `session.snapshot` goes through `normalize()` (Model.kt) into Space → Tab → Pane. Activity and git info
are added, and `buildViewState()` (ViewState.kt) turns the result into JSON. That JSON must keep the same shape as
upstream's `viewState.ts`, because the sidebar renderer `src/main/resources/media/panel.js` comes from the VS Code
extension. Change panel.js only minimally; so far the only local change is the `contextmenu` listener.

**Commands (UI → Herdr):** `HerdrToolWindow` hosts panel.js in JCEF with an `acquireVsCodeApi()` shim and maps theme
colors to `--vscode-*` CSS variables. Messages from panel.js go to `HerdrService.handle(command, spaceId, paneId)`.
From there, interactive flows (dialogs, popups, confirmations, review diffs) live in `HerdrManage`. The typed
socket calls live in `HerdrActions`, which has no IntelliJ imports so that `ActionsTest` can run it against a fake
socket server.

**Panes as editor tabs:** `HerdrPaneEditor` runs a JediTerm widget. Its `HerdrTtyConnector` spawns
`herdr terminal session control <pane> --takeover`. Closing a tab only detaches, unless the `closeTabInHerdr`
setting says otherwise. `HerdrService` ignores tab closes the plugin itself causes, and tab closes during project
close.

**Git** (GitInfo.kt) shells out to the git CLI with the upstream's hardening (fsmonitor, filters and hooks off). It is
skipped in untrusted projects.

## Conventions

- Herdr API rules that bite: every create passes `focus:false`. `worktree.create` refuses `workspace_id` and `cwd`
  together. Agent names must match `AGENT_NAME`. `null` params are sent as JSON null to mean "clear".
- Avoid platform APIs that are deprecated in 2026.2: `verifyPlugin` flags them (for example `ReadAction.compute`; use
  `ApplicationManager.getApplication().runReadAction`).
- Known limits are marked in code as `// shortcut: <limit>, <when to upgrade>`.
- Commits reference Gitea issues (`Closes #N`). Remote: gitea.root-zone.info/tez/herdr-cockpit.
