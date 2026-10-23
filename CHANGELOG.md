# Changelog

## 0.1.36
- Windows (untested): the plugin connects to Herdr's named pipe, which Herdr names after its socket path, as the VS
  Code extension does. The default socket path there is `%APPDATA%\herdr\herdr.sock`.

## 0.1.35
- Settings | Tools | Herdr Cockpit | Agent integrations: Herdr's agent integrations with their state (installed,
  outdated) and whether the agent was found, with Install / Update and Uninstall. They change the agent's own
  configuration, so only on a click. When Herdr reports an integration as outdated, a notification says so once per
  Herdr version.

## 0.1.34
- Split and zoom Herdr panes from the IDE: "Split Right…", "Split Down…" and "Zoom in Herdr / Unzoom" in a pane's
  right-click menu in the sidebar and in a pane tab's context menu. A split asks for a shell or an agent, like a new
  tab, and opens the new pane as a tab. Zoom is Herdr's: every client of the session sees it.

## 0.1.33
- Fix: Switch Claude Session… had no search field to type in, only the hidden speed search, which did nothing while
  the sidebar kept the keyboard focus. The search field is now always shown above the list.

## 0.1.32
- Fix: Shift+Enter in a pane tab still sent Claude Code's prompt. The line feed it typed is Enter to a program that
  turned on the kitty keyboard protocol, as Claude Code does. Shift+Enter now asks Herdr for Ctrl+J, which Herdr
  encodes for the keyboard mode the program in the pane uses.

## 0.1.31
- Fix: a pane tab opened in the background (e.g. while switching spaces) took the pane over at a default 120×32
  before it had a size of its own. That shrank the program in the pane, and Claude Code's screen stayed cut off and
  garbled once the tab was shown. The stream now starts when the tab gets its real size.

## 0.1.30
- Shift+Enter in a pane tab starts a new line in an agent's prompt (Claude Code, Codex) instead of sending it, so
  longer prompts can be written in several lines. It sends a line feed (like Ctrl+J); Enter still sends.

## 0.1.29
- Switch Claude Session… has a search field: typing filters the sessions by title (and date). It now lists the 100
  newest sessions of the folder instead of 30.

## 0.1.28
- Fix: clicking a file path printed in a pane tab (or a finding in an agent review's list) threw "Access is allowed
  from write thread only" in newer IDEs instead of opening the file. The file is now looked up and refreshed in the
  background and opened on the UI thread.

## 0.1.27
- Fix: pasting a longer text into a pane tab could arrive cut off or garbled (e.g. Claude Code lost its beginning).
  Herdr's frames don't carry the program's modes, so the tab never used bracketed paste: the paste went out as typed
  text with Enter for every line break. Pastes are now always sent marked as a paste, as in the VS Code extension.

## 0.1.26
- Agent reviews are followed through Herdr (`agent.prompt` with a wait, then `agent.wait`) instead of by watching
  snapshots. When the agent stops at a question during a review, a notification offers Answer… and Show; when it
  finishes without writing the findings file, a notification says so instead of the review staying open forever.
  The agent's own "needs you" / "is done" notifications are left out while it reviews.
- **Answer…** for a blocked agent (right-click menu, its "needs you" notification, "is waiting for an answer"): shows
  the agent's screen with keys to answer its dialog (↑ ↓ 1 2 3 Enter Esc), sent with `agent.send_keys` (only to that
  agent), and reads the screen again after each key.
- Closing a project ends Herdr requests still in flight and the request timeout thread.

## 0.1.25
- Fix: a NullPointerException at startup ("Cannot invoke kotlin.Lazy.getValue()" in HerdrService.getClaims) meant
  stale takeover claims in ~/.herdr-cockpit/claims weren't pruned. The startup task could run before the field it
  uses was set.

