# bork mobile

bork for phones: **Android** and **iOS**, one repo, the same add-ons.

```
android/            Gecko (GeckoView) app — Kotlin
ios/                WebKit app — Swift, UIKit
shared/extensions/  bork's add-ons, used by both
tools/              add-on sync, blocklist builder
tests/              host tests for the mobile YouTube script
```

The two are not the same browser. **Android runs Gecko**, Firefox's
engine, so Manifest V2 extensions run there for real: the complete
**uBlock Origin**, not the Lite version, plus Dark Reader and bork's own
add-ons. **iOS allows no engine but WebKit**, and WebKit runs no
extensions inside an app — so the iOS app carries bork's own two add-ons on
a small extension runtime of its own, and blocks ads with WebKit's content
rule lists instead of uBlock. Everything else — workspaces, zen mode, the
start page, the look — is the same on both.

## What both do

- **Workspaces** — named sets of tabs, like the desktop's. Switch them from
  the chips at the top of the tab tray; long-press a tab to move it to
  another workspace, long-press a workspace to rename or delete it. The
  badge left of the address bar shows which one you are in (tap it for the
  tray, long-press it to jump to another).
- **Zen mode** — toolbars and status bar gone, only the page. A faint dot
  on the right edge brings them back.
- **Start page** — pinned sites as tiles. Pin a page from the menu;
  long-press a tile to rename, reorder or unpin it.
- **Private tabs**, **desktop site** per tab, **find in page**, downloads,
  bookmarks and history (on the phone and nowhere else), tabs that come
  back after a restart, the address bar at the bottom if you prefer it.
- **Private by default**: trackers blocked, each site's cookies kept to that
  site, private tabs that leave nothing behind, and nothing reported back
  about how you use it.

## Add-ons

| | Android | iOS |
|---|---|---|
| **uBlock Origin** | the AMO-signed build the desktop ships, full MV2 | — (WebKit cannot run it); EasyList + EasyPrivacy as a WebKit content blocker instead, with a per-site off switch, and YouTube ads stripped by bork itself (below) |
| **Dark Reader** | yes | — |
| **YoutubeDaddy** | yes, ported to m.youtube.com (below) | yes, the same files, on bork's extension runtime |
| **Cyberswine** | yes, plus a `contextMenus` shim | yes, the same files; its navigation blocking runs through the runtime's `webRequest` bridge, and *Block this site* is in the link long-press menu and the ⋯ menu |
| Anything from addons.mozilla.org | installs | — |

Each add-on with a toolbar button is in the menu and opens its popup in a
sheet; their settings pages open in tabs.

### YoutubeDaddy on a phone

The desktop add-on is built around a mouse: a floating bar follows the
pointer over video cards, and HIDE drives YouTube's own ⋮ menu. None of that
translates, so the mobile version keeps the same settings, store and matching
(`filters.js` and friends are the desktop files, copied unchanged) and
replaces only the part that touches YouTube's pages:

