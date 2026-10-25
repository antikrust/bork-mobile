import UIKit
import WebKit

/// One of bork's own add-ons, read from its manifest in the app bundle.
final class BorkExtension {
    struct ContentScript {
        let patterns: [String]
        let excludes: [String]
        let js: [String]
        let css: [String]
        let atStart: Bool
        let allFrames: Bool
    }

    let id: String
    /// The folder name, and the host of its pages: bork-ext://<host>/…
    let host: String
    let dir: URL
    let manifest: [String: Any]
    let name: String
    let contentScripts: [ContentScript]
    let backgroundScripts: [String]
    let optionsPage: String?
    let popup: String?
    let icon: String?
    /// Content scripts run here, apart from the page's own scripts and from
    /// every other add-on — which is what a content script is in Firefox too.
    let world: WKContentWorld
    var wantsWebRequest = false

    init?(dir: URL) {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("manifest.json")),
              let manifest = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        let gecko = (manifest["browser_specific_settings"] as? [String: Any])?["gecko"] as? [String: Any]
        self.id = gecko?["id"] as? String ?? dir.lastPathComponent
        self.host = dir.lastPathComponent
        self.dir = dir
        self.manifest = manifest
        self.name = manifest["name"] as? String ?? dir.lastPathComponent
        self.contentScripts = (manifest["content_scripts"] as? [[String: Any]] ?? []).map { entry in
            ContentScript(
                patterns: entry["matches"] as? [String] ?? [],
                excludes: entry["exclude_matches"] as? [String] ?? [],
                js: entry["js"] as? [String] ?? [],
                css: entry["css"] as? [String] ?? [],
                atStart: (entry["run_at"] as? String) == "document_start",
                allFrames: entry["all_frames"] as? Bool ?? false
            )
        }
        self.backgroundScripts = (manifest["background"] as? [String: Any])?["scripts"] as? [String] ?? []
        self.optionsPage = (manifest["options_ui"] as? [String: Any])?["page"] as? String
            ?? manifest["options_page"] as? String
        let action = (manifest["browser_action"] ?? manifest["action"]) as? [String: Any]
        self.popup = action?["default_popup"] as? String
        let icons = manifest["icons"] as? [String: String]
        self.icon = icons?["96"] ?? icons?["48"] ?? icons?.values.first
        self.world = WKContentWorld.world(name: "bork-ext-\(dir.lastPathComponent)")
    }

    var baseURL: URL { URL(string: "bork-ext://\(host)/")! }
    func url(_ path: String) -> URL { URL(string: path, relativeTo: baseURL)!.absoluteURL }
    var optionsURL: URL? { optionsPage.map(url) }
    var popupURL: URL? { popup.map(url) }

    var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "ext.enabled.\(id)") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "ext.enabled.\(id)") }
    }
}

/// What the browser has to supply for add-ons: which tab is which, and how
/// to open things.
protocol ExtensionHost: AnyObject {
    func extensionTabID(for webView: WKWebView) -> Int?
    func extensionActiveTab() -> (id: Int, url: String, title: String)?
    /// `loaded` is false for a tab restored from last time and not opened
    /// since: it has no page yet, so nothing in it can answer.
    func extensionTabs() -> [(id: Int, url: String, title: String, active: Bool, loaded: Bool)]
    func extensionWebView(forTab id: Int) -> WKWebView?
    @discardableResult func extensionOpen(_ url: URL, active: Bool) -> Int?
    func extensionClose(_ webView: WKWebView)
}

/**
 A small WebExtension runtime on top of WebKit, for bork's own add-ons.

 The add-ons' files run unchanged. extension-api.js gives them `browser.*`;
 this side keeps their storage, carries their messages, serves their pages
 on bork-ext://, runs their background pages in hidden web views and asks
 those pages before a tab navigates (the one webRequest use there is:
 Cyberswine's blocking).

 Two trust boundaries are enforced here, not in the script: a message is
 only accepted for the add-on whose content world it came from, or whose
 bork-ext:// page sent it. A web page cannot reach any of this — it has
 neither the world nor the origin.
 */
