import UIKit

/// What the address bar offers while typing: go there, search for it, or
/// somewhere already visited.
final class SuggestionsView: UITableView, UITableViewDataSource, UITableViewDelegate {
    struct Row { let url: URL; let title: String; let detail: String; let symbol: String; var copy = false }
    var onPick: ((URL) -> Void)?
    private var rows: [Row] = []

    init() {
        super.init(frame: .zero, style: .plain)
        backgroundColor = Theme.bg
        separatorColor = Theme.line
        dataSource = self
        delegate = self
        keyboardDismissMode = .onDrag
        register(UITableViewCell.self, forCellReuseIdentifier: "row")
    }
    required init?(coder: NSCoder) { fatalError() }

    func update(query: String, current: Tab?) {
        var out: [Row] = []
        let q = query.trimmingCharacters(in: .whitespaces)
        let engine = Settings.searchEngine
        if !q.isEmpty {
            if let url = UrlInput.url(for: q), url != engine.url(for: q) {
                out.append(Row(url: url, title: UrlInput.display(url.absoluteString), detail: "Go to", symbol: "arrow.right"))
            }
            out.append(Row(url: engine.url(for: q), title: q, detail: "Search \(engine.name)", symbol: "magnifyingglass"))
            for site in Store.shared.suggest(q) {
                guard let url = URL(string: site.url) else { continue }
                out.append(Row(url: url, title: site.title.isEmpty ? UrlInput.display(site.url) : site.title,
                               detail: UrlInput.display(site.url), symbol: "clock"))
            }
        } else {
            if let tab = current, !tab.home, let url = URL(string: tab.url) {
                out.append(Row(url: url, title: "Copy this page's link", detail: UrlInput.display(tab.url), symbol: "doc.on.doc", copy: true))
            }
            for site in Store.shared.history(limit: 12) {
                guard let url = URL(string: site.url) else { continue }
                out.append(Row(url: url, title: site.title.isEmpty ? UrlInput.display(site.url) : site.title,
                               detail: UrlInput.display(site.url), symbol: "clock"))
            }
        }
        rows = out
        reloadData()
    }

    func tableView(_ tv: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }

    func tableView(_ tv: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: "row", for: indexPath)
        let row = rows[indexPath.row]
        var c = UIListContentConfiguration.subtitleCell()
        c.text = row.title
        c.secondaryText = row.detail
        c.textProperties.color = Theme.fg
        c.textProperties.numberOfLines = 1
        c.secondaryTextProperties.color = Theme.dim
        c.secondaryTextProperties.numberOfLines = 1
        c.image = UIImage(systemName: row.symbol)
        c.imageProperties.tintColor = Theme.dim
        cell.contentConfiguration = c
        cell.backgroundColor = Theme.bg
        return cell
    }

    func tableView(_ tv: UITableView, didSelectRowAt indexPath: IndexPath) {
        tv.deselectRow(at: indexPath, animated: false)
        let row = rows[indexPath.row]
        if row.copy {
            UIPasteboard.general.url = row.url
            window?.endEditing(true)
        } else {
            onPick?(row.url)
        }
    }
}
