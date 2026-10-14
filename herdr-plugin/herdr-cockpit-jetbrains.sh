#!/bin/sh
# Herdr Cockpit for JetBrains: Herdr plugin. POSIX sh on macOS and Linux; needs Herdr's CLI, sed, awk and curl (or wget).
# Helpers (json_str, urlencode, parse_location, session_name) come from the VS Code extension's plugin (MIT).
#
#   herdr-cockpit-jetbrains.sh open        open the focused space (and pane) in the IDE
#   herdr-cockpit-jetbrains.sh open-file   open the selected path[:line[:col]] in the IDE
#   herdr-cockpit-jetbrains.sh review      review the focused pane's changes in the IDE
#
# The IDE side writes ~/.herdr-cockpit/jetbrains.json ({port, token, ...}) when it starts; links go to
# http://127.0.0.1:<port>/api/herdr-cockpit/<action>?token=... and only name a space, a pane or a file.
set -u

HERDR=${HERDR_BIN_PATH:-herdr}
LINK_FILE=${HERDR_COCKPIT_DIR:-$HOME/.herdr-cockpit}/jetbrains.json
PATH=$PATH:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin
export PATH

say() { printf '%s\n' "$*"; }

notify() {
  "$HERDR" notification show "Herdr Cockpit" --body "$1" >/dev/null 2>&1 || true
  say "$1" >&2
}

json_str() {
  sed -nE 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"(([^"\\]|\\.)*)".*/\1/p' | head -n 1 |
    sed -e 's/\\[nr].*//' -e 's/\\"/"/g' -e 's#\\/#/#g' -e 's/\\\\/\\/g'
}
json_num() { sed -nE 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*(-?[0-9]+).*/\1/p' | head -n 1; }

urlencode() {
  S=$1 LC_ALL=C awk 'BEGIN {
    for (i = 1; i < 256; i++) ord[sprintf("%c", i)] = i
    s = ENVIRON["S"]; out = ""
    for (i = 1; i <= length(s); i++) {
      c = substr(s, i, 1)
      if (c ~ /[A-Za-z0-9._~\/-]/) out = out c; else out = out sprintf("%%%02X", ord[c])
    }
    printf "%s", out
  }'
}
q() { [ -n "$2" ] && printf '&%s=%s' "$1" "$(urlencode "$2")"; return 0; }

session_name() {
  if [ -n "${HERDR_SESSION:-}" ]; then
    printf '%s' "$HERDR_SESSION"
  else
    printf '%s' "${HERDR_SOCKET_PATH:-}" | sed -nE 's#.*/sessions/([^/]+)/herdr\.sock$#\1#p'
  fi
}

ctx() { printf '%s' "${HERDR_PLUGIN_CONTEXT_JSON:-}" | json_str "$1"; }

# send ACTION QUERY: call the IDE's built-in server.
send() {
  if [ ! -f "$LINK_FILE" ]; then
    notify "No JetBrains IDE with Herdr Cockpit has run yet ($LINK_FILE is missing)."
    return 1
  fi
  port=$(json_num port <"$LINK_FILE") token=$(json_str token <"$LINK_FILE")
  url="http://127.0.0.1:$port/api/herdr-cockpit/$1?token=$token$2$(q session "$(session_name)")"
  if command -v curl >/dev/null 2>&1; then
    out=$(curl -sS --max-time 5 -w '\n%{http_code}' "$url" 2>&1)
    [ "$(printf '%s' "$out" | tail -n 1)" = 200 ] && return 0
  else
    out=$(wget -q -O - --timeout=5 "$url" 2>&1) && return 0
  fi
  # The IDE answers a refused link with a small HTML page: keep its text.
  notify "The JetBrains IDE didn't take the link (is it running?): $(printf '%s' "$out" | sed -e 's/<[^>]*>/ /g' | tr -s ' \n\t' ' ' | cut -c1-200)"
  return 1
}

space_query() {
  printf '%s%s' "$(q space "${HERDR_WORKSPACE_ID:-$(ctx workspace_id)}")" "$(q pane "${HERDR_PANE_ID:-$(ctx focused_pane_id)}")"
}

parse_location() {
  printf '%s\n' "$1" | head -n 1 | sed -E \
    -e 's/^[[:space:]]+//; s/[[:space:]]+$//' \
    -e 's/^File "([^"]+)", line ([0-9]+).*/\1	\2	/' \
    -e '/	/!s/^[`"'"'"'(<[]+//' \
    -e '/	/!s/^(.+)\(([0-9]+)(,[[:space:]]*([0-9]+))?\).*$/\1	\2	\4/' \
    -e '/	/!s/[]`"'"'"')>,;.:]+$//' \
    -e '/	/!s/^(.+):([0-9]+):([0-9]+)(:.*)?$/\1	\2	\3/' \
    -e '/	/!s/^(.+):([0-9]+)(:.*)?$/\1	\2	/' \
    -e '/	/!s/^(.+)#L([0-9]+)(C([0-9]+))?$/\1	\2	\4/' \
    -e '/	/!s/$/		/'
}

cmd_open_file() {
  sel=$(ctx selected_text)
  if [ -z "$sel" ]; then
    notify "Select a path first (like src/app.php:42), then run Open selected file."
    return 0
  fi
  loc=$(parse_location "$sel")
  file=$(printf '%s' "$loc" | cut -f1) line=$(printf '%s' "$loc" | cut -f2) col=$(printf '%s' "$loc" | cut -f3)
  case $file in
  "~"/*) file=$HOME/${file#\~/} ;;
  /*) ;;
  *)
    pane=${HERDR_PANE_ID:-}
    base=
    [ -n "$pane" ] && base=$("$HERDR" pane get "$pane" 2>/dev/null | json_str foreground_cwd)
    [ -n "$base" ] || base=$(ctx focused_pane_cwd)
    [ -n "$base" ] || base=$(ctx workspace_cwd)
    file=$base/$file
    ;;
  esac
  if [ ! -e "$file" ]; then
    notify "No such file: $file"
    return 0
  fi
  send file "$(q path "$file")$(q line "$line")$(q col "$col")"
}

case ${1:-} in
open) send open "$(space_query)" ;;
review) send review "$(space_query)" ;;
open-file) cmd_open_file ;;
parse-location) parse_location "${2:-}" ;; # for tests
*)
  say "usage: herdr-cockpit-jetbrains.sh open|open-file|review" >&2
  exit 64
  ;;
esac
