import UIKit
import WebKit

final class SettingsViewController: UITableViewController {
    private unowned let browser: BrowserViewController

    private enum Row {
        case engine, bottom, desktop, restore, javascript, inspectable
        case clearHistory, clearData, about, notice
    }
    private let sections: [(String?, [Row], String?)] = [
        ("Browsing", [.engine, .bottom, .desktop, .restore, .javascript], "JavaScript applies to new tabs."),
        ("Privacy", [.clearHistory, .clearData],
         "No telemetry, no crash reports, no accounts — nothing is sent anywhere. Ads and trackers are blocked by the content blocker (Add-ons)."),
        ("Advanced", [.inspectable], "Lets Safari's Web Inspector on a Mac attach to pages, for add-on work."),
        ("About", [.about, .notice], nil),
    ]

    init(browser: BrowserViewController) {
        self.browser = browser
        super.init(style: .insetGrouped)
        title = "Settings"
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: true)
        })
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "row")
    }

    override func numberOfSections(in tv: UITableView) -> Int { sections.count }
    override func tableView(_ tv: UITableView, numberOfRowsInSection s: Int) -> Int { sections[s].1.count }
    override func tableView(_ tv: UITableView, titleForHeaderInSection s: Int) -> String? { sections[s].0 }
    override func tableView(_ tv: UITableView, titleForFooterInSection s: Int) -> String? { sections[s].2 }

    private func toggle(_ on: Bool, _ set: @escaping (Bool) -> Void) -> UISwitch {
        let t = UISwitch()
        t.isOn = on
        t.addAction(UIAction { _ in set(t.isOn) }, for: .valueChanged)
        return t
    }

    override func tableView(_ tv: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: "row", for: indexPath)
        cell.accessoryView = nil
        cell.accessoryType = .none
        var c = UIListContentConfiguration.valueCell()
        switch sections[indexPath.section].1[indexPath.row] {
        case .engine:
            c.text = "Search engine"
            c.secondaryText = Settings.searchEngine.name
            cell.accessoryType = .disclosureIndicator
        case .bottom:
            c.text = "Address bar at the bottom"
            cell.accessoryView = toggle(Settings.toolbarAtBottom) { [weak self] v in
                Settings.toolbarAtBottom = v
                self?.browser.placeToolbar()
            }
        case .desktop:
            c.text = "Desktop site for new tabs"
            cell.accessoryView = toggle(Settings.desktopByDefault) { Settings.desktopByDefault = $0 }
        case .restore:
            c.text = "Reopen tabs on start"
            cell.accessoryView = toggle(Settings.restoreTabs) { Settings.restoreTabs = $0 }
        case .javascript:
            c.text = "JavaScript"
            cell.accessoryView = toggle(Settings.javascript) { Settings.javascript = $0 }
        case .inspectable:
            c.text = "Web Inspector"
            cell.accessoryView = toggle(Settings.inspectable) { [weak self] v in
                Settings.inspectable = v
                self?.browser.tabs.forEach { $0.webView?.isInspectable = v }
            }
        case .clearHistory:
            c.text = "Clear history"
            c.textProperties.color = .systemRed
        case .clearData:
            c.text = "Clear cookies and site data"
            c.secondaryText = "signs you out"
            c.textProperties.color = .systemRed
        case .about:
            let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
            c.text = "bork mobile \(version)"
            c.secondaryText = "WebKit"
        case .notice:
            c = UIListContentConfiguration.cell()
            c.text = "bork is based on Floorp, which is based on Mozilla Firefox. It is not affiliated with, endorsed by, "
                + "or connected to Mozilla, Ablaze or the Floorp project. MPL 2.0."
            c.textProperties.font = .preferredFont(forTextStyle: .footnote)
            c.textProperties.color = .secondaryLabel
        }
        cell.contentConfiguration = c
        return cell
    }

    override func tableView(_ tv: UITableView, didSelectRowAt indexPath: IndexPath) {
        tv.deselectRow(at: indexPath, animated: true)
        switch sections[indexPath.section].1[indexPath.row] {
        case .engine:
            let sheet = UIAlertController(title: "Search engine", message: nil, preferredStyle: .actionSheet)
            for engine in Settings.engines {
                sheet.addAction(UIAlertAction(title: engine.name + (engine == Settings.searchEngine ? "  ✓" : ""), style: .default) { _ in
                    Settings.searchEngineID = engine.id
                    tv.reloadData()
                })
            }
            sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            sheet.popoverPresentationController?.sourceView = tv.cellForRow(at: indexPath)
            present(sheet, animated: true)
        case .clearHistory:
            confirm("Clear history?", "Every visited page is forgotten. Pinned sites stay.") {
                Store.shared.clearHistory()
            }
        case .clearData:
            confirm("Clear cookies and site data?", "Every site forgets you.") {
                WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
            }
        default:
            break
        }
    }

    private func confirm(_ title: String, _ message: String, _ action: @escaping () -> Void) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { _ in action() })
        present(alert, animated: true)
    }
}