## 0.1.24
- Forges (GitLab, GitHub, Gitea) now register through an extension point of their own,
  `io.github.tezmanian.herdrcockpit.forge` (interface `ForgeProvider`, experimental). A plugin can add a further forge
  without touching the core; the built-in ones register the same way. A review server entry whose type no plugin
  provides any more stays in the settings unchanged instead of being dropped. No other change in behaviour.

## 0.1.23
- Review templates: what Review Branch with Agent… and Review with Agent… send now comes from the prompt library, in
  the new categories "branch template" and "changes template" (with several, the review dialog lets you pick and remembers your choice), with
  the variables {repo}, {branch}, {target}, {base}, {head}, {diff}, {files}, {instructions}, {out} and {format}. Lines
  whose variables are all empty are left out; a template without {out} gets the findings-file rules appended. The
  built-in templates are added to your library once. Branch reviews now also list the changed files.
- More detailed built-in presets: Security, Real bugs only, Missing tests, Explain and flag risks, Write unit tests.
  They replace the short presets of earlier versions in your library, unless you edited them.

## 0.1.22
- Internal: the review flow (Review, Review Branch with Agent…, Review with Agent…, sending findings to a forge) moved
  out of HerdrManage into ReviewFlow, which needs only a narrow ReviewHost from the service. The shared popups, dialogs
  and background tasks live in ProjectUi. No change in behaviour.

## 0.1.21
- Review servers: the CLI path (glab, gh or tea, by the server's type) is now set in the server's entry, shown for the
  sign-ins Automatic and CLI, instead of three global fields. Paths you had set move to your servers of that type
  once; a notification says so, and names any path no server took. Servers without an entry find the CLIs in the
  usual install folders or on PATH.

## 0.1.20
- Fixed OK staying disabled in the review server and prompt dialogs after the first check, e.g. when the URL started
  as "https://".
- Review server dialog: Sign-in offers only what the type supports (the IDE account only for GitLab, the CLI by its
  name), its hints and the token's scopes follow the type, and the token field shows only where a token is used.

## 0.1.19
- Send an agent review to GitHub and Gitea too, as a pending review on the pull request (GitLab: draft notes as
  before). One flow for all three: find the request (choose when several fit), preview with checkboxes, nothing
  published until you submit it on the forge.
- Review servers (Settings | Tools | Herdr Cockpit): a table of GitLab, GitHub and Gitea servers with their sign-in:
  a CLI (`glab`, `gh`, `tea`), an access token kept in the password safe, the IDE's GitLab account, or automatic.
  A Test button shows who you're signed in as. Servers without an entry are still detected automatically.

## 0.1.18
- Fixed stale text below a pane's screen (e.g. an old prompt line under Claude's input) after a resize, when Herdr's
  pane was smaller than the tab.

## 0.1.17
- Prompt library: the saved prompts are now a table (Name | Category | Text) with add, edit, remove and reorder; a
  prompt can span several lines. The dialogs offer them by name. Your existing prompts are taken over once.
- Project Prompts (Settings | Tools | Herdr Cockpit | Project Prompts): prompts of a project, in
  `.idea/herdr-cockpit-prompts.xml` to share through the repository. Offered only in trusted projects, marked
  "(project)".

## 0.1.16
- Send to GitLab as Drafts…: an agent review's findings become draft notes on the merge request (via `glab` when it's
  signed in, otherwise with a personal access token kept in the password safe). A preview picks what to send; nothing
  is published until you submit the review. Without `glab` it uses the IDE's own GitLab account for the host if
  there is one.
- The send entry only appears for a GitLab: the plugin tells GitLab, GitHub and Gitea apart (known hosts, `glab`/`gh`/
  `tea` signed in to the host, else one anonymous request to the server). Paths to these CLIs can be set in Settings |
  Tools | Herdr Cockpit | Review tools.
- The merge request is the one GitLab lists for the reviewed commit; when several fit (e.g. two from one branch), you
  pick one instead of the plugin taking the first.

