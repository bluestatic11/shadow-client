#!/usr/bin/env bash
# Re-render docs/chat-mockup.svg → docs/chat-mockup.png via headless Edge.
# Invoked from the PostToolUse hook in .claude/settings.json on every
# Edit/Write. We self-gate on mtime so the hook does no work unless the
# SVG was actually touched since the last PNG render.

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
SVG="$ROOT/docs/chat-mockup.svg"
PNG="$ROOT/docs/chat-mockup.png"

# Nothing to do if there's no SVG (different repo / pre-mockup branch).
[ -f "$SVG" ] || exit 0

# Skip if PNG is already up-to-date.
if [ -f "$PNG" ] && [ "$PNG" -nt "$SVG" ]; then
  exit 0
fi

# Chrome's headless screenshot is more reliable than Edge's right now
# (Edge's `--headless=new` recently started returning exit-0 without
# writing the file on this machine); fall back to Edge if Chrome's
# missing.
BROWSER="/c/Program Files/Google/Chrome/Application/chrome.exe"
[ -f "$BROWSER" ] || BROWSER="/c/Program Files (x86)/Google/Chrome/Application/chrome.exe"
[ -f "$BROWSER" ] || BROWSER="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
[ -f "$BROWSER" ] || BROWSER="/c/Program Files/Microsoft/Edge/Application/msedge.exe"
[ -f "$BROWSER" ] || { echo "no chromium browser found to render mockup" >&2; exit 0; }

TMP="$(mktemp -d 2>/dev/null || echo /tmp/render-mockup-$$)"
mkdir -p "$TMP"

# msedge expects native Windows paths for --screenshot and a file:/// URL
# for the input. Use cygpath to convert when available.
if command -v cygpath >/dev/null 2>&1; then
  SVG_URL="file:///$(cygpath -m "$SVG")"
  PNG_OUT="$(cygpath -w "$PNG")"
else
  SVG_URL="file://$SVG"
  PNG_OUT="$PNG"
fi

"$BROWSER" --headless=new --disable-gpu --hide-scrollbars \
  --window-size=1280,720 --no-sandbox --user-data-dir="$TMP" \
  --screenshot="$PNG_OUT" "$SVG_URL" >/dev/null 2>&1 || true

if [ -s "$PNG" ] && [ "$PNG" -nt "$SVG" ]; then
  echo "chat-mockup.png re-rendered from chat-mockup.svg"
else
  echo "chat-mockup.png render failed (browser exited but produced no fresh PNG)" >&2
fi

rm -rf "$TMP" 2>/dev/null || true
exit 0