| Desktop | Mobile |
|---|---|
| Channel blocking on home, search, the watch sidebar, Shorts | **Kept**, on m.youtube.com's markup (and the desktop site, if you ask for it) |
| SHUT UP in a bar that follows the pointer | **A SHUT UP button under every video card**, and one beside Subscribe on the watch page |
| Subscription tabs + TAG | **Kept**: tabs at the top of the subscriptions feed, a TAG button under each video, a bottom sheet to pick the tab |
| Shorts switch on the subscriptions feed | **Kept** |
| Live chat filtering, SHUT UP per message, threaded view | **Kept** — m.youtube.com does not show live chat, so live watch pages get a **CHAT** button that opens YouTube's own chat page in a new tab, where the desktop chat code runs unchanged. Tap a message to show its SHUT UP |
| HIDE / DONE (presses YouTube's ⋮ → Hide) | **Stripped** — the mobile menu is a different bottom sheet; pressing it for you would be guesswork |
| Watch tab saves with YouTube's *Save to Watch later* | **Stripped** — the Watch tab still holds single videos, as a note kept here only |
| End-of-video suggestion wall | **Stripped** — the mobile player does not build one |
| "Who do I subscribe to" from the guide drawer | **Replaced** — the mobile site has no drawer; Subscribe buttons and the subscriptions feed's channel strip say it instead, and it is remembered |

### YouTube ads on iOS

uBlock does this on Android. On iOS a content rule list cannot: YouTube
serves video ads from youtube.com, inside the same data as the video. So
`ios/Bork/Extensions/youtube-ads.js` runs in YouTube's page ahead of
YouTube's own scripts and takes the ad sections out of that data as it
arrives — the inline first load (`ytInitialPlayerResponse`, `ytInitialData`)
and every `/youtubei/` API response — then hides what is left of promoted
cards, and fast-forwards any ad that starts anyway. A small rule list blocks
YouTube's ad and ad-tracking endpoints. It has its own switch under
Add-ons, on by default; `tests/youtube_ads_test.ts` covers the stripping.

### The iOS extension runtime

`ios/Bork/Extensions/` runs bork's add-ons from their own unchanged files:
content scripts in an isolated WebKit content world per add-on, background
pages in hidden web views, extension pages on `bork-ext://`, and
`browser.storage`, `runtime` messaging, `tabs.query/sendMessage/create`,
and blocking `webRequest` for top-level navigations — the API surface
bork's add-ons use, not all of WebExtensions. A message is accepted only
from the add-on's own content world or its own `bork-ext://` pages, so no
web page can reach an add-on's storage.

## Building

### Android

Needs the Android SDK with **platform 37.2** and JDK 17+ (Android Studio's
bundled one works).

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Releasing the Android app

Release builds are signed with the key in `android/release.keystore`, whose
passwords are in `android/keystore.properties`. **Both are gitignored, and
both must be backed up**: Android only installs an update signed with the same
key as the copy already on the phone, so losing them means nobody who
installed bork can update. Without them, `assembleRelease` falls back to the
debug key, which is fine for testing and never for the website.

```bash
cd android && ./gradlew :app:assembleRelease
```

The website takes the APK from there: bork-browser's
`tools/release/production-build.sh` stages it as `Bork-android-arm64.apk`, and
refuses a debug-signed one.

### iOS

Needs Xcode 26 (iOS 17+ deployment target; runs on iPhone and iPad, and on
Apple Silicon Macs as an iPad app).

```bash
cd ios
xcodegen generate        # only after editing project.yml; the project is committed
xcodebuild -project Bork.xcodeproj -target Bork -sdk iphonesimulator -arch arm64 \
  SYMROOT=build/Build/Products CODE_SIGNING_ALLOWED=NO build
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/Bork.app
```

Or open `ios/Bork.xcodeproj` in Xcode and run. A device build needs your
own signing team.

## Tests

```bash
deno test --allow-read tests/
```

The YoutubeDaddy mobile script against markup captured from the real
m.youtube.com. No browser, no network.

## Keeping the add-ons in step with the desktop

```bash
tools/sync-extensions.sh            # expects ../bork-browser next to this repo
```

It copies the files shared with the desktop into `shared/extensions/` and
never touches the mobile-only ones (listed at the top of the script). **Bump
the mobile `manifest.json` version afterwards** — Android only reloads a
built-in add-on when its version changes.

The iOS blocklist is rebuilt from the current EasyList and EasyPrivacy with:

```bash
tools/build-blocklist.py ios/Bork/Resources/blocklist.json
```

## License

[Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0/), as
bork. The iOS blocklist is derived from EasyList and EasyPrivacy by The
EasyList authors, dual-licensed GPLv3 / CC BY-SA 3.0 and used here under
CC BY-SA 3.0.

bork is based on Floorp, which is based on Mozilla Firefox. It is not
affiliated with, endorsed by, or connected to Mozilla, Ablaze or the Floorp
project. GeckoView is Mozilla's, MPL 2.0.
