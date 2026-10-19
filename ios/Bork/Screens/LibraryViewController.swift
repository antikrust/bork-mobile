import UIKit

/// Pinned sites (bookmarks) and history. Picking one opens it.
final class LibraryViewController: UITableViewController, UISearchResultsUpdating {
    private let open: (URL) -> Void
    private let segments = UISegmentedControl(items: ["Pinned", "History"])
    private var sites: [Site] = []
    private var query = ""

    init(open: @escaping (URL) -> Void) {
        self.open = open
        super.init(style: .insetGrouped)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        segments.selectedSegmentIndex = 0
        segments.addAction(UIAction { [weak self] _ in self?.reload() }, for: .valueChanged)
        navigationItem.titleView = segments
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: true)
        })
        let search = UISearchController()
        search.searchResultsUpdater = self
        search.obscuresBackgroundDuringPresentation = false
        navigationItem.searchController = search
        navigationItem.hidesSearchBarWhenScrolling = false
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "row")
        reload()
    }

    private var history: Bool { segments.selectedSegmentIndex == 1 }

    private func reload() {
        if history {
            sites = Store.shared.history(limit: 400, query: query)
            navigationItem.leftBarButtonItem = UIBarButtonItem(title: "Clear", primaryAction: UIAction { [weak self] _ in
                let alert = UIAlertController(title: "Clear all history?", message: nil, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
                alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { _ in
                    Store.shared.clearHistory()
                    self?.reload()
                })
                self?.present(alert, animated: true)
            })
        } else {
            sites = Store.shared.bookmarks().filter {
                query.isEmpty || $0.url.localizedCaseInsensitiveContains(query) || $0.title.localizedCaseInsensitiveContains(query)
            }
            navigationItem.leftBarButtonItem = nil
        }
        tableView.reloadData()
    }

    func updateSearchResults(for search: UISearchController) {
        query = search.searchBar.text ?? ""
        reload()
    }

    override func tableView(_ tv: UITableView, numberOfRowsInSection section: Int) -> Int { sites.count }

    override func tableView(_ tv: UITableView, titleForFooterInSection section: Int) -> String? {
        guard sites.isEmpty else { return nil }
        return history ? "Nothing here yet." : "Pin a page from the menu and it lands here and on the start page."
    }

    override func tableView(_ tv: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: "row", for: indexPath)
        let site = sites[indexPath.row]
        var c = UIListContentConfiguration.subtitleCell()
        c.text = site.title.isEmpty ? UrlInput.display(site.url) : site.title
        var detail = UrlInput.display(site.url)
        if history {
            detail += " · " + RelativeDateTimeFormatter().localizedString(for: site.time, relativeTo: Date())
        }
        c.secondaryText = detail
        c.secondaryTextProperties.color = .secondaryLabel
        c.image = UIImage(systemName: history ? "clock" : "star.fill")
        cell.contentConfiguration = c
        return cell
    }

    override func tableView(_ tv: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard let url = URL(string: sites[indexPath.row].url) else { return }
        dismiss(animated: true) { self.open(url) }
    }

    override func tableView(_ tv: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let site = sites[indexPath.row]
        let delete = UIContextualAction(style: .destructive, title: history ? "Delete" : "Unpin") { [weak self] _, _, done in
            if self?.history == true { Store.shared.deleteHistory(site.url) } else { Store.shared.removeBookmark(site.url) }
            self?.reload()
            done(true)
        }
        var actions = [delete]
        if history {
            let pin = UIContextualAction(style: .normal, title: "Pin") { [weak self] _, _, done in
                Store.shared.addBookmark(site.url, title: site.title)
                self?.reload()
                done(true)
            }
            pin.backgroundColor = Theme.accent
            actions.append(pin)
        }
        return UISwipeActionsConfiguration(actions: actions)
    }
}
