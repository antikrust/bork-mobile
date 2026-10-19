# bork mobile

bork for Android: the same Gecko engine as the desktop build, so the same
**Manifest V2 extensions** — the complete **uBlock Origin**, not the Lite
version — plus bork's own add-ons, on a phone.

It is a GeckoView app, not a Fenix fork: a small browser shell written for
bork, around the Gecko engine Mozilla ships as a library.

## What it does

- **Workspaces** — named sets of tabs, like the desktop's. Switch them from
  the chips along the top of the tab tray; long-press a tab to move it to
  another workspace, long-press a workspace to rename or delete it. The badge
  left of the address bar shows which one you are in (tap it for the tray,
  long-press to jump straight to another workspace).
- **Zen mode** — toolbars and status bar gone, only the page. A faint dot on
  the right edge brings them back.
- **Start page** — pinned sites as tiles. Star a page from the menu to pin
  it; long-press a tile to rename, reorder or unpin it.
- **Private tabs**, **desktop site** per tab, **find in page**, downloads,
  bookmarks and history (kept on the phone and nowhere else), and the
  address bar at the bottom if you prefer it there (Settings).
- **No telemetry, no crash reports, no accounts.** Tracking protection and
  Global Privacy Control are always on; Safe Browsing is on, as on the
  desktop.

### Add-ons that ship

| | |
|---|---|
| **uBlock Origin** | the AMO-signed build the desktop ships, full MV2 |
| **Dark Reader** | likewise |
| **YouTube Manager** | bork's own, ported to m.youtube.com — see below |
| **Cyberswine** | bork's own, unchanged but for a shim (see below) |

Every add-on with a toolbar button is in the menu under **Add-ons**, and
opens its popup in a sheet — uBlock's popup, Dark Reader's switch, YouTube
Manager's quick settings. **Menu → Add-ons** lists them all, with their
settings pages, an off switch and Remove. Removing a shipped add-on sticks,
as on the desktop. Anything else installs from addons.mozilla.org.

### YouTube Manager on a phone

The desktop add-on is built around a mouse: a floating bar follows the
pointer over video cards, and HIDE drives YouTube's own ⋮ menu. None of that
translates, so the mobile version keeps the same settings, store and matching
(`filters.js` and friends are the desktop files, copied unchanged) and
replaces only the part that touches YouTube's pages:

| Desktop | Mobile |
|---|---|
| Channel blocking on home, search, the watch sidebar, Shorts | **Kept**, on m.youtube.com's markup (and on the desktop site, if you ask for it) |
| SHUT UP in a bar that follows the pointer | **A SHUT UP button under every video card**, and one beside Subscribe on the watch page |
| Subscription tabs + TAG | **Kept**: tabs at the top of the subscriptions feed, a TAG button under each video, a bottom sheet to pick the tab |
| Shorts switch on the subscriptions feed | **Kept** |
| Live chat filtering, SHUT UP per message, threaded view | **Kept** — m.youtube.com does not show live chat, so live watch pages get a **CHAT** button that opens YouTube's own chat page in a new tab, where the desktop chat code runs unchanged. Tap a message to show its SHUT UP |
| HIDE / DONE (presses YouTube's ⋮ → Hide) | **Stripped** — the mobile menu is a different bottom sheet; pressing it for you would be guesswork |
| Watch tab saves with YouTube's *Save to Watch later* | **Stripped** — the Watch tab still holds single videos, as a note kept here only |
| End-of-video suggestion wall | **Stripped** — the mobile player does not build one |
| "Who do I subscribe to" from the guide drawer | **Replaced** — the mobile site has no drawer; Subscribe buttons and the subscriptions feed's channel strip say it instead, and it is remembered |

## Building

Needs the Android SDK with **platform 37.2** and a JDK 17+ (Android Studio's
bundled one works). Gradle and everything else comes via the wrapper.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # self-signed with the debug key, like the desktop builds
```

Install on a phone or an emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GeckoView ships as an arm64 library here (every current phone, and Apple
Silicon emulators).

## Tests

```bash
deno test --allow-read tests/
```

The YouTube Manager mobile script against markup captured from the real
m.youtube.com — channel names from bylines, shorts and Subscribe buttons. No
browser, no network.

## Keeping the add-ons in step with the desktop

The shared add-on files are copies of the ones in `bork-browser`. After
changing them there:

```bash
tools/sync-extensions.sh            # expects ../bork-browser next to this repo
```

It copies everything shared and never touches the mobile-only files (listed
at the top of the script). **Bump the mobile `manifest.json` version
afterwards** — a built-in add-on is only reloaded when its version changes.

## License

[Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0/), as
bork.

bork is based on Floorp, which is based on Mozilla Firefox. It is not
affiliated with, endorsed by, or connected to Mozilla, Ablaze or the Floorp
project. GeckoView is Mozilla's, MPL 2.0.
