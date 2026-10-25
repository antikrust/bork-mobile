import UIKit
import WebKit

/**
 The browser: one web view per tab, tabs grouped into workspaces — the iOS
 twin of the Android BrowserActivity, on WebKit because iOS allows nothing
 else.

 A tab's web view is made the first time the tab is shown, so a restored set
 of tabs costs nothing until one is opened; its back list comes back from
 the saved interactionState.
 */
final class BrowserViewController: UIViewController {

    // MARK: State

    private(set) var workspaces: [Workspace] = []
    private(set) var tabs: [Tab] = []
    private(set) var current: Tab?
    private(set) var workspace: Workspace!
    private var observers: [String: [NSKeyValueObservation]] = [:]
    var pendingDownloads: [ObjectIdentifier: URL] = [:]
    private var zen = Settings.zen
    private lazy var privateStore = WKWebsiteDataStore.nonPersistent()

    // MARK: Views

    private let root = UIStackView()
    private let topSlot = UIView()
    private let bottomSlot = UIStackView()
    private let urlRow = UIView()
    private let content = UIView()
    private let progress = UIProgressView(progressViewStyle: .bar)
    private let urlBox = UIView()
    private let urlIcon = UIImageView()
    let urlField = UITextField()
    private let reloadButton = UIButton(type: .system)
    private let wsBadge = UIButton(type: .system)
    private let backButton = UIButton(type: .system)
    private let forwardButton = UIButton(type: .system)
    private let homeButton = UIButton(type: .system)
    private let tabsButton = UIButton(type: .system)
    private let menuButton = UIButton(type: .system)
    private let zenDot = UIButton(type: .system)
    private let start = StartPageView()
    private let suggestions = SuggestionsView()
    private var topSlotHeight: NSLayoutConstraint!

