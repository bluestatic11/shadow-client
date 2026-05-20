#!/usr/bin/env bash
# Bake real Minecraft skin heads from mc-heads.net into chat-mockup.svg.
# Replaces the placeholder <use href="#face"> references with <image>
# tags carrying base64-encoded PNGs of each named player's head crop.
#
# Mapping is fixed below — extend as the mockup gains more avatars.

set -eu
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
SVG="$ROOT/docs/chat-mockup.svg"
[ -f "$SVG" ] || { echo "no svg at $SVG" >&2; exit 1; }

# clip-id  →  username  →  size
declare -A NAMES=(
  [vfa1]="Notch"
  [vfa2]="jeb_"
  [vfa3]="dinnerbone"
  [vfa4]="Edisongu"
  [meav]="Edisongu"
)
declare -A SIZES=(
  [vfa1]="24" [vfa2]="24" [vfa3]="24" [vfa4]="24"
  [meav]="32"
)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for clip in "${!NAMES[@]}"; do
  name="${NAMES[$clip]}"
  size="${SIZES[$clip]}"
  png="$TMP/${clip}.png"
  echo "fetching $name @ ${size}px → $clip"
  if ! curl -fsSL "https://mc-heads.net/avatar/$name/$size" -o "$png"; then
    echo "  fetch failed for $name; skipping" >&2
    continue
  fi
  b64="$(base64 -w0 "$png")"
  # Match the existing <use href="#face" ...> inside the matching
  # clip-path group and rewrite it as an <image> with the base64.
  # Use python-less sed; the marker is the clip-path id so each row
  # only matches once.
  if [ "$clip" = "meav" ]; then
    repl='<image href="data:image/png;base64,'"$b64"'" x="0" y="0" width="36" height="36"/>'
  else
    repl='<image href="data:image/png;base64,'"$b64"'" x="-2" y="-2" width="24" height="24"/>'
  fi
  # Use Python's stdin since the base64 has slashes that confuse sed.
  # We don't have python; use awk with a sentinel approach instead.
  marker="<g clip-path=\"url(#${clip})\">"
  awk -v marker="$marker" -v repl="$repl" '
    {
      if (index($0, marker) > 0) {
        # Replace anything between marker and </g> on the same line
        # with marker + repl + </g>.
        line = $0
        before = substr(line, 1, index(line, marker) + length(marker) - 1)
        rest = substr(line, index(line, marker) + length(marker))
        # rest currently starts with the old <use .../> followed by </g>...
        endpos = index(rest, "</g>")
        after = substr(rest, endpos)
        print before repl after
        next
      }
      print
    }
  ' "$SVG" > "$TMP/svg.new"
  mv "$TMP/svg.new" "$SVG"
done

echo "done; chat-mockup.svg updated"
