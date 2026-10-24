#!/bin/sh
# Refresh the extension files bork-mobile shares with the desktop build.
#
# The desktop tree (bork-browser) is the source of truth for everything
# listed here; this copies it into shared/extensions/, which both the
# Android and the iOS app ship. Files that exist only
# on mobile are never touched:
#
#   youtube-manager/manifest.json        MV2, m.youtube.com, no hover UI
#   youtube-manager/src/pages-mobile.*   replaces the desktop pages.js/.css
#   youtube-manager/src/ui-mobile.css    touch sizes for the chat panel
#   youtube-manager/options/options.html wording for touch
#   youtube-manager/popup/popup.html     full-width popup
#   cyberswine/manifest.json             adds the shim below
#   cyberswine/src/mobile-shim.js        stands in for contextMenus
#
# Usage: tools/sync-extensions.sh [path/to/bork-browser]
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)
desktop=${1:-$here/../bork-browser}
src=$desktop/browser-features/extensions
out=$here/shared/extensions

[ -d "$src/youtube-manager" ] || { echo "no desktop extensions at $src" >&2; exit 1; }

yt=$out/youtube-manager
mkdir -p "$yt/src" "$yt/options" "$yt/popup" "$yt/icons"
for f in src/channels.js src/store.js src/filters.js src/threads.js \
         src/content.js src/ui.css options/options.js options/options.css \
         popup/popup.js; do
  cp "$src/youtube-manager/$f" "$yt/$f"
done
cp "$src/youtube-manager/icons/"*.png "$yt/icons/"

cs=$out/cyberswine
mkdir -p "$cs"
# Everything but the manifest, which mobile keeps its own copy of.
# Tests stay on the desktop side; the app ships only what runs.
(cd "$src/cyberswine" && find . -type f ! -name manifest.json ! -name .DS_Store ! -path "./test/*") |
while read -r f; do
  mkdir -p "$cs/$(dirname "$f")"
  cp "$src/cyberswine/$f" "$cs/$f"
done

# Signed by AMO, so they install on a release GeckoView as-is.
cp "$src/uBlock0@raymondhill.net.xpi" "$out/ublock-origin.xpi"
cp "$src/addon@darkreader.org.xpi" "$out/dark-reader.xpi"

echo "synced from $src"