    override var prefersStatusBarHidden: Bool { zen }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
    override var prefersHomeIndicatorAutoHidden: Bool { zen }

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.surface
        buildViews()
        ExtensionRuntime.shared.host = self
        ExtensionRuntime.shared.startBackgrounds(in: view)
        seedBookmarks()
        restoreTabs()
        applyChrome()
        ContentBlocker.shared.prepare { [weak self] in
            guard let self else { return }
            for tab in self.tabs { if let wv = tab.webView { ContentBlocker.shared.apply(to: wv.configuration.userContentController) } }
        }
    }

    // MARK: Building the chrome

    private func icon(_ name: String, _ action: Selector, _ label: String) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .regular)), for: .normal)
        b.tintColor = Theme.fg
        b.accessibilityLabel = label
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    private func buildViews() {
        root.axis = .vertical
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            root.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])
        view.keyboardLayoutGuide.followsUndockedKeyboard = true

        // URL row: workspace badge, address field, reload.
        wsBadge.backgroundColor = Theme.accent
        wsBadge.layer.cornerRadius = 7
        wsBadge.setTitleColor(.white, for: .normal)
        wsBadge.titleLabel?.font = .systemFont(ofSize: 13, weight: .bold)
        wsBadge.addTarget(self, action: #selector(openTabTray), for: .touchUpInside)
        wsBadge.accessibilityLabel = "Workspace"
        wsBadge.showsMenuAsPrimaryAction = false
        wsBadge.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in
            done(self?.workspaceMenuItems() ?? [])
        }])

        urlBox.backgroundColor = Theme.field
        urlBox.layer.cornerRadius = 10
        urlBox.layer.cornerCurve = .continuous
        urlIcon.tintColor = Theme.dim
        urlIcon.contentMode = .scaleAspectFit
        urlField.textColor = Theme.fg
        urlField.font = .systemFont(ofSize: 16)
        urlField.attributedPlaceholder = NSAttributedString(string: "Search or type a URL", attributes: [.foregroundColor: Theme.dim])
        urlField.keyboardType = .webSearch
        urlField.autocapitalizationType = .none
        urlField.autocorrectionType = .no
        urlField.returnKeyType = .go
        urlField.clearButtonMode = .whileEditing
        urlField.delegate = self
        urlField.addTarget(self, action: #selector(urlChanged), for: .editingChanged)

        reloadButton.tintColor = Theme.fg
        reloadButton.addTarget(self, action: #selector(reloadOrStop), for: .touchUpInside)

        for v in [wsBadge, urlBox, urlIcon, urlField, reloadButton] { v.translatesAutoresizingMaskIntoConstraints = false }
        urlBox.addSubview(urlIcon)
        urlBox.addSubview(urlField)
        urlRow.addSubview(wsBadge)
        urlRow.addSubview(urlBox)
        urlRow.addSubview(reloadButton)
        urlRow.backgroundColor = Theme.surface
        badgeWidth = wsBadge.widthAnchor.constraint(equalToConstant: 28)
        badgeGap = urlBox.leadingAnchor.constraint(equalTo: wsBadge.trailingAnchor, constant: 8)
        NSLayoutConstraint.activate([
            urlRow.heightAnchor.constraint(equalToConstant: 52),
            wsBadge.leadingAnchor.constraint(equalTo: urlRow.leadingAnchor, constant: 10),
            wsBadge.centerYAnchor.constraint(equalTo: urlRow.centerYAnchor),
            badgeWidth,
            wsBadge.heightAnchor.constraint(equalToConstant: 28),
            badgeGap,
            urlBox.centerYAnchor.constraint(equalTo: urlRow.centerYAnchor),
            urlBox.heightAnchor.constraint(equalToConstant: 40),
            reloadButton.leadingAnchor.constraint(equalTo: urlBox.trailingAnchor, constant: 2),
            reloadButton.trailingAnchor.constraint(equalTo: urlRow.trailingAnchor, constant: -4),
            reloadButton.centerYAnchor.constraint(equalTo: urlRow.centerYAnchor),
            reloadButton.widthAnchor.constraint(equalToConstant: 44),
            reloadButton.heightAnchor.constraint(equalToConstant: 44),
            urlIcon.leadingAnchor.constraint(equalTo: urlBox.leadingAnchor, constant: 10),
            urlIcon.centerYAnchor.constraint(equalTo: urlBox.centerYAnchor),
            urlIcon.widthAnchor.constraint(equalToConstant: 16),
            urlIcon.heightAnchor.constraint(equalToConstant: 16),
            urlField.leadingAnchor.constraint(equalTo: urlIcon.trailingAnchor, constant: 8),
            urlField.trailingAnchor.constraint(equalTo: urlBox.trailingAnchor, constant: -4),
            urlField.topAnchor.constraint(equalTo: urlBox.topAnchor),
            urlField.bottomAnchor.constraint(equalTo: urlBox.bottomAnchor),
        ])

        progress.progressTintColor = Theme.accent
        progress.trackTintColor = .clear
        progress.translatesAutoresizingMaskIntoConstraints = false

        // Bottom: back, forward, home, tabs, menu.
        backButton.setImage(UIImage(systemName: "chevron.backward"), for: .normal)
        backButton.addTarget(self, action: #selector(goBack), for: .touchUpInside)
        backButton.accessibilityLabel = "Back"
        forwardButton.setImage(UIImage(systemName: "chevron.forward"), for: .normal)
        forwardButton.addTarget(self, action: #selector(goForward), for: .touchUpInside)
        forwardButton.accessibilityLabel = "Forward"
        homeButton.setImage(UIImage(systemName: "house"), for: .normal)
        homeButton.addTarget(self, action: #selector(goHome), for: .touchUpInside)
        homeButton.accessibilityLabel = "Start page"
        tabsButton.layer.borderColor = Theme.fg.cgColor
        tabsButton.layer.borderWidth = 2
        tabsButton.layer.cornerRadius = 6
        tabsButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .bold)
        tabsButton.setTitleColor(Theme.fg, for: .normal)
        tabsButton.addTarget(self, action: #selector(openTabTray), for: .touchUpInside)
        tabsButton.accessibilityLabel = "Tabs and workspaces"
        tabsButton.translatesAutoresizingMaskIntoConstraints = false
        let tabsHolder = UIView()
        tabsHolder.addSubview(tabsButton)
        NSLayoutConstraint.activate([
            tabsButton.centerXAnchor.constraint(equalTo: tabsHolder.centerXAnchor),
            tabsButton.centerYAnchor.constraint(equalTo: tabsHolder.centerYAnchor),
            tabsButton.widthAnchor.constraint(equalToConstant: 26),
            tabsButton.heightAnchor.constraint(equalToConstant: 26),
        ])
        menuButton.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        menuButton.accessibilityLabel = "Menu"
        menuButton.showsMenuAsPrimaryAction = true
        menuButton.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in
            done(self?.mainMenuItems() ?? [])
        }])
        let nav = UIStackView(arrangedSubviews: [backButton, forwardButton, homeButton, tabsHolder, menuButton])
        nav.distribution = .fillEqually
        nav.heightAnchor.constraint(equalToConstant: 50).isActive = true
        for b in [backButton, forwardButton, homeButton, menuButton] { b.tintColor = Theme.fg }

        bottomSlot.axis = .vertical
        bottomSlot.backgroundColor = Theme.surface
        bottomSlot.addArrangedSubview(nav)

        content.backgroundColor = Theme.bg
        content.clipsToBounds = true
        start.translatesAutoresizingMaskIntoConstraints = false
        start.browser = self
        suggestions.translatesAutoresizingMaskIntoConstraints = false
        suggestions.isHidden = true
        suggestions.onPick = { [weak self] url in
            self?.urlField.resignFirstResponder()
            self?.load(url)
        }
        content.addSubview(start)
        content.addSubview(suggestions)
        content.addSubview(progress)
        for v in [start, suggestions] {
            NSLayoutConstraint.activate([
                v.topAnchor.constraint(equalTo: content.topAnchor),
                v.bottomAnchor.constraint(equalTo: content.bottomAnchor),
                v.leadingAnchor.constraint(equalTo: content.leadingAnchor),
                v.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            ])
        }
        NSLayoutConstraint.activate([
            progress.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            progress.heightAnchor.constraint(equalToConstant: 2),
        ])
        progressTop = progress.topAnchor.constraint(equalTo: content.topAnchor)
        progressBottom = progress.bottomAnchor.constraint(equalTo: content.bottomAnchor)

        root.addArrangedSubview(topSlot)
        root.addArrangedSubview(content)
        root.addArrangedSubview(bottomSlot)
        placeToolbar()

        // Zen mode's only chrome: one faint dot, mid-right, away from the
        // menus sites put in their top corners.
        zenDot.setImage(UIImage(systemName: "circle.circle"), for: .normal)
        zenDot.tintColor = Theme.fg
        zenDot.backgroundColor = Theme.surface.withAlphaComponent(0.8)
        zenDot.layer.cornerRadius = 20
        zenDot.alpha = 0.45
        zenDot.accessibilityLabel = "Leave zen mode"
        zenDot.addTarget(self, action: #selector(leaveZen), for: .touchUpInside)
        zenDot.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(zenDot)
        NSLayoutConstraint.activate([
            zenDot.widthAnchor.constraint(equalToConstant: 40),
            zenDot.heightAnchor.constraint(equalToConstant: 40),
            zenDot.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            zenDot.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    private var progressTop: NSLayoutConstraint!
    private var badgeWidth: NSLayoutConstraint!
    private var badgeGap: NSLayoutConstraint!
    private var progressBottom: NSLayoutConstraint!

    /// The address bar at the top, or above the buttons at the bottom.
    func placeToolbar() {
        urlRow.removeFromSuperview()
        progressTop.isActive = false
        progressBottom.isActive = false
        if Settings.toolbarAtBottom {
            urlRow.translatesAutoresizingMaskIntoConstraints = true
            bottomSlot.insertArrangedSubview(urlRow, at: 0)
            topSlot.isHidden = true
            progressBottom.isActive = true
        } else {
            urlRow.translatesAutoresizingMaskIntoConstraints = false
            topSlot.addSubview(urlRow)
            NSLayoutConstraint.activate([
                urlRow.topAnchor.constraint(equalTo: topSlot.topAnchor),
                urlRow.bottomAnchor.constraint(equalTo: topSlot.bottomAnchor),
                urlRow.leadingAnchor.constraint(equalTo: topSlot.leadingAnchor),
                urlRow.trailingAnchor.constraint(equalTo: topSlot.trailingAnchor),
            ])
            topSlot.isHidden = zen
            progressTop.isActive = true
        }
    }

    // MARK: Tabs

    private func restoreTabs() {
        if Settings.restoreTabs, let snap = TabStore.load(), !snap.workspaces.isEmpty {
            workspaces = snap.workspaces
            tabs = snap.tabs.filter { t in snap.workspaces.contains { $0.id == t.workspace } }.map(Tab.init(saved:))
            workspace = workspaces.first { $0.id == snap.currentWorkspace } ?? workspaces[0]
        } else {
            workspace = Workspace(name: "Home")
            workspaces = [workspace]
        }
        let tab = tabs(in: workspace).first { $0.id == workspace.lastTabID }
            ?? tabs(in: workspace).max { $0.lastUsed < $1.lastUsed }
            ?? newTab(select: false)
        show(tab)
    }

    func tabs(in ws: Workspace) -> [Tab] { tabs.filter { $0.workspaceID == ws.id } }

    @discardableResult
    func newTab(_ url: URL? = nil, select: Bool = true, parent: Tab? = nil, isPrivate: Bool = false) -> Tab {
        let tab = Tab(workspaceID: workspace.id, isPrivate: isPrivate)
        tab.desktop = Settings.desktopByDefault
        tab.parentID = parent?.id
        if let parent, let i = tabs.firstIndex(where: { $0 === parent }) {
            tabs.insert(tab, at: i + 1)
        } else {
            tabs.append(tab)
        }
        if let url { load(url, in: tab, select: false) }
        if select { show(tab) }
        updateUI()
        scheduleSave()
        return tab
    }

    func insertTab(_ tab: Tab, at index: Int) {
        tabs.insert(tab, at: min(index, tabs.count))
        scheduleSave()
    }

    /// A tab for a window the page opened: WebKit supplies the configuration.
    func makeChildWebView(_ tab: Tab, _ configuration: WKWebViewConfiguration) -> WKWebView {
        ensureWebView(tab, configuration: configuration)
    }

    func load(_ url: URL, in target: Tab? = nil, select: Bool = true) {
        guard let tab = target ?? current ?? Optional(newTab(select: true)) else { return }
        let wv = ensureWebView(tab)
        tab.home = false
        tab.url = url.absoluteString
        if url.isFileURL {
            wv.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        } else {
            wv.load(URLRequest(url: url))
        }
        if select, tab === current { show(tab) } else { updateUI() }
    }

    /// Safari's version is the system's: "18.5".
    static let safariVersion = UIDevice.current.systemVersion.split(separator: ".").prefix(2).joined(separator: ".")

    /// The tail Safari puts on its user agent. WebKit's default stops at
    /// "Mobile/15E148", and Google reads a user agent without "Safari/" as
    /// an embedded web view and refuses to sign in ("This browser or app may
    /// not be secure").
    static let safariName = "Version/\(safariVersion) Mobile/15E148 Safari/604.1"

    /// What Safari sends when you ask for the desktop site. The tail above is
    /// fixed when the web view is made, so a desktop-site tab sets the whole
    /// string instead; without it, the Mac string would still say "Mobile".
    static let desktopUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/\(safariVersion) Safari/605.1.15"

    @discardableResult
    private func ensureWebView(_ tab: Tab, configuration: WKWebViewConfiguration? = nil) -> WKWebView {
        if let wv = tab.webView { return wv }
        let fresh = configuration == nil
        let config = configuration ?? WKWebViewConfiguration()
        if fresh {
            config.websiteDataStore = tab.isPrivate ? privateStore : .default()
            config.allowsInlineMediaPlayback = true
            config.mediaTypesRequiringUserActionForPlayback = .audio
            config.preferences.isElementFullscreenEnabled = true
            config.defaultWebpagePreferences.preferredContentMode = tab.desktop ? .desktop : .mobile
            config.defaultWebpagePreferences.allowsContentJavaScript = Settings.javascript
            config.applicationNameForUserAgent = Self.safariName
            // Add-ons and content scripts. A configuration WebKit hands us
            // for a popup window is a copy of its opener's and has them already.
            ExtensionRuntime.shared.configure(config)
            YouTubeAds.shared.configure(config)
        }
        ContentBlocker.shared.apply(to: config.userContentController)
        let wv = WKWebView(frame: content.bounds, configuration: config)
        wv.navigationDelegate = self
        wv.uiDelegate = self
        wv.allowsBackForwardNavigationGestures = true
        wv.isFindInteractionEnabled = true
        wv.isInspectable = Settings.inspectable
        wv.backgroundColor = Theme.bg
        wv.isOpaque = false
        wv.scrollView.contentInsetAdjustmentBehavior = .automatic
        tab.webView = wv
        ExtensionRuntime.shared.track(wv)
        observe(tab, wv)
        if fresh {
            if let state = tab.savedState {
                wv.interactionState = state
                tab.savedState = nil
            } else if !tab.url.isEmpty, let url = URL(string: tab.url) {
                wv.load(URLRequest(url: url))
            }
        }
        return wv
    }

    private func observe(_ tab: Tab, _ wv: WKWebView) {
        let update: (WKWebView) -> Void = { [weak self, weak tab] wv in
            guard let self, let tab else { return }
            if let url = wv.url?.absoluteString { tab.url = url }
            tab.title = wv.title ?? tab.title
            if tab === self.current { self.updateUI() }
        }
        observers[tab.id] = [
            wv.observe(\.url) { wv, _ in update(wv) },
            wv.observe(\.title) { [weak tab] wv, _ in
                update(wv)
                if let tab, !tab.isPrivate, let url = wv.url?.absoluteString { Store.shared.setTitle(url, wv.title ?? "") }
            },
            wv.observe(\.estimatedProgress) { wv, _ in update(wv) },
            wv.observe(\.isLoading) { wv, _ in update(wv) },
            wv.observe(\.canGoBack) { wv, _ in update(wv) },
            wv.observe(\.canGoForward) { wv, _ in update(wv) },
            wv.observe(\.hasOnlySecureContent) { wv, _ in update(wv) },
        ]
    }

    func show(_ tab: Tab) {
        if let previous = current, previous !== tab {
            snapshot(previous)
            previous.webView?.removeFromSuperview()
        }
        current = tab
        tab.lastUsed = Date()
        if tab.workspaceID != workspace.id, let ws = workspaces.first(where: { $0.id == tab.workspaceID }) {
            workspace = ws
        }
        workspace.lastTabID = tab.id
        if !tab.home || tab.webView != nil {
            let wv = ensureWebView(tab)
            if wv.superview !== content {
                wv.frame = content.bounds
                wv.translatesAutoresizingMaskIntoConstraints = false
                content.insertSubview(wv, belowSubview: start)
                NSLayoutConstraint.activate([
                    wv.topAnchor.constraint(equalTo: content.topAnchor),
                    wv.bottomAnchor.constraint(equalTo: content.bottomAnchor),
                    wv.leadingAnchor.constraint(equalTo: content.leadingAnchor),
                    wv.trailingAnchor.constraint(equalTo: content.trailingAnchor),
                ])
            }
        }
        updateUI()
        scheduleSave()
    }

    func close(_ tab: Tab, selectInstead: Tab? = nil) {
        guard let i = tabs.firstIndex(where: { $0 === tab }) else { return }
        if let view = tab.webView { ExtensionRuntime.shared.pageGone(view) }
        tab.webView?.removeFromSuperview()
        tab.webView?.stopLoading()
        observers[tab.id] = nil
        tab.webView = nil
        tabs.remove(at: i)
        if current === tab {
            current = nil
            let next = selectInstead.flatMap { s in tabs.contains { $0 === s } ? s : nil }
                ?? tabs(in: workspace).max { $0.lastUsed < $1.lastUsed }
                ?? newTab(select: false)
            show(next)
        }
        if !tabs.contains(where: \.isPrivate) {
            // Last private tab gone: so is everything it stored.
            privateStore = WKWebsiteDataStore.nonPersistent()
        }
        updateUI()
        scheduleSave()
    }

    func closeAll(in ws: Workspace) {
        for tab in tabs(in: ws) {
            if let view = tab.webView { ExtensionRuntime.shared.pageGone(view) }
            tab.webView?.removeFromSuperview()
            observers[tab.id] = nil
            tab.webView = nil
            tabs.removeAll { $0 === tab }
        }
        if let c = current, !tabs.contains(where: { $0 === c }) {
            current = nil
            show(newTab(select: false))
        }
        updateUI()
        scheduleSave()
    }

    private func snapshot(_ tab: Tab) {
        guard let wv = tab.webView, !tab.home, wv.superview != nil, wv.bounds.width > 0 else { return }
        let config = WKSnapshotConfiguration()
        config.snapshotWidth = 360
        wv.takeSnapshot(with: config) { image, _ in tab.snapshot = image }
    }

    // MARK: Workspaces

    func switchWorkspace(_ ws: Workspace) {
        guard ws !== workspace else { return }
        workspace = ws
        let tab = tabs(in: ws).first { $0.id == ws.lastTabID }
            ?? tabs(in: ws).max { $0.lastUsed < $1.lastUsed }
            ?? newTab(select: false)
        show(tab)
    }

    func createWorkspace(then done: ((Workspace) -> Void)? = nil) {
        ask(title: "New workspace", placeholder: "Name", initial: "", action: "Create", from: presentedViewController ?? self) { [weak self] name in
            guard let self else { return }
            let ws = Workspace(name: name.isEmpty ? "Workspace \(self.workspaces.count + 1)" : name)
            self.workspaces.append(ws)
            self.switchWorkspace(ws)
            done?(ws)
        }
    }

    func renameWorkspace(_ ws: Workspace, then done: @escaping () -> Void) {
        ask(title: "Rename workspace", placeholder: "Name", initial: ws.name, action: "Rename", from: presentedViewController ?? self) { [weak self] name in
            if !name.isEmpty { ws.name = name }
            self?.updateUI()
            self?.scheduleSave()
            done()
        }
    }

    func deleteWorkspace(_ ws: Workspace, then done: @escaping () -> Void) {
        guard workspaces.count > 1 else { return }
        let count = tabs(in: ws).count
        let alert = UIAlertController(title: "Delete \(ws.name)?",
                                      message: count == 0 ? "It has no tabs." : "Its \(count) tab\(count == 1 ? "" : "s") will close.",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { [weak self] _ in
            guard let self else { return }
            let wasCurrent = ws === self.workspace
            for tab in self.tabs(in: ws) {
                tab.webView?.removeFromSuperview()
                self.observers[tab.id] = nil
                tab.webView = nil
            }
            self.tabs.removeAll { $0.workspaceID == ws.id }
            self.workspaces.removeAll { $0 === ws }
            if wasCurrent {
                self.workspace = self.workspaces[0]
                self.current = nil
                self.show(self.tabs(in: self.workspace).max { $0.lastUsed < $1.lastUsed } ?? self.newTab(select: false))
            }
            self.updateUI()
            self.scheduleSave()
            done()
        })
        (presentedViewController ?? self).present(alert, animated: true)
    }

    func move(_ tab: Tab, to ws: Workspace) {
        tab.workspaceID = ws.id
        if tab === current {
            current = nil
            tab.webView?.removeFromSuperview()
            show(tabs(in: workspace).max { $0.lastUsed < $1.lastUsed } ?? newTab(select: false))
        }
        updateUI()
        scheduleSave()
    }

    private func workspaceMenuItems() -> [UIMenuElement] {
        var items: [UIMenuElement] = workspaces.map { ws in
            UIAction(title: ws.name, subtitle: "\(tabs(in: ws).count) tabs", state: ws === workspace ? .on : .off) { [weak self] _ in
                self?.switchWorkspace(ws)
            }
        }
        items.append(UIAction(title: "New workspace…", image: UIImage(systemName: "plus")) { [weak self] _ in
            self?.createWorkspace()
        })
        return items
    }

    @objc func openTabTray() {
        urlField.resignFirstResponder()
        if let tab = current { snapshot(tab) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) { [weak self] in
            guard let self else { return }
            let tray = TabTrayViewController(browser: self)
            if let sheet = tray.sheetPresentationController {
                sheet.detents = [.large()]
                sheet.prefersGrabberVisible = true
            }
            self.present(tray, animated: true)
        }
    }

    // MARK: Navigation

    @objc private func goBack() {
        guard let tab = current else { return }
        if tab.home, tab.webView != nil, !tab.url.isEmpty {
            tab.home = false
            show(tab)
        } else if let wv = tab.webView, wv.canGoBack, !tab.home {
            wv.goBack()
        } else if let parent = tabs.first(where: { $0.id == tab.parentID }) {
            close(tab, selectInstead: parent)
        }
    }

    @objc private func goForward() {
        guard let tab = current else { return }
        tab.home = false
        tab.webView?.goForward()
        show(tab)
    }

    @objc func goHome() {
        guard let tab = current else { return }
        urlField.resignFirstResponder()
        tab.home = true
        show(tab)
    }

    @objc private func reloadOrStop() {
        guard let wv = current?.webView else { return }
        if wv.isLoading { wv.stopLoading() } else { wv.reload() }
    }

    func open(external url: URL) {
        var target = url
        if url.scheme == "bork", let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
           let raw = comps.queryItems?.first(where: { $0.name == "url" })?.value, let inner = URL(string: raw) {
            target = inner
        }
        if let tab = current, tab.home, tab.webView == nil { load(target, in: tab) } else { newTab(target) }
    }

    // MARK: Zen

    func setZen(_ on: Bool) {
        zen = on
        Settings.zen = on
        applyChrome()
    }

    @objc private func leaveZen() { setZen(false) }

    private func applyChrome() {
        topSlot.isHidden = zen || Settings.toolbarAtBottom
        bottomSlot.isHidden = zen
        zenDot.isHidden = !zen
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
    }

    // MARK: UI

    func updateUI() {
        guard let tab = current else { return }
        let wv = tab.webView
        if !urlField.isFirstResponder {
            urlField.text = tab.home ? "" : displayURL(tab)
        }
        let symbol: String
        if tab.home || tab.url.isEmpty { symbol = "magnifyingglass" }
        else if tab.isPrivate { symbol = "eyeglasses" }
        else if tab.url.hasPrefix("bork-ext:") { symbol = "puzzlepiece.extension" }
        else if wv?.hasOnlySecureContent == true { symbol = "lock.fill" }
        else { symbol = "magnifyingglass" }
        urlIcon.image = UIImage(systemName: symbol)
        urlBox.backgroundColor = tab.isPrivate ? Theme.privateField : Theme.field

        let loading = wv?.isLoading == true && !tab.home
        reloadButton.setImage(UIImage(systemName: loading ? "xmark" : "arrow.clockwise"), for: .normal)
        reloadButton.isEnabled = !tab.home && wv != nil
        progress.isHidden = !loading
        progress.setProgress(Float(max(0.05, wv?.estimatedProgress ?? 0)), animated: loading)

        let back = (wv?.canGoBack == true && !tab.home) || (tab.home && wv != nil && !tab.url.isEmpty) || tab.parentID != nil
        backButton.isEnabled = back
        forwardButton.isEnabled = wv?.canGoForward == true && !tab.home

        let count = tabs(in: workspace).count
        tabsButton.setTitle(count > 99 ? ":D" : "\(count)", for: .normal)
        wsBadge.setTitle(workspace.name.first.map { String($0).uppercased() } ?? "•", for: .normal)
        wsBadge.isHidden = workspaces.count < 2
        // One workspace: no badge, and no gap where it would be.
        badgeWidth.constant = workspaces.count < 2 ? 0 : 28
        badgeGap.constant = workspaces.count < 2 ? -2 : 8

        let showStart = tab.home || (tab.webView == nil && tab.url.isEmpty)
        start.isHidden = !showStart
        if showStart { start.reload() }
    }

    /// Add-on pages show the add-on's name, not bork-ext:// and a path.
    private func displayURL(_ tab: Tab) -> String {
        if let url = URL(string: tab.url), url.scheme == "bork-ext", let ext = ExtensionRuntime.shared.ext(host: url.host ?? "") {
            return tab.title.isEmpty || tab.title.hasPrefix(ext.name) ? (tab.title.isEmpty ? ext.name : tab.title) : "\(ext.name) · \(tab.title)"
        }
        return UrlInput.display(tab.url)
    }

    // MARK: Saving

    private var saveWork: DispatchWorkItem?

    func scheduleSave() {
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    func saveNow() {
        saveWork?.cancel()
        guard workspace != nil else { return }
        if Settings.restoreTabs {
            TabStore.save(workspaces: workspaces, tabs: tabs, current: workspace.id)
        } else {
            TabStore.clear()
        }
    }

    // MARK: Start page data

    private func seedBookmarks() {
        guard !Settings.seeded else { return }
        Settings.seeded = true
        guard Store.shared.bookmarks().isEmpty else { return }
        for (url, title) in [
            ("https://m.youtube.com/", "YouTube"),
            ("https://en.m.wikipedia.org/", "Wikipedia"),
            ("https://duckduckgo.com/", "DuckDuckGo"),
            ("https://news.ycombinator.com/", "Hacker News"),
            ("https://www.openstreetmap.org/", "OpenStreetMap"),
            ("https://borkbrowser.online/", "bork"),
        ] { Store.shared.addBookmark(url, title: title) }
    }

    // MARK: Small helpers

    func ask(title: String, placeholder: String, initial: String, action: String,
             from presenter: UIViewController, done: @escaping (String) -> Void) {
        let alert = UIAlertController(title: title, message: nil, preferredStyle: .alert)
        alert.addTextField { f in
            f.placeholder = placeholder
            f.text = initial
            f.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: action, style: .default) { _ in
            done(alert.textFields?.first?.text?.trimmingCharacters(in: .whitespaces) ?? "")
        })
        presenter.present(alert, animated: true)
    }

    func toast(_ text: String) {
        let label = PaddedLabel()
        label.text = text
        label.textColor = Theme.bg
        label.backgroundColor = Theme.fg
        label.font = .systemFont(ofSize: 14, weight: .medium)
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -64),
        ])
        label.alpha = 0
        UIView.animate(withDuration: 0.2) { label.alpha = 1 }
        UIView.animate(withDuration: 0.3, delay: 2, options: []) { label.alpha = 0 } completion: { _ in label.removeFromSuperview() }
    }

    @objc private func urlChanged() { suggestions.update(query: urlField.text ?? "", current: current) }
}

final class PaddedLabel: UILabel {
    override func drawText(in rect: CGRect) { super.drawText(in: rect.inset(by: UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16))) }
    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + 32, height: s.height + 20)
    }
}

// MARK: - Address field

extension BrowserViewController: UITextFieldDelegate {
    func textFieldDidBeginEditing(_ textField: UITextField) {
        if let tab = current { textField.text = tab.home ? "" : tab.url }
        textField.selectAll(nil)
        suggestions.isHidden = false
        suggestions.update(query: "", current: current)
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        suggestions.isHidden = true
        updateUI()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        let text = textField.text ?? ""
        textField.resignFirstResponder()
        if let url = UrlInput.url(for: text) { load(url) }
        return true
    }

    func focusAddressBar() {
        if zen { setZen(false) }
        urlField.becomeFirstResponder()
    }
}