final class ExtensionRuntime: NSObject {
    static let shared = ExtensionRuntime()

    /// bork's own add-ons that run on iOS. uBlock Origin and Dark Reader do
    /// not: see ContentBlocker for what stands in for the first.
    static let bundled = ["youtube-manager", "cyberswine"]

    weak var host: ExtensionHost?
    private(set) var extensions: [BorkExtension] = []
    private var storage: [String: [String: Any]] = [:]
    private var backgrounds: [String: WKWebView] = [:]
    private var backgroundReady: Set<String> = []

    /// One end of a port: the page it lives in and the world its script runs in.
    private struct PortEnd {
        weak var view: WKWebView?
        let world: WKContentWorld
    }
    /// Open ports by id, both ends, and the add-on they belong to.
    private var ports: [String: (ext: String, opener: PortEnd, target: PortEnd)] = [:]
    private let views = NSHashTable<WKWebView>.weakObjects()
    private var apiSource = ""
    private lazy var bridge = Bridge(runtime: self)
    let schemeHandler = ExtensionSchemeHandler()

    private override init() {
        super.init()
        let root = Bundle.main.resourceURL!.appendingPathComponent("extensions")
        extensions = Self.bundled.compactMap { BorkExtension(dir: root.appendingPathComponent($0)) }
        if let url = Bundle.main.url(forResource: "extension-api", withExtension: "js"),
           let source = try? String(contentsOf: url, encoding: .utf8) {
            apiSource = source
        }
        schemeHandler.runtime = self
        for ext in extensions { storage[ext.id] = loadStorage(ext) }
    }

    func ext(host: String) -> BorkExtension? { extensions.first { $0.host == host } }
    func ext(id: String) -> BorkExtension? { extensions.first { $0.id == id } }

    // MARK: Setting up a web view

    /// Everything a web view needs for add-ons to work in it: the bork-ext://
    /// scheme, the bridge in every world, the API, and the content scripts.
    func configure(_ config: WKWebViewConfiguration) {
        config.setURLSchemeHandler(schemeHandler, forURLScheme: "bork-ext")
        let ucc = config.userContentController
        ucc.addScriptMessageHandler(bridge, contentWorld: .page, name: "borkExt")
        ucc.addUserScript(pageShim())
        for ext in extensions where ext.enabled {
            ucc.addScriptMessageHandler(bridge, contentWorld: ext.world, name: "borkExt")
            ucc.addUserScript(WKUserScript(
                source: prelude(ext, kind: "content") + apiSource,
                injectionTime: .atDocumentStart, forMainFrameOnly: false, in: ext.world))
            for script in ext.contentScripts {
                ucc.addUserScript(WKUserScript(
                    source: contentSource(ext, script),
                    injectionTime: script.atStart ? .atDocumentStart : .atDocumentEnd,
                    forMainFrameOnly: !script.allFrames, in: ext.world))
            }
        }
    }

    /// A web view to keep informed of storage changes.
    func track(_ webView: WKWebView) { views.add(webView) }

