# Changelog

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
