import Foundation
import WebKit

/**
 Ad and tracker blocking on iOS.

 uBlock Origin cannot run here — iOS allows only WebKit, and WebKit runs no
 extensions in an app's web views. What it has instead is the content rule
 list: rules WebKit compiles once and applies inside the engine. The rules
 are EasyList and EasyPrivacy, converted at build time by
 tools/build-blocklist.py (the same lists uBlock Origin starts with, minus
 what the format cannot express — see that script).

 Sites can be let off: they go into a second, tiny list of
 ignore-previous-rules entries, so changing it never recompiles the big one.
 */
final class ContentBlocker {
    static let shared = ContentBlocker()

    private let store = WKContentRuleListStore.default()!
    private(set) var main: WKContentRuleList?
    private(set) var allow: WKContentRuleList?
    private(set) var ruleCount = 0
    private var waiting: [() -> Void] = []
    private var ready = false

    var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "blocker.enabled") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "blocker.enabled") }
    }

    var allowedSites: [String] {
        get { UserDefaults.standard.stringArray(forKey: "blocker.allowed") ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: "blocker.allowed") }
    }

    /// The bundled list's identity: a new app build with new rules compiles anew.
    private var identifier: String {
        let url = Bundle.main.url(forResource: "blocklist", withExtension: "json")
        let size = (try? url?.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
        return "bork-blocklist-\(build)-\(size ?? 0)"
    }

    /// Compile (or fetch the cached compile of) the rules. Calls back on main.
    func prepare(_ done: @escaping () -> Void) {
        if ready { return done() }
        waiting.append(done)
        guard waiting.count == 1 else { return }
        let id = identifier
        store.lookUpContentRuleList(forIdentifier: id) { [weak self] list, _ in
            guard let self else { return }
            if let list {
                self.main = list
                self.finish()
                return
            }
            DispatchQueue.global(qos: .userInitiated).async {
                guard let url = Bundle.main.url(forResource: "blocklist", withExtension: "json"),
                      let json = try? String(contentsOf: url, encoding: .utf8) else {
                    DispatchQueue.main.async { self.finish() }
                    return
                }
                let count = json.components(separatedBy: "\"trigger\"").count - 1
                DispatchQueue.main.async {
                    self.ruleCount = count
                    self.removeStale(keeping: id)
                    self.store.compileContentRuleList(forIdentifier: id, encodedContentRuleList: json) { list, error in
                        if let error { NSLog("[bork] blocklist did not compile: \(error)") }
                        self.main = list
                        self.finish()
                    }
                }
            }
        }
    }

    private func finish() {
        // The YouTube list first, so the tabs re-applied below get it too.
        YouTubeAds.shared.prepare {
            self.compileAllowList {
                self.ready = true
                let callbacks = self.waiting
                self.waiting = []
                callbacks.forEach { $0() }
            }
        }
    }

    private func removeStale(keeping id: String) {
        store.getAvailableContentRuleListIdentifiers { ids in
            for old in ids ?? [] where old.hasPrefix("bork-blocklist-") && old != id {
                self.store.removeContentRuleList(forIdentifier: old) { _ in }
            }
        }
    }

    private func compileAllowList(_ done: @escaping () -> Void) {
        let sites = allowedSites
        guard !sites.isEmpty else {
            allow = nil
            return done()
        }
        let rules: [[String: Any]] = [[
            "trigger": ["url-filter": ".*", "if-domain": sites.map { "*\($0)" }],
            "action": ["type": "ignore-previous-rules"],
        ]]
        let json = String(data: try! JSONSerialization.data(withJSONObject: rules), encoding: .utf8)!
        store.compileContentRuleList(forIdentifier: "bork-allow", encodedContentRuleList: json) { list, _ in
            self.allow = list
            done()
        }
    }

    /// Put the lists on a web view's content controller (or take them off).
    func apply(to controller: WKUserContentController) {
        controller.removeAllContentRuleLists()
        if YouTubeAds.shared.enabled, let youtube = YouTubeAds.shared.rules { controller.add(youtube) }
        guard enabled, let main else { return }
        controller.add(main)
        if let allow { controller.add(allow) }
    }

    func isAllowed(_ host: String) -> Bool {
        let h = host.lowercased()
        return allowedSites.contains { h == $0 || h.hasSuffix("." + $0) }
    }

    func setAllowed(_ host: String, _ allowed: Bool, done: @escaping () -> Void) {
        let h = host.lowercased().replacingOccurrences(of: "www.", with: "")
        var sites = allowedSites.filter { $0 != h }
        if allowed { sites.append(h) }
        allowedSites = sites
        compileAllowList(done)
    }
}