    private func prelude(_ ext: BorkExtension, kind: String) -> String {
        let meta: [String: Any] = ["id": ext.id, "host": ext.host, "kind": kind, "manifest": ext.manifest]
        let json = (try? JSONSerialization.data(withJSONObject: meta)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return "globalThis.__BORK_EXT__ = \(json);\n"
    }

    /// The API on bork-ext:// pages (options, popups, background), in the
    /// page's own world because that is where those pages' scripts run.
    private func pageShim() -> WKUserScript {
        var map: [String: Any] = [:]
        for ext in extensions where ext.enabled {
            map[ext.host] = ["id": ext.id, "host": ext.host, "manifest": ext.manifest]
        }
        let json = (try? JSONSerialization.data(withJSONObject: map)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let source = """
        if (location.protocol === "bork-ext:") {
          const m = (\(json))[location.host];
          if (m) {
            m.kind = location.pathname === "/_background.html" ? "background" : "page";
            globalThis.__BORK_EXT__ = m;
            \(apiSource)
          }
        }
        """
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: false, in: .page)
    }

    private func contentSource(_ ext: BorkExtension, _ script: BorkExtension.ContentScript) -> String {
        let include = script.patterns.map(MatchPattern.regex)
        let exclude = script.excludes.map(MatchPattern.regex)
        var body = ""
        for css in script.css {
            guard let text = read(ext, css) else { continue }
            let literal = Self.jsString(text)
            // A constructed stylesheet, not a <style> element: a page's
            // Content-Security-Policy refuses <style> it did not write
            // (DuckDuckGo's does), and Firefox exempts add-on CSS from that
            // where WebKit gives us no such exemption. <style> is only the
            // fallback for an engine without adoptedStyleSheets.
            body += """
            (function () {
              const css = \(literal);
              try {
                const sheet = new CSSStyleSheet();
                sheet.replaceSync(css);
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
                return;
              } catch (_) {}
              const s = document.createElement("style");
              s.dataset.borkExt = \(Self.jsString(ext.host));
              s.textContent = css;
              (document.head || document.documentElement).appendChild(s);
            })();

            """
        }
        for js in script.js {
            guard let text = read(ext, js) else { continue }
            body += """
            try { (function () {
            \(text)
            }).call(globalThis); } catch (e) { console.error("[\(ext.host)] \(js)", e); }

            """
        }
        return """
        (function () {
          const href = location.href;
          const inc = [\(include.map(Self.jsString).joined(separator: ","))].map((r) => new RegExp(r));
          const exc = [\(exclude.map(Self.jsString).joined(separator: ","))].map((r) => new RegExp(r));
          if (!inc.some((r) => r.test(href)) || exc.some((r) => r.test(href))) return;
          \(body)
        })();
        """
    }

    func read(_ ext: BorkExtension, _ path: String) -> String? {
        try? String(contentsOf: ext.dir.appendingPathComponent(path), encoding: .utf8)
    }

    static func jsString(_ s: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [s], options: [.fragmentsAllowed])
        let array = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(array.dropFirst().dropLast())
    }

    // MARK: Background pages

