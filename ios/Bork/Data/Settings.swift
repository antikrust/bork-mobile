import Foundation

struct SearchEngine: Equatable {
    let id: String
    let name: String
    let template: String
    func url(for query: String) -> URL {
        let q = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&+=?#")))
            ?? query
        return URL(string: template.replacingOccurrences(of: "%s", with: q))!
    }
}

/// The same settings, names and defaults as the Android app.
enum Settings {
    private static let d = UserDefaults.standard

    static let engines = [
        SearchEngine(id: "ddg", name: "DuckDuckGo", template: "https://duckduckgo.com/?q=%s"),
        SearchEngine(id: "startpage", name: "Startpage", template: "https://www.startpage.com/do/search?q=%s"),
        SearchEngine(id: "brave", name: "Brave Search", template: "https://search.brave.com/search?q=%s"),
        SearchEngine(id: "google", name: "Google", template: "https://www.google.com/search?q=%s"),
        SearchEngine(id: "bing", name: "Bing", template: "https://www.bing.com/search?q=%s"),
        SearchEngine(id: "kagi", name: "Kagi", template: "https://kagi.com/search?q=%s"),
    ]

    static var searchEngineID: String {
        get { d.string(forKey: "search") ?? engines[0].id }
        set { d.set(newValue, forKey: "search") }
    }
    static var searchEngine: SearchEngine { engines.first { $0.id == searchEngineID } ?? engines[0] }

    static var toolbarAtBottom: Bool {
        get { d.bool(forKey: "toolbarAtBottom") }
        set { d.set(newValue, forKey: "toolbarAtBottom") }
    }
    static var desktopByDefault: Bool {
        get { d.bool(forKey: "desktopByDefault") }
        set { d.set(newValue, forKey: "desktopByDefault") }
    }
    static var restoreTabs: Bool {
        get { d.object(forKey: "restoreTabs") as? Bool ?? true }
        set { d.set(newValue, forKey: "restoreTabs") }
    }
    static var javascript: Bool {
        get { d.object(forKey: "javascript") as? Bool ?? true }
        set { d.set(newValue, forKey: "javascript") }
    }
    static var zen: Bool {
        get { d.bool(forKey: "zen") }
        set { d.set(newValue, forKey: "zen") }
    }
    static var inspectable: Bool {
        get { d.bool(forKey: "inspectable") }
        set { d.set(newValue, forKey: "inspectable") }
    }
    static var seeded: Bool {
        get { d.bool(forKey: "seeded") }
        set { d.set(newValue, forKey: "seeded") }
    }
}
