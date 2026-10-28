import UIKit

/**
 What runs on iOS of bork's add-ons, and what stands in for the rest.

 WebKit runs no extensions in an app, so uBlock Origin and Dark Reader
 cannot come along; the content blocker is the stand-in for the first. bork's
 own two run on ExtensionRuntime, files unchanged.
 */
final class AddonsViewController: UITableViewController {
    private unowned let browser: BrowserViewController
    private var exts: [BorkExtension] { ExtensionRuntime.shared.extensions }

    init(browser: BrowserViewController) {
        self.browser = browser
        super.init(style: .insetGrouped)
        title = "Add-ons"
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: true)
        })
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "row")
    }

    /// Section 0: YouTube ads. 1: the content blocker. 2…: bork's add-ons.
    private let first = 2

    override func numberOfSections(in tv: UITableView) -> Int { first + exts.count }

    override func tableView(_ tv: UITableView, numberOfRowsInSection section: Int) -> Int {
        if section == 0 { return 1 }
        if section == 1 { return 1 + ContentBlocker.shared.allowedSites.count }
        let ext = exts[section - first]
        return 1 + (ext.optionsPage != nil ? 1 : 0) + (ext.popup != nil ? 1 : 0)
    }

    override func tableView(_ tv: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 0 ? "YouTube" : section == 1 ? "Ad and tracker blocking" : exts[section - first].name
    }

    override func tableView(_ tv: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 {
            return "Takes the ads out of YouTube's own data before the player sees it, hides sponsored cards, "
                + "and fast-forwards any ad that still starts — what uBlock Origin does for YouTube, built into "
                + "bork because iOS lets no extension run here. Applies to new tabs."
        }
        if section == 1 {
            return "EasyList and EasyPrivacy, run by WebKit itself. iOS allows no engine but WebKit and WebKit runs no "
                + "extensions in apps, so uBlock Origin and Dark Reader stay on Android and the desktop; this is what "
                + "blocks here instead. Sites listed above are let off — swipe to remove one."
        }
        let ext = exts[section - first]
        if ext.host == "youtubedaddy" {
            return "Blocks channels on m.youtube.com, adds SHUT UP and TAG buttons, subscription tabs and the Shorts switch. "
                + "On a live stream, CHAT opens the chat with your filters."
        }
        return (ext.manifest["description"] as? String)
    }

    override func tableView(_ tv: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: "row", for: indexPath)
        cell.accessoryView = nil
        cell.accessoryType = .none
        var c = UIListContentConfiguration.valueCell()
        if indexPath.section == 0 {
            c.text = "Block YouTube ads"
            c.image = UIImage(systemName: "play.slash")
            let toggle = UISwitch()
            toggle.isOn = YouTubeAds.shared.enabled
            toggle.addAction(UIAction { [weak self] _ in
                YouTubeAds.shared.enabled = toggle.isOn
                self?.reapplyBlocker()
            }, for: .valueChanged)
            cell.accessoryView = toggle
        } else if indexPath.section == 1 {
            if indexPath.row == 0 {
                c.text = "Content blocker"
                c.image = UIImage(systemName: "shield.lefthalf.filled")
                let toggle = UISwitch()
                toggle.isOn = ContentBlocker.shared.enabled
                toggle.addAction(UIAction { [weak self] _ in
                    ContentBlocker.shared.enabled = toggle.isOn
                    self?.reapplyBlocker()
                }, for: .valueChanged)
                cell.accessoryView = toggle
            } else {
                c.text = ContentBlocker.shared.allowedSites[indexPath.row - 1]
                c.secondaryText = "not blocked"
                c.image = UIImage(systemName: "shield.slash")
            }
        } else {
            let ext = exts[indexPath.section - first]
            switch indexPath.row {
            case 0:
                c.text = "On"
                c.image = BrowserViewController.icon(for: ext)
                let toggle = UISwitch()
                toggle.isOn = ext.enabled
                toggle.addAction(UIAction { [weak self] _ in
                    ext.enabled = toggle.isOn
                    let alert = UIAlertController(title: toggle.isOn ? "\(ext.name) is on" : "\(ext.name) is off",
                                                  message: "Open tabs keep what they had until they are reloaded; new tabs get the change.",
                                                  preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "OK", style: .default))
                    self?.present(alert, animated: true)
                }, for: .valueChanged)
                cell.accessoryView = toggle
            case 1 where ext.popup != nil:
                c.text = "Quick settings"
                c.image = UIImage(systemName: "slider.horizontal.3")
                cell.accessoryType = .disclosureIndicator
            default:
                c.text = "All settings"
                c.image = UIImage(systemName: "gearshape")
                cell.accessoryType = .disclosureIndicator
            }
        }
        cell.contentConfiguration = c
        return cell
    }

    override func tableView(_ tv: UITableView, didSelectRowAt indexPath: IndexPath) {
        tv.deselectRow(at: indexPath, animated: true)
        guard indexPath.section >= first, indexPath.row > 0 else { return }
        let ext = exts[indexPath.section - first]
        let popupRow = ext.popup != nil && indexPath.row == 1
        dismiss(animated: true) {
            if popupRow { self.browser.openPopup(ext) }
            else if let url = ext.optionsURL { self.browser.newTab(url) }
        }
    }

    override func tableView(_ tv: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard indexPath.section == 1, indexPath.row > 0 else { return nil }
        let site = ContentBlocker.shared.allowedSites[indexPath.row - 1]
        return UISwipeActionsConfiguration(actions: [UIContextualAction(style: .destructive, title: "Block again") { [weak self] _, _, done in
            ContentBlocker.shared.setAllowed(site, false) {
                self?.reapplyBlocker()
                self?.tableView.reloadData()
            }
            done(true)
        }])
    }

    private func reapplyBlocker() {
        for tab in browser.tabs { if let wv = tab.webView { ContentBlocker.shared.apply(to: wv.configuration.userContentController) } }
    }
}
