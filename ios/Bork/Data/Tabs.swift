import UIKit
import WebKit

/// A named set of tabs — the desktop's workspaces.
final class Workspace: Codable {
    let id: String
    var name: String
    var lastTabID: String?
    init(id: String = UUID().uuidString, name: String) {
        self.id = id
        self.name = name
    }
}

final class Tab {
    let id: String
    /// Stable small number for the add-on API's tabs.* (WebExtensions use ints).
    let number: Int
    var workspaceID: String
    let isPrivate: Bool
    var url = ""
    var title = ""
    /// Showing the start page over a web view that still has its history.
    var home = true
    var desktop = false
    var lastUsed = Date()
    var parentID: String?
    /// WKWebView.interactionState, kept so a restored tab gets its back list.
    var savedState: Data?
    var webView: WKWebView?
    var snapshot: UIImage?

    private static var counter = 0

    init(id: String = UUID().uuidString, workspaceID: String, isPrivate: Bool = false) {
        self.id = id
        self.workspaceID = workspaceID
        self.isPrivate = isPrivate
        Tab.counter += 1
        self.number = Tab.counter
    }

    var displayTitle: String {
        if home && (url.isEmpty || webView == nil) { return "Start" }
        if !title.isEmpty { return title }
        return url.isEmpty ? "Start" : url
    }

    struct Saved: Codable {
        let id: String
        let workspace: String
        let url: String
        let title: String
        let home: Bool
        let desktop: Bool
        let lastUsed: Date
        let parent: String?
        let state: Data?
    }

    var saved: Saved {
        Saved(id: id, workspace: workspaceID, url: url, title: title, home: home, desktop: desktop,
              lastUsed: lastUsed, parent: parentID,
              state: (webView?.interactionState as? Data) ?? savedState)
    }

    convenience init(saved s: Saved) {
        self.init(id: s.id, workspaceID: s.workspace)
        url = s.url
        title = s.title
        home = s.home
        desktop = s.desktop
        lastUsed = s.lastUsed
        parentID = s.parent
        savedState = s.state
    }
}

/// Tabs and workspaces on disk; private tabs never are.
enum TabStore {
    struct Snapshot: Codable {
        var version = 1
        var currentWorkspace: String?
        var workspaces: [Workspace]
        var tabs: [Tab.Saved]
    }

    private static var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tabs.json")
    }

    static func load() -> Snapshot? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: data)
    }

    static func save(workspaces: [Workspace], tabs: [Tab], current: String?) {
        let snap = Snapshot(currentWorkspace: current, workspaces: workspaces,
                            tabs: tabs.filter { !$0.isPrivate }.map(\.saved))
        if let data = try? JSONEncoder().encode(snap) {
            try? data.write(to: file, options: .atomic)
        }
    }

    static func clear() { try? FileManager.default.removeItem(at: file) }
}
