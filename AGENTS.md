# AGENTS.md

**bork mobile** is bork for Android: a GeckoView browser shell (Kotlin, plain
Android views) carrying the same add-ons as the desktop build. The desktop
feature layer lives in `../bork-browser`; nothing here patches it.

## Layout

```
app/src/main/java/online/borkbrowser/mobile/
  BorkApplication.kt   the one GeckoRuntime; privacy settings
  BrowserActivity.kt   tabs, workspaces, url bar, zen, menu, all session delegates
  TabTray.kt           workspace chips + tab grid
  Tabs.kt              Tab / Workspace model, tabs.json persistence
  Addons.kt            bundled add-ons, popups, add-on tabs
  Prompts.kt           alert/confirm/select/auth/file/date prompts
  Store.kt             history + bookmarks (SQLite)
app/src/main/assets/extensions/
  youtube-manager/     shared desktop files + mobile-only overlay
  cyberswine/          desktop files + mobile manifest and shim
  *.xpi                AMO-signed uBlock Origin and Dark Reader
tools/sync-extensions.sh   refresh shared add-on files from ../bork-browser
tests/                 deno tests for the mobile YouTube script
```

## Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
deno test --allow-read tests/
tools/sync-extensions.sh
```

## Landmines (each cost real time)

- **GeckoView 158 needs compileSdk 37.2**, which needs AGP 9.4+ and Gradle
  9.6+. AGP 9 compiles Kotlin itself; the root build pins Kotlin 2.4.20 only
  because GeckoView and androidx core are built with it — the bundled 2.2
  compiler cannot read their metadata.
- **Add-on popups and `tabs.query({active:true})` need
  `webExtensionController.setTabActive(session, true)`.** GeckoView has no idea
  which tab is in front unless the app says so; without it, clicking an
  add-on's button does nothing at all and logs nothing.
- **`ensureBuiltIn` only reloads a built-in add-on when its manifest version
  changes.** Edit YouTube Manager or Cyberswine and forget the bump, and the
  phone keeps running the old copy.
- **Sessions GeckoView opens for you are not open yet.** `onNewSession` and an
  add-on's `onNewTab` must return an *unopened* session; GeckoView opens it a
  moment later. Attaching it to the view before then fails, hence
  `attach()` waiting for `isOpen`. Popup sessions are the opposite: return
  one already open.
- **GeckoView has no `contextMenus`.** Cyberswine's background page built its
  menu at startup and died on the undefined namespace, taking request
  blocking with it; `src/mobile-shim.js` stands in for it.
- **No PromptDelegate means `<select>` never opens.** Everything in
  `Prompts.kt` is load-bearing.
- **Edge-to-edge is forced at targetSdk 36+.** Window insets (status bar,
  navigation bar, IME) are applied by hand in `applyInsets()`;
  `adjustResize` alone no longer moves anything.
- **m.youtube.com does not show live chat** on its watch page, but
  `www.youtube.com/live_chat?v=…` renders on a phone, so the desktop chat
  code runs there unchanged.