    /// One hidden web view per add-on with a background page, kept alive in
    /// the window so WebKit does not suspend its timers.
    func startBackgrounds(in container: UIView) {
        for ext in extensions where ext.enabled && !ext.backgroundScripts.isEmpty && backgrounds[ext.id] == nil {
            let config = WKWebViewConfiguration()
            configureForExtensionPage(config)
            let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: config)
            view.alpha = 0.01
            view.isUserInteractionEnabled = false
            view.navigationDelegate = self
            container.insertSubview(view, at: 0)
            backgrounds[ext.id] = view
            track(view)
            view.load(URLRequest(url: ext.url("_background.html")))
        }
    }

    /// Extension pages get the API and the scheme, and no content scripts.
    func configureForExtensionPage(_ config: WKWebViewConfiguration) {
        config.setURLSchemeHandler(schemeHandler, forURLScheme: "bork-ext")
        config.userContentController.addScriptMessageHandler(bridge, contentWorld: .page, name: "borkExt")
        config.userContentController.addUserScript(pageShim())
    }

    func backgroundHTML(_ ext: BorkExtension) -> String {
        let tags = ext.backgroundScripts.map { "<script src=\"/\($0)\"></script>" }.joined(separator: "\n")
        return "<!doctype html><meta charset=\"utf-8\"><title>\(ext.name)</title>\n\(tags)\n"
    }

    /// The app speaking to an add-on's background page, as one of its own
    /// pages would with runtime.sendMessage. Used where the desktop has a
    /// right-click menu entry and iOS has a native one instead.
    func messageBackground(host: String, _ message: [String: Any], done: ((Any?) -> Void)? = nil) {
        guard let ext = ext(host: host), ext.enabled, let background = backgrounds[ext.id] else {
            done?(nil)
            return
        }
        let sender: [String: Any] = ["id": ext.id, "url": "bork-ext://\(ext.host)/"]
        background.borkCall("return await __borkExt.dispatchMessage(m, s)",
                            ["m": message, "s": sender], world: .page) { done?($0) }
    }

    // MARK: Navigation (webRequest)

    /// Ask every add-on that listens to webRequest about a top-level load.
    /// Calls back with a URL to go to instead, or nil to carry on.
    func beforeNavigation(to url: URL, tabID: Int, completion: @escaping (URL?) -> Void) {
        let askers = extensions.filter { $0.enabled && $0.wantsWebRequest && backgroundReady.contains($0.id) }
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? ""), !askers.isEmpty else {
            return completion(nil)
        }
        let details: [String: Any] = ["url": url.absoluteString, "type": "main_frame", "method": "GET", "tabId": tabID, "frameId": 0]
        func ask(_ i: Int) {
            guard i < askers.count, let view = backgrounds[askers[i].id] else { return completion(nil) }
            view.borkCall("return __borkExt.beforeRequest(d)", ["d": details], world: .page) { value in
                if let dict = value as? [String: Any] {
                    if let redirect = dict["redirectUrl"] as? String, let target = URL(string: redirect) {
                        return completion(target)
                    }
                    if dict["cancel"] as? Bool == true {
                        return completion(URL(string: "about:blank"))
                    }
                }
                ask(i + 1)
            }
        }
        ask(0)
    }

    // MARK: Storage

    private func storageFile(_ ext: BorkExtension) -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("extensions", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(ext.host).json")
    }

    private func loadStorage(_ ext: BorkExtension) -> [String: Any] {
        guard let data = try? Data(contentsOf: storageFile(ext)),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return dict
    }

    private func saveStorage(_ ext: BorkExtension) {
        let dict = storage[ext.id] ?? [:]
        if let data = try? JSONSerialization.data(withJSONObject: dict) {
            try? data.write(to: storageFile(ext), options: .atomic)
        }
    }

    fileprivate func storageGet(_ ext: BorkExtension, _ keys: Any?) -> [String: Any] {
        let all = storage[ext.id] ?? [:]
        switch keys {
        case let key as String: return all[key].map { [key: $0] } ?? [:]
        case let list as [String]: return all.filter { list.contains($0.key) }
        case let defaults as [String: Any]:
            var out = defaults
            for (k, _) in defaults { if let v = all[k] { out[k] = v } }
            return out
        default: return all
        }
    }

    fileprivate func storageSet(_ ext: BorkExtension, _ items: [String: Any]) {
        var all = storage[ext.id] ?? [:]
        var changes: [String: Any] = [:]
        for (k, v) in items {
            var change: [String: Any] = ["newValue": v]
            if let old = all[k] { change["oldValue"] = old }
            changes[k] = change
            all[k] = v
        }
        storage[ext.id] = all
        saveStorage(ext)
        broadcast(ext, changes)
    }

    fileprivate func storageRemove(_ ext: BorkExtension, _ keys: [String]) {
        var all = storage[ext.id] ?? [:]
        var changes: [String: Any] = [:]
        for k in keys {
            if let old = all.removeValue(forKey: k) { changes[k] = ["oldValue": old] }
        }
        storage[ext.id] = all
        saveStorage(ext)
        if !changes.isEmpty { broadcast(ext, changes) }
    }

    /// storage.onChanged, everywhere the add-on is running: its content
    /// world in every tab, and its own pages.
    private func broadcast(_ ext: BorkExtension, _ changes: [String: Any]) {
        for view in views.allObjects {
            view.borkCall("if (globalThis.__borkExt) __borkExt.fireStorage(c)", ["c": changes], world: ext.world)
            if view.url?.scheme == "bork-ext", view.url?.host == ext.host {
                view.borkCall("if (globalThis.__borkExt) __borkExt.fireStorage(c)", ["c": changes], world: .page)
            }
        }
    }

    // MARK: Messages from the add-ons

    fileprivate func handle(_ message: WKScriptMessage, reply: @escaping (Any?, String?) -> Void) {
        // Replies: nil resolves the add-on's promise with undefined. NSNull
        // does not resolve it at all — the promise just hangs.
        guard let body = message.body as? [String: Any],
              let extID = body["ext"] as? String,
              let op = body["op"] as? String,
              let ext = ext(id: extID), ext.enabled
        else { return reply(nil, "bad message") }

        // The add-on must be who it says: its own world, or its own pages.
        let origin = message.frameInfo.securityOrigin
        let fromOwnPage = message.world.name == nil && origin.protocol == "bork-ext" && origin.host == ext.host
        let fromOwnWorld = message.world.name == ext.world.name
        guard fromOwnPage || fromOwnWorld else { return reply(nil, "not allowed") }
        let args = body["args"]
        let webView = message.webView

        switch op {
        case "storage.get":
            reply(storageGet(ext, args is NSNull ? nil : args), nil)
        case "storage.set":
            storageSet(ext, args as? [String: Any] ?? [:])
            reply(nil, nil)
        case "storage.remove":
            let keys = (args as? [String]) ?? (args as? String).map { [$0] } ?? []
            storageRemove(ext, keys)
            reply(nil, nil)
        case "storage.clear":
            storageRemove(ext, Array((storage[ext.id] ?? [:]).keys))
            reply(nil, nil)

        case "runtime.sendMessage":
            let tabID = webView.flatMap { host?.extensionTabID(for: $0) }
            var sender: [String: Any] = ["id": ext.id, "url": message.frameInfo.request.url?.absoluteString ?? ""]
            if let tabID, let view = webView { sender["tab"] = ["id": tabID, "url": view.url?.absoluteString ?? ""] }
            guard let background = backgrounds[ext.id], background !== webView else { return reply(nil, nil) }
            background.borkCall("return await __borkExt.dispatchMessage(m, s)",
                                ["m": args ?? NSNull(), "s": sender], world: .page) { value in
                reply(value is NSNull ? nil : value, nil)
            }
        case "runtime.openOptionsPage":
            if let url = ext.optionsURL { host?.extensionOpen(url, active: true) }
            reply(nil, nil)

        case "tabs.query":
            let info = args as? [String: Any] ?? [:]
            let patterns = (info["url"] as? [String]) ?? (info["url"] as? String).map { [$0] }
            if let patterns {
                // By address: every tab that matches, in front or not.
                let found = (host?.extensionTabs() ?? []).enumerated().filter { _, tab in
                    patterns.contains { tab.url.range(of: MatchPattern.regex($0), options: .regularExpression) != nil }
                        && (info["active"] as? Bool).map { $0 == tab.active } ?? true
                }
                reply(found.map { i, tab in
                    ["id": tab.id, "url": tab.url, "title": tab.title, "active": tab.active,
                     "index": i, "windowId": 1, "discarded": !tab.loaded] as [String: Any]
                }, nil)
            } else if let tab = host?.extensionActiveTab() {
                reply([["id": tab.id, "url": tab.url, "title": tab.title, "active": true, "index": 0, "windowId": 1]], nil)
            } else {
                reply([Any](), nil)
            }
        case "tabs.sendMessage":
            let dict = args as? [String: Any] ?? [:]
            guard let id = dict["tabId"] as? Int, let view = host?.extensionWebView(forTab: id) else { return reply(nil, nil) }
            view.borkCall("return globalThis.__borkExt ? await __borkExt.dispatchMessage(m, {id: e}) : null",
                          ["m": dict["message"] ?? NSNull(), "e": ext.id], world: ext.world) { value in
                reply(value is NSNull ? nil : value, nil)
            }
        case "tabs.create":
            let dict = args as? [String: Any] ?? [:]
            guard let raw = dict["url"] as? String, let url = URL(string: raw, relativeTo: ext.baseURL)?.absoluteURL
            else { return reply(nil, nil) }
            let id = host?.extensionOpen(url, active: dict["active"] as? Bool ?? true)
            reply(["id": id ?? -1, "url": url.absoluteString], nil)

        case "port.open":
            let dict = args as? [String: Any] ?? [:]
            guard let id = dict["id"] as? String, let tabID = dict["tabId"] as? Int,
                  let target = host?.extensionWebView(forTab: tabID), let opener = webView
            else { return reply(false, nil) }
            ports[id] = (ext.id, PortEnd(view: opener, world: message.world), PortEnd(view: target, world: ext.world))
            let sender: [String: Any] = ["id": ext.id, "url": message.frameInfo.request.url?.absoluteString ?? ""]
            target.borkCall("return globalThis.__borkExt ? __borkExt.openPort(i, n, s) : false",
                            ["i": id, "n": dict["name"] as? String ?? "", "s": sender], world: ext.world) { [weak self] value in
                let ok = value as? Bool ?? false
                if !ok { self?.ports[id] = nil }
                reply(ok, nil)
            }
        case "port.post":
            let dict = args as? [String: Any] ?? [:]
            guard let id = dict["id"] as? String, let port = ports[id], port.ext == ext.id else { return reply(nil, nil) }
            let fromOpener = port.opener.view === webView && port.opener.world.name == message.world.name
            let (to, from) = fromOpener ? (port.target, port.opener) : (port.opener, port.target)
            reply(nil, nil)
            guard let view = to.view else { return closePort(id, notify: from) }
            view.borkCall("return globalThis.__borkExt ? __borkExt.portMessage(i, m) : false",
                          ["i": id, "m": dict["message"] ?? NSNull()], world: to.world) { [weak self] value in
                // Its page moved on and took the script with it.
                if (value as? Bool) != true { self?.closePort(id, notify: from) }
            }
        case "port.close":
            let dict = args as? [String: Any] ?? [:]
            guard let id = dict["id"] as? String, let port = ports[id], port.ext == ext.id else { return reply(nil, nil) }
            let fromOpener = port.opener.view === webView && port.opener.world.name == message.world.name
            closePort(id, notify: fromOpener ? port.target : port.opener)
            reply(nil, nil)

        case "webRequest.listen":
            ext.wantsWebRequest = true
            reply(nil, nil)
        case "window.close":
            if let view = webView { host?.extensionClose(view) }
            reply(nil, nil)
        default:
            reply(nil, "unsupported: \(op)")
        }
    }

    // MARK: Ports

    private func closePort(_ id: String, notify end: PortEnd) {
        guard ports.removeValue(forKey: id) != nil else { return }
        end.view?.borkCall("globalThis.__borkExt && __borkExt.portClosed(i)", ["i": id], world: end.world)
    }

    /// A page navigated or its tab closed: its scripts are gone, and so is
    /// every port they held. The other ends hear onDisconnect.
    func pageGone(_ webView: WKWebView) {
        for (id, port) in ports {
            if port.opener.view === webView { closePort(id, notify: port.target) }
            else if port.target.view === webView { closePort(id, notify: port.opener) }
        }
    }

    private final class Bridge: NSObject, WKScriptMessageHandlerWithReply {
        weak var runtime: ExtensionRuntime?
        init(runtime: ExtensionRuntime) { self.runtime = runtime }
        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage,
            replyHandler: @escaping @MainActor @Sendable (Any?, String?) -> Void
        ) {
            guard let runtime else { return replyHandler(nil, "gone") }
            runtime.handle(message) { value, error in replyHandler(value, error) }
        }
    }
}

