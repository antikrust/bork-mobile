#!/bin/sh
# Refresh the extension files bork-mobile shares with the desktop build.
#
# The desktop tree (bork-browser) is the source of truth for everything
# listed here; this copies it into shared/extensions/, which both the
# Android and the iOS app ship. Files that exist only on mobile are never
# touched:
#
#   youtubedaddy/manifest.json        MV2, m.youtube.com, no hover UI
#   youtubedaddy/src/pages-mobile.*   replaces the desktop pages.js/.css
#   youtubedaddy/src/ui-mobile.css    touch sizes for the chat panel
#   youtubedaddy/src/subs-mobile.js   replaces subs.js (m.youtube.com, WebKit)
#   youtubedaddy/comments/*           comment deletion, one tab at a time
#   youtubedaddy/popup/popup.html     full-width popup
#   cyberswine/manifest.json          adds the shim below
#   cyberswine/src/mobile-shim.js     stands in for contextMenus
#
# The settings page is the desktop's, made here rather than kept by hand:
# options.js also looks for m.youtube.com tabs, and options.html hides what
# the phone's YouTube script does not do (video filters, the player, the two
# outside services) and words the buttons for touch.
#
# Usage: tools/sync-extensions.sh [path/to/bork-browser]
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)
desktop=${1:-$here/../bork-browser}
src=$desktop/browser-features/extensions
out=$here/shared/extensions

[ -d "$src/youtubedaddy" ] || { echo "no desktop extensions at $src" >&2; exit 1; }

yt=$out/youtubedaddy
mkdir -p "$yt/src" "$yt/options" "$yt/popup" "$yt/icons"
for f in src/channels.js src/store.js src/filters.js src/threads.js \
         src/content.js src/ui.css src/sync.js options/options.css \
         popup/popup.js; do
  cp "$src/youtubedaddy/$f" "$yt/$f"
done
cp "$src/youtubedaddy/icons/"*.png "$yt/icons/"

python3 - "$src/youtubedaddy/options" "$yt/options" <<'PY'
import sys
src, out = sys.argv[1], sys.argv[2]

def swap(text, old, new):
    if old not in text:
        sys.exit(f"sync-extensions: the desktop settings page changed; update the mobile edit for: {old[:60]!r}")
    return text.replace(old, new, 1)

js = open(f"{src}/options.js", encoding="utf-8").read()
js = swap(js, 'const open = await api.tabs.query({ url: "https://www.youtube.com/*" });',
          '// bork mobile: a phone is sent to m.youtube.com, so look there too.\n'
          '    const open = await api.tabs.query({ url: ["https://m.youtube.com/*", "https://www.youtube.com/*"] });')
js = swap(js, 'url: "https://www.youtube.com/feed/channels"', 'url: "https://m.youtube.com/feed/channels"')
open(f"{out}/options.js", "w", encoding="utf-8").write(js)

html = open(f"{src}/options.html", encoding="utf-8").read()
html = swap(html, '<link rel="stylesheet" href="options.css" />',
            '<link rel="stylesheet" href="options.css" />\n'
            '    <!-- options.css gives every button display:inline-block, which beats the\n'
            '         hidden attribute. -->\n'
            '    <style>[hidden] { display: none !important; }</style>')
for heading in ("Videos", "Player", "From other people"):
    html = swap(html, f"<section>\n        <h2>{heading}</h2>", f"<section hidden>\n        <h2>{heading}</h2>")
html = swap(html, "Hidden everywhere on YouTube. Block from the SHUT UP button on any video, or add a name here.",
            "Hidden everywhere on YouTube. Press SHUT UP under any video, or add a name here.")
html = swap(html, "Tabs on your subscriptions feed. Press TAG on a video or a channel page to put the channel in a tab.",
            "Tabs on your subscriptions feed. Press TAG under a video to put its channel in a tab.")
html = swap(html, "<em>Watch</em> holds single videos, saved to YouTube's Watch later.",
            "<em>Watch</em> holds single videos, as a note kept on the phone.")
html = swap(html, '<a class="button" href="../comments/comments.html" target="_blank">',
            '<a class="button" href="../comments/comments.html">')
open(f"{out}/options.html", "w", encoding="utf-8").write(html)
PY

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
