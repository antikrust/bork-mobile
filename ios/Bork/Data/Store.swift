import Foundation
import SQLite3

struct Site {
    let url: String
    let title: String
    var time: Date = .distantPast
}

/**
 History and bookmarks, on the phone and nowhere else. The same two tables
 as the Android app. Bookmarks are the start page's tiles, in their order.
 */
final class Store {
    static let shared = Store()
    private var db: OpaquePointer?
    private let TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        sqlite3_open(dir.appendingPathComponent("bork.db").path, &db)
        exec("""
        CREATE TABLE IF NOT EXISTS history (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '',
            visits INTEGER NOT NULL DEFAULT 0, last_visit REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS history_last ON history(last_visit);
        CREATE TABLE IF NOT EXISTS bookmarks (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '',
            position INTEGER NOT NULL, created REAL NOT NULL);
        """)
    }

    private func exec(_ sql: String) { sqlite3_exec(db, sql, nil, nil, nil) }

    @discardableResult
    private func run(_ sql: String, _ args: [Any] = [], row: ((OpaquePointer) -> Void)? = nil) -> Int {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return 0 }
        defer { sqlite3_finalize(stmt) }
        for (i, arg) in args.enumerated() {
            let idx = Int32(i + 1)
            switch arg {
            case let s as String: sqlite3_bind_text(stmt, idx, s, -1, TRANSIENT)
            case let n as Int: sqlite3_bind_int64(stmt, idx, Int64(n))
            case let d as Double: sqlite3_bind_double(stmt, idx, d)
            default: sqlite3_bind_null(stmt, idx)
            }
        }
        while sqlite3_step(stmt) == SQLITE_ROW { row?(stmt) }
        return Int(sqlite3_changes(db))
    }

    private func text(_ s: OpaquePointer, _ i: Int32) -> String {
        sqlite3_column_text(s, i).map { String(cString: $0) } ?? ""
    }

    static func isRecordable(_ url: String) -> Bool { url.hasPrefix("http://") || url.hasPrefix("https://") }

    // MARK: History

    func recordVisit(_ url: String, title: String) {
        guard Self.isRecordable(url) else { return }
        let now = Date().timeIntervalSince1970
        if run("UPDATE history SET visits = visits + 1, last_visit = ?, title = CASE WHEN ? = '' THEN title ELSE ? END WHERE url = ?",
               [now, title, title, url]) == 0 {
            run("INSERT OR IGNORE INTO history (url, title, visits, last_visit) VALUES (?, ?, 1, ?)", [url, title, now])
        }
    }

    func setTitle(_ url: String, _ title: String) {
        guard Self.isRecordable(url), !title.isEmpty else { return }
        run("UPDATE history SET title = ? WHERE url = ?", [title, url])
    }

    func history(limit: Int = 500, query: String = "") -> [Site] {
        var out: [Site] = []
        let sql = query.isEmpty
            ? "SELECT url, title, last_visit FROM history ORDER BY last_visit DESC LIMIT \(limit)"
            : "SELECT url, title, last_visit FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY last_visit DESC LIMIT \(limit)"
        run(sql, query.isEmpty ? [] : ["%\(query)%", "%\(query)%"]) { s in
            out.append(Site(url: self.text(s, 0), title: self.text(s, 1), time: Date(timeIntervalSince1970: sqlite3_column_double(s, 2))))
        }
        return out
    }

    func deleteHistory(_ url: String) { run("DELETE FROM history WHERE url = ?", [url]) }
    func clearHistory() { run("DELETE FROM history") }

    func suggest(_ query: String, limit: Int = 8) -> [Site] {
        guard !query.isEmpty else { return [] }
        let like = "%\(query)%"
        var seen = Set<String>()
        var out: [Site] = []
        run("SELECT url, title FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY position LIMIT ?", [like, like, limit]) { s in
            let url = self.text(s, 0)
            if seen.insert(url).inserted { out.append(Site(url: url, title: self.text(s, 1))) }
        }
        run("SELECT url, title FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visits DESC, last_visit DESC LIMIT ?", [like, like, limit]) { s in
            let url = self.text(s, 0)
            if out.count < limit, seen.insert(url).inserted { out.append(Site(url: url, title: self.text(s, 1))) }
        }
        return out
    }

    // MARK: Bookmarks

    func bookmarks() -> [Site] {
        var out: [Site] = []
        run("SELECT url, title, created FROM bookmarks ORDER BY position, created") { s in
            out.append(Site(url: self.text(s, 0), title: self.text(s, 1), time: Date(timeIntervalSince1970: sqlite3_column_double(s, 2))))
        }
        return out
    }

    func isBookmarked(_ url: String) -> Bool {
        var found = false
        run("SELECT 1 FROM bookmarks WHERE url = ?", [url]) { _ in found = true }
        return found
    }

    func addBookmark(_ url: String, title: String) {
        var next = 0
        run("SELECT COALESCE(MAX(position), -1) + 1 FROM bookmarks") { s in next = Int(sqlite3_column_int64(s, 0)) }
        run("INSERT OR IGNORE INTO bookmarks (url, title, position, created) VALUES (?, ?, ?, ?)",
            [url, title, next, Date().timeIntervalSince1970])
    }

    func renameBookmark(_ url: String, _ title: String) { run("UPDATE bookmarks SET title = ? WHERE url = ?", [title, url]) }
    func removeBookmark(_ url: String) { run("DELETE FROM bookmarks WHERE url = ?", [url]) }

    func moveBookmark(_ url: String, by delta: Int) {
        var list = bookmarks()
        guard let i = list.firstIndex(where: { $0.url == url }), list.indices.contains(i + delta) else { return }
        list.insert(list.remove(at: i), at: i + delta)
        exec("BEGIN")
        for (n, site) in list.enumerated() { run("UPDATE bookmarks SET position = ? WHERE url = ?", [n, site.url]) }
        exec("COMMIT")
    }
}