extension ExtensionRuntime: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if let id = backgrounds.first(where: { $0.value === webView })?.key {
            backgroundReady.insert(id)
        }
    }
}

/// Firefox match patterns ("https://*.youtube.com/*", "<all_urls>") as regexes.
enum MatchPattern {
    static func regex(_ pattern: String) -> String {
        if pattern == "<all_urls>" { return "^(https?|wss?|file|ftp)://" }
        guard let schemeEnd = pattern.range(of: "://") else { return "^$" }
        let scheme = String(pattern[..<schemeEnd.lowerBound])
        let rest = pattern[schemeEnd.upperBound...]
        let slash = rest.firstIndex(of: "/") ?? rest.endIndex
        let host = String(rest[..<slash])
        let path = String(rest[slash...])
        let schemeRe = scheme == "*" ? "https?" : NSRegularExpression.escapedPattern(for: scheme)
        var hostRe: String
        if host == "*" {
            hostRe = "[^/]*"
        } else if host.hasPrefix("*.") {
            hostRe = "([^/]*\\.)?" + NSRegularExpression.escapedPattern(for: String(host.dropFirst(2)))
        } else {
            hostRe = NSRegularExpression.escapedPattern(for: host)
        }
        hostRe += "(:\\d+)?"
        let pathRe = path.isEmpty ? "/.*" : path.split(separator: "*", omittingEmptySubsequences: false)
            .map { NSRegularExpression.escapedPattern(for: String($0)) }
            .joined(separator: ".*")
        return "^\(schemeRe)://\(hostRe)\(pathRe)$"
    }
}

