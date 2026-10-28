# AGENTS.md

**bork mobile** is bork for phones: `android/` is a GeckoView browser shell
(Kotlin, plain Android views), `ios/` a WebKit one (Swift, UIKit), and
`shared/extensions/` the add-ons both carry. The desktop feature layer lives
in `../bork-browser`; nothing here patches it.

Commits go straight to `main`, pushed to `origin` (shithub.space, which also
pushes to the antikrust GitHub mirror) — the same arrangement as
bork-browser.

## Layout

```
android/app/src/main/java/online/borkbrowser/mobile/
  BorkApplication.kt   the one GeckoRuntime; privacy settings
  BrowserActivity.kt   tabs, workspaces, url bar, zen, menu, all session delegates
  TabTray.kt           workspace chips + tab grid
  Tabs.kt              Tab / Workspace model, tabs.json persistence
  Addons.kt            bundled add-ons, popups, add-on tabs
  Prompts.kt           alert/confirm/select/auth/file/date prompts
  Store.kt             history + bookmarks (SQLite)
ios/Bork/
  Browser/             BrowserViewController (+ WebDelegates, Menu), tab tray,
                       start page, suggestions, add-on popup sheet
  Extensions/          ExtensionRuntime.swift + extension-api.js: the mini
                       WebExtension runtime; ContentBlocker.swift
  Data/                Settings, Store (SQLite), Tabs
  Screens/             Library, Add-ons, Settings
  Resources/           blocklist.json (generated), icons
ios/project.yml        XcodeGen spec; Bork.xcodeproj is generated and committed
shared/extensions/     (the Android build mounts this as its assets)
  youtubedaddy/        shared desktop files + mobile-only overlay
  cyberswine/          desktop files + mobile manifest and shim
  *.xpi                AMO-signed uBlock Origin and Dark Reader (Android only)
tools/sync-extensions.sh   refresh shared add-on files from ../bork-browser
tools/build-blocklist.py   EasyList/EasyPrivacy -> WebKit content rules
tests/                 deno tests for the mobile YouTube script
```

## Commands

```bash
# Android
cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
# iOS (by target: see the Xcode landmine below)
cd ios && xcodegen generate
xcodebuild -project Bork.xcodeproj -target Bork -sdk iphonesimulator -arch arm64 \
  SYMROOT=build/Build/Products CODE_SIGNING_ALLOWED=NO build
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/Bork.app
# both
deno test --allow-read tests/
tools/sync-extensions.sh
```

## Landmines (each cost real time)

### Android

- **GeckoView 158 needs compileSdk 37.2**, which needs AGP 9.4+ and Gradle
  9.6+. AGP 9 compiles Kotlin itself; the root build pins Kotlin 2.4.20 only
  because GeckoView and androidx core are built with it — the bundled 2.2
  compiler cannot read their metadata.
- **Add-on popups and `tabs.query({active:true})` need
  `webExtensionController.setTabActive(session, true)`.** GeckoView has no idea
  which tab is in front unless the app says so; without it, clicking an
  add-on's button does nothing at all and logs nothing.
- **`ensureBuiltIn` only reloads a built-in add-on when its manifest version
  changes.** Edit YoutubeDaddy or Cyberswine and forget the bump, and the
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

### iOS

- **This Mac's Xcode 26.4 has no iOS 26.4 platform installed** (and the
  system disk has no room for it), only an iOS 18.5 simulator runtime. So
  `-destination` matching fails and actool (asset catalogs) refuses to run.
  Build by `-target` with `-sdk iphonesimulator`, and the icons are plain
  PNGs listed in Info.plist instead of an asset catalog.
- **WebKit's Swift overlay (libswiftWebKit) only ships in iOS 26.** Calling
  any Swift-only WebKit API — `callAsyncJavaScript` with a `Result`, for one —
  links it, and the app dies at launch on iOS 17/18 with "Library missing".
  Use the Objective-C entry points (`WKWebView.borkCall` wraps
  `__callAsyncJavaScript`); the overlay is weak-linked in project.yml.
- **Reply to a `WKScriptMessageHandlerWithReply` with `nil`, never
  `NSNull()`.** NSNull does not resolve the page's promise at all; the
  add-on's `await browser.storage.local.set()` just hangs.
- **A configuration WebKit passes to `createWebViewWith` already carries
  the opener's message handlers.** Adding them again throws; only fresh
  configurations go through `ExtensionRuntime.configure`.
- **Messages are trusted by world and origin, not by what they claim.**
  `ExtensionRuntime.handle` checks the sender's content world or bork-ext://
  origin against the add-on id. Keep it that way: the page world is shared
  with every website.
