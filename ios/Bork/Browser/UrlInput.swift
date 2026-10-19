import Foundation

enum UrlInput {
    private static let known: Set<String> = ["http", "https", "about", "data", "file", "bork-ext", "blob", "javascript"]

    /// What the address bar should load for what was typed into it.
    static func url(for input: String) -> URL? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let engine = Settings.searchEngine
        if text.contains(" ") { return engine.url(for: text) }
        if let colon = text.firstIndex(of: ":") {
            let scheme = text[..<colon].lowercased()
            // "example.com:8080" is a host with a port, not a scheme.
            let portLike = text.range(of: #"^[^:/]+:\d"#, options: .regularExpression) != nil
            if known.contains(scheme), !portLike, let url = URL(string: text) { return url }
        }
        let hostish = text.range(of: #"^[^\s/?#]+\.[^\s/?#.]{2,}([/?#].*)?$"#, options: .regularExpression) != nil
        let ip = text.range(of: #"^\d{1,3}(\.\d{1,3}){3}(:\d+)?([/?#].*)?$"#, options: .regularExpression) != nil
        let localhost = text.range(of: #"^localhost(:\d+)?([/?#].*)?$"#, options: [.regularExpression, .caseInsensitive]) != nil
        if hostish || ip || localhost {
            return URL(string: (ip || localhost ? "http://" : "https://") + text) ?? engine.url(for: text)
        }
        return engine.url(for: text)
    }

    /// The URL as the address bar shows it while not being edited.
    static func display(_ url: String) -> String {
        guard url.hasPrefix("http://") || url.hasPrefix("https://") else { return url }
        var s = url.components(separatedBy: "://").dropFirst().joined(separator: "://")
        if s.hasPrefix("www.") { s.removeFirst(4) }
        if s.hasSuffix("/") { s.removeLast() }
        return s
    }

    static func host(_ url: String) -> String {
        var h = URL(string: url)?.host ?? ""
        if h.hasPrefix("www.") { h.removeFirst(4) }
        return h
    }
}