/// Serves an add-on's own files on bork-ext://<host>/<path>.
final class ExtensionSchemeHandler: NSObject, WKURLSchemeHandler {
    weak var runtime: ExtensionRuntime?

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url, let host = url.host, let ext = runtime?.ext(host: host) else {
            return task.didFailWithError(URLError(.fileDoesNotExist))
        }
        let path = url.path.isEmpty || url.path == "/" ? "/index.html" : url.path
        let data: Data?
        if path == "/_background.html" {
            data = runtime?.backgroundHTML(ext).data(using: .utf8)
        } else {
            let file = ext.dir.appendingPathComponent(String(path.dropFirst())).standardizedFileURL
            // Never outside the add-on's own folder.
            guard file.path.hasPrefix(ext.dir.standardizedFileURL.path) else {
                return task.didFailWithError(URLError(.noPermissionsToReadFile))
            }
            data = try? Data(contentsOf: file)
        }
        guard let body = data else { return task.didFailWithError(URLError(.fileDoesNotExist)) }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [
            "Content-Type": Self.mime(for: path),
            "Content-Length": "\(body.count)",
            "Cache-Control": "no-store",
        ])!
        task.didReceive(response)
        task.didReceive(body)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

    static func mime(for path: String) -> String {
        switch (path as NSString).pathExtension.lowercased() {
        case "html", "htm": return "text/html; charset=utf-8"
        case "js", "mjs": return "text/javascript; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "json": return "application/json"
        case "png": return "image/png"
        case "svg": return "image/svg+xml"
        case "jpg", "jpeg": return "image/jpeg"
        case "woff2": return "font/woff2"
        default: return "application/octet-stream"
        }
    }
}

extension WKWebView {
    /// callAsyncJavaScript through its Objective-C entry point. The Swift
    /// spelling (with a Result) lives in libswiftWebKit, which only iOS 26
    /// ships — calling it would stop the app launching on iOS 17 and 18.
    func borkCall(_ js: String, _ args: [String: Any], world: WKContentWorld, done: ((Any?) -> Void)? = nil) {
        __callAsyncJavaScript(js, arguments: args, inFrame: nil, in: world) { value, error in
            if let error { NSLog("[bork-ext] call failed in %@: %@", world.name ?? "page", "\(error)") }
            done?(error == nil ? value : nil)
        }
    }
}
