import UIKit
import WebKit

/// The ⋯ menu: built fresh each time it opens, so every switch shows its state.
extension BrowserViewController {
    func mainMenuItems() -> [UIMenuElement] {
        let tab = current
        let onPage = tab.map { !$0.home && !$0.url.isEmpty } ?? false
        let pinned = onPage && Store.shared.isBookmarked(tab!.url)

        let quick = UIMenu(options: .displayInline, preferredElementSize: .small, children: [
            UIAction(title: pinned ? "Unpin" : "Pin", image: UIImage(systemName: pinned ? "star.fill" : "star"),
                     attributes: onPage ? [] : .disabled) { [weak self] _ in self?.togglePin() },
            UIAction(title: "Share", image: UIImage(systemName: "square.and.arrow.up"),
                     attributes: onPage ? [] : .disabled) { [weak self] _ in
                if let s = tab?.url, let url = URL(string: s) { self?.share(url) }
            },
            UIAction(title: "Find", image: UIImage(systemName: "doc.text.magnifyingglass"),
                     attributes: onPage ? [] : .disabled) { _ in
                tab?.webView?.findInteraction?.presentFindNavigator(showingReplace: false)
            },
        ])

        var tabsSection: [UIMenuElement] = [
            UIAction(title: "New Tab", image: UIImage(systemName: "plus")) { [weak self] _ in
                self?.newTab()
                self?.focusAddressBar()
            },
            UIAction(title: "New Private Tab", image: UIImage(systemName: "eyeglasses")) { [weak self] _ in
                self?.newTab(isPrivate: true)
                self?.focusAddressBar()
            },
            UIAction(title: "Zen Mode", subtitle: "Only the page. Tap the dot to come back.",
                     image: UIImage(systemName: "circle.circle"), state: Settings.zen ? .on : .off) { [weak self] _ in
                self?.setZen(!Settings.zen)
            },
        ]
        if let tab {
            tabsSection.append(UIAction(title: "Desktop Site", image: UIImage(systemName: "desktopcomputer"),
                                        state: tab.desktop ? .on : .off) { [weak self] _ in
                self?.setDesktop(tab, !tab.desktop)
            })
        }

        // bork's add-ons, each opening its popup — the same row Android has.
        var addons: [UIMenuElement] = ExtensionRuntime.shared.extensions.filter { $0.enabled && $0.popup != nil }.map { ext in
            UIAction(title: ext.name, image: Self.icon(for: ext)) { [weak self] _ in self?.openPopup(ext) }
        }
        if let tab, onPage, let url = URL(string: tab.url), let host = url.host,
           ["http", "https"].contains(url.scheme ?? ""),
           ExtensionRuntime.shared.ext(host: "cyberswine")?.enabled == true {
            addons.append(UIAction(title: "Block This Site (Cyberswine)", subtitle: UrlInput.host(tab.url),
                                   image: UIImage(systemName: "nosign"), attributes: .destructive) { [weak self] _ in
                self?.cyberswineBlock(host: host, reloading: tab)
            })
        }
        if let host = tab.flatMap({ URL(string: $0.url)?.host }), onPage, ContentBlocker.shared.enabled {
            let allowed = ContentBlocker.shared.isAllowed(host)
            addons.append(UIAction(title: allowed ? "Block ads here again" : "Don't block ads here",
                                   subtitle: UrlInput.host(tab!.url),
                                   image: UIImage(systemName: allowed ? "shield.lefthalf.filled" : "shield.slash")) { [weak self] _ in
                ContentBlocker.shared.setAllowed(host, !allowed) {
                    guard let self else { return }
                    for t in self.tabs { if let wv = t.webView { ContentBlocker.shared.apply(to: wv.configuration.userContentController) } }
                    tab?.webView?.reload()
                }
            })
        }

        let screens: [UIMenuElement] = [
            UIAction(title: "Bookmarks & History", image: UIImage(systemName: "clock")) { [weak self] _ in self?.openLibrary() },
            UIAction(title: "Add-ons", image: UIImage(systemName: "puzzlepiece.extension")) { [weak self] _ in self?.openAddons() },
            UIAction(title: "Settings", image: UIImage(systemName: "gearshape")) { [weak self] _ in self?.openSettings() },
        ]

        return [
            quick,
            UIMenu(options: .displayInline, children: tabsSection),
            UIMenu(title: "Add-ons", options: .displayInline, children: addons),
            UIMenu(options: .displayInline, children: screens),
        ]
    }

    static func icon(for ext: BorkExtension) -> UIImage? {
        guard let path = ext.icon, let image = UIImage(contentsOfFile: ext.dir.appendingPathComponent(path).path) else {
            return UIImage(systemName: "puzzlepiece.extension")
        }
        let size = CGSize(width: 22, height: 22)
        return UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            .withRenderingMode(.alwaysOriginal)
    }

    private func togglePin() {
        guard let tab = current else { return }
        if Store.shared.isBookmarked(tab.url) {
            Store.shared.removeBookmark(tab.url)
            toast("Unpinned from the start page")
        } else {
            Store.shared.addBookmark(tab.url, title: tab.title.isEmpty ? UrlInput.host(tab.url) : tab.title)
            toast("Pinned to the start page")
        }
    }

    func setDesktop(_ tab: Tab, _ desktop: Bool) {
        tab.desktop = desktop
        guard let wv = tab.webView else { return }
        // m.youtube.com and friends pick their layout by user agent, so the
        // mobile address would come straight back mobile. Go to www. instead.
        if desktop, let url = wv.url, let host = url.host, host.hasPrefix("m."),
           var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.host = "www." + host.dropFirst(2)
            if let target = comps.url { wv.load(URLRequest(url: target)); return }
        }
        wv.reloadFromOrigin()
        scheduleSave()
    }

    func openPopup(_ ext: BorkExtension) {
        guard let url = ext.popupURL else { return }
        let popup = PopupViewController(ext: ext, url: url)
        if let sheet = popup.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }
        present(popup, animated: true)
    }

    func openLibrary() {
        let library = LibraryViewController { [weak self] url in self?.load(url) }
        present(UINavigationController(rootViewController: library), animated: true)
    }

    func openAddons() {
        let addons = AddonsViewController(browser: self)
        present(UINavigationController(rootViewController: addons), animated: true)
    }

    func openSettings() {
        let settings = SettingsViewController(browser: self)
        present(UINavigationController(rootViewController: settings), animated: true)
    }
}