## 0.1.15
- Fixed a ClassCastException in the new UI when the tool window icon shows the red "blocked" dot.

## 0.1.14
- Review with Agent… in PhpStorm's GitLab tool window: right-click a merge request's changes (or some of its files);
  an agent you pick reviews exactly those changes between the merge request's commits, without checking out its
  branch. Findings come back as with Review Branch with Agent…; nothing is posted.
- Both reviews ask for optional extra instructions (e.g. "focus on security") before sending.
- Saved prompts (Settings | Tools | Herdr Cockpit | Prompts, one "category: text" per line): the prompt dialogs of the
  reviews (category review) and of Send to Agent… and Hand Off to Agent… (general) offer them as presets that fill the
  text.

## 0.1.13
- Review Branch with Agent…: right-click an agent to have it review its folder's branch against another branch
  (`git diff <target>...HEAD`). It writes its findings to `~/.herdr-cockpit/reviews/`, nothing is posted; when it's
  done, a notification lists them and each one opens the file at its line.

## 0.1.12
- Switch Claude Session…: right-click a Claude agent to start a new session (`/clear`) or resume one of Claude Code's
  saved sessions for its folder.

## 0.1.11
- Plan usage reads Claude Code transcripts only where they grew, and Kiro sessions only when they changed, instead of
  every file every minute in every window.
- Pane find bar: stepping through matches of a pane that keeps printing reads its history at most once a second, and
  highlighting only looks at the matches on screen.
- Send to Agent…: OK stays off until you've written what the agent should do.
- Links from Herdr: the token check is a tested function; an empty token never matches.
- Pane names of closed panes are dropped; focusing a space or agent goes through the tested Herdr calls.

## 0.1.10
- Send to Agent…: right-click in the editor (the selection or the caret's line) or on files in the project view to
  send them to an agent in Herdr, with what it should do.
- Pane tab: Ctrl/Cmd+F opens a find bar over the pane's whole history in Herdr. The pane scrolls to each match and
  centers it, matches are highlighted, Enter/↑/F3 go to older and Shift+Enter/↓/Shift+F3 to newer matches, and Tab
  lists every matching line. Smart case; `/regex/` searches with a regular expression.
- Pane tab: Backspace or Delete deletes a selection on the line being typed, and typing replaces it.
- Pane tab: Alt+click moves the cursor there, Shift+click extends the selection.
- Pane tab: dragging a selection past the top or bottom row scrolls Herdr's history, and copying includes the rows
  that scrolled out of view.

## 0.1.9
- Hand Off to Agent…: right-click an agent (or use the "is done" notification) to send another agent a task that
  points at this agent's output.

## 0.1.8
- Pane tab: Ctrl/Cmd+F opens the pane's whole history as a document with the editor's find bar.

## 0.1.7
- Takeover: when another window or client takes a pane, the tab shows it read-only and says who has it; the first
  key, or Take Back in the notification, takes it back.
- Pane tab: Open Pane Output in Editor, file paths printed in a pane open the file at that line, and
  Shift+PageUp/PageDown and Ctrl/Cmd+Home/End scroll Herdr's history.
- Sidebar: busy and recently active spaces first.

## 0.1.6
- The status bar menu no longer offers Open Herdr TUI.

## 0.1.5
- Files dropped on a pane's tab are typed in as their paths (`@path` for Claude Code).

## 0.1.4
- Editor tabs titled "space · pane"; the sidebar is on the left; no broad content roots.

## 0.1.3
- Project picker for new spaces, Stop Herdr, and the JCEF dependency PhpStorm 2026.2 needs.

## 0.1.1
- The Herdr TUI tab shows the project's space; Start Herdr creates the first space in the project directory.

## 0.1.0
- Spaces, agents and shells in a sidebar; panes as editor tabs; new spaces and tabs, rename and close; review
  changes; plan usage of Claude Code, Codex and Kiro; status bar; links from Herdr.
