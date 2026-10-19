import UIKit

/**
 Tabs and workspaces in one sheet, as on Android: workspaces as chips along
 the top (the desktop's workspace rail on its side), the picked workspace's
 tabs as cards below. Long-press a card to move or close it; long-press a
 chip to rename or delete the workspace.
 */
final class TabTrayViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegate {
    private unowned let browser: BrowserViewController
    private let titleLabel = UILabel()
    private let chips = UIStackView()
    private var list: [Tab] = []
    private lazy var grid: UICollectionView = {
        let item = NSCollectionLayoutItem(layoutSize: .init(widthDimension: .fractionalWidth(0.5), heightDimension: .fractionalHeight(1)))
        item.contentInsets = .init(top: 6, leading: 6, bottom: 6, trailing: 6)
        let group = NSCollectionLayoutGroup.horizontal(layoutSize: .init(widthDimension: .fractionalWidth(1), heightDimension: .absolute(230)), subitems: [item])
        let section = NSCollectionLayoutSection(group: group)
        section.contentInsets = .init(top: 4, leading: 10, bottom: 24, trailing: 10)
        let v = UICollectionView(frame: .zero, collectionViewLayout: UICollectionViewCompositionalLayout(section: section))
        v.backgroundColor = Theme.surface
        v.dataSource = self
        v.delegate = self
        v.register(Card.self, forCellWithReuseIdentifier: "card")
        return v
    }()

    init(browser: BrowserViewController) {
        self.browser = browser
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.surface
        titleLabel.font = .systemFont(ofSize: 19, weight: .semibold)
        titleLabel.textColor = Theme.fg

        func button(_ symbol: String, _ label: String, _ action: @escaping () -> Void) -> UIButton {
            let b = UIButton(type: .system, primaryAction: UIAction { _ in action() })
            b.setImage(UIImage(systemName: symbol), for: .normal)
            b.tintColor = Theme.fg
            b.accessibilityLabel = label
            b.widthAnchor.constraint(equalToConstant: 44).isActive = true
            return b
        }
        let more = UIButton(type: .system)
        more.setImage(UIImage(systemName: "ellipsis.circle"), for: .normal)
        more.tintColor = Theme.fg
        more.showsMenuAsPrimaryAction = true
        more.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in
            guard let self else { return done([]) }
            let ws = self.browser.workspace!
            done([
                UIAction(title: "New Workspace", image: UIImage(systemName: "plus.rectangle.on.rectangle")) { _ in
                    self.browser.createWorkspace { _ in self.refresh() }
                },
                UIAction(title: "Rename \(ws.name)", image: UIImage(systemName: "pencil")) { _ in
                    self.browser.renameWorkspace(ws) { self.refresh() }
                },
                UIAction(title: "Delete \(ws.name)", image: UIImage(systemName: "trash"),
                         attributes: self.browser.workspaces.count > 1 ? .destructive : .disabled) { _ in
                    self.browser.deleteWorkspace(ws) { self.refresh() }
                },
                UIAction(title: "Close All Tabs Here", image: UIImage(systemName: "xmark.square"), attributes: .destructive) { _ in
                    self.browser.closeAll(in: ws)
                    self.refresh()
                },
            ])
        }])
        more.widthAnchor.constraint(equalToConstant: 44).isActive = true

        let head = UIStackView(arrangedSubviews: [
            titleLabel,
            button("eyeglasses", "New private tab") { [weak self] in self?.newTab(private: true) },
            button("plus", "New tab") { [weak self] in self?.newTab(private: false) },
            more,
        ])
        head.spacing = 2
        chips.spacing = 8
        let chipScroll = UIScrollView()
        chipScroll.showsHorizontalScrollIndicator = false
        chipScroll.addSubview(chips)
        chips.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            chips.leadingAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.leadingAnchor, constant: 16),
            chips.trailingAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.trailingAnchor, constant: -16),
            chips.topAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.topAnchor),
            chips.bottomAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.bottomAnchor),
            chips.heightAnchor.constraint(equalTo: chipScroll.frameLayoutGuide.heightAnchor),
            chipScroll.heightAnchor.constraint(equalToConstant: 40),
        ])

        for v in [head, chipScroll, grid] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        NSLayoutConstraint.activate([
            head.topAnchor.constraint(equalTo: view.topAnchor, constant: 18),
            head.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            head.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            chipScroll.topAnchor.constraint(equalTo: head.bottomAnchor, constant: 10),
            chipScroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            chipScroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            grid.topAnchor.constraint(equalTo: chipScroll.bottomAnchor, constant: 8),
            grid.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            grid.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            grid.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        refresh()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if let i = list.firstIndex(where: { $0 === browser.current }) {
            grid.scrollToItem(at: IndexPath(item: i, section: 0), at: .centeredVertically, animated: false)
        }
    }

    private func newTab(private isPrivate: Bool) {
        dismiss(animated: true) {
            self.browser.newTab(isPrivate: isPrivate)
            self.browser.focusAddressBar()
        }
    }

    private func refresh() {
        let ws = browser.workspace!
        list = browser.tabs(in: ws)
        titleLabel.text = "\(ws.name) · \(list.count) tab\(list.count == 1 ? "" : "s")"
        grid.reloadData()

        chips.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for w in browser.workspaces {
            var config: UIButton.Configuration = w === ws ? .filled() : .gray()
            config.title = "\(w.name)  \(browser.tabs(in: w).count)"
            config.cornerStyle = .capsule
            let chip = UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in
                self?.browser.switchWorkspace(w)
                self?.refresh()
            })
            chip.menu = UIMenu(title: w.name, children: [
                UIAction(title: "Rename", image: UIImage(systemName: "pencil")) { [weak self] _ in
                    self?.browser.renameWorkspace(w) { self?.refresh() }
                },
                UIAction(title: "Delete", image: UIImage(systemName: "trash"), attributes: .destructive) { [weak self] _ in
                    self?.browser.deleteWorkspace(w) { self?.refresh() }
                },
            ])
            chips.addArrangedSubview(chip)
        }
        var add = UIButton.Configuration.bordered()
        add.title = "+ Workspace"
        add.cornerStyle = .capsule
        chips.addArrangedSubview(UIButton(configuration: add, primaryAction: UIAction { [weak self] _ in
            self?.browser.createWorkspace { _ in self?.refresh() }
        }))
    }

    func collectionView(_ cv: UICollectionView, numberOfItemsInSection section: Int) -> Int { list.count }

    func collectionView(_ cv: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let card = cv.dequeueReusableCell(withReuseIdentifier: "card", for: indexPath) as! Card
        let tab = list[indexPath.item]
        card.set(tab, selected: tab === browser.current)
        card.onClose = { [weak self] in
            self?.browser.close(tab)
            self?.refresh()
        }
        return card
    }

    func collectionView(_ cv: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let tab = list[indexPath.item]
        dismiss(animated: true) { self.browser.show(tab) }
    }

    func collectionView(_ cv: UICollectionView, contextMenuConfigurationForItemsAt paths: [IndexPath], point: CGPoint) -> UIContextMenuConfiguration? {
        guard let path = paths.first else { return nil }
        let tab = list[path.item]
        let others = browser.workspaces.filter { $0.id != tab.workspaceID }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            var items: [UIMenuElement] = []
            if others.isEmpty {
                items.append(UIAction(title: "New Workspace…", image: UIImage(systemName: "plus.rectangle.on.rectangle")) { _ in
                    self?.browser.createWorkspace { _ in self?.refresh() }
                })
            } else {
                items.append(UIMenu(title: "Move to Workspace", image: UIImage(systemName: "rectangle.portrait.and.arrow.right"),
                                    children: others.map { ws in
                    UIAction(title: ws.name) { _ in
                        self?.browser.move(tab, to: ws)
                        self?.refresh()
                    }
                }))
            }
            items.append(UIAction(title: "Close Tab", image: UIImage(systemName: "xmark"), attributes: .destructive) { _ in
                self?.browser.close(tab)
                self?.refresh()
            })
            return UIMenu(title: tab.displayTitle, children: items)
        }
    }

    private final class Card: UICollectionViewCell {
        var onClose: (() -> Void)?
        private let title = UILabel()
        private let close = UIButton(type: .system)
        private let preview = UIImageView()
        private let tile = LetterTile(size: 48)
        private let privateMark = UIImageView(image: UIImage(systemName: "eyeglasses"))

        override init(frame: CGRect) {
            super.init(frame: frame)
            contentView.backgroundColor = Theme.surfaceHi
            contentView.layer.cornerRadius = 12
            contentView.layer.cornerCurve = .continuous
            contentView.clipsToBounds = true
            title.font = .systemFont(ofSize: 13)
            title.textColor = Theme.fg
            close.setImage(UIImage(systemName: "xmark"), for: .normal)
            close.tintColor = Theme.dim
            close.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
            privateMark.tintColor = Theme.pink
            preview.contentMode = .scaleAspectFill
            preview.clipsToBounds = true
            preview.backgroundColor = Theme.bg
            preview.layer.cornerRadius = 8
            let head = UIStackView(arrangedSubviews: [privateMark, title, close])
            head.spacing = 6
            head.alignment = .center
            close.widthAnchor.constraint(equalToConstant: 32).isActive = true
            for v in [head, preview, tile] {
                v.translatesAutoresizingMaskIntoConstraints = false
                contentView.addSubview(v)
            }
            NSLayoutConstraint.activate([
                head.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4),
                head.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
                head.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -2),
                head.heightAnchor.constraint(equalToConstant: 34),
                preview.topAnchor.constraint(equalTo: head.bottomAnchor, constant: 2),
                preview.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 6),
                preview.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -6),
                preview.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
                tile.centerXAnchor.constraint(equalTo: preview.centerXAnchor),
                tile.centerYAnchor.constraint(equalTo: preview.centerYAnchor),
            ])
        }
        required init?(coder: NSCoder) { fatalError() }

        func set(_ tab: Tab, selected: Bool) {
            title.text = tab.displayTitle
            privateMark.isHidden = !tab.isPrivate
            contentView.layer.borderWidth = selected ? 2 : 0
            contentView.layer.borderColor = Theme.accent.cgColor
            if let snap = tab.snapshot, !tab.home {
                preview.image = snap
                preview.contentMode = .scaleAspectFill
                tile.isHidden = true
            } else {
                preview.image = nil
                tile.isHidden = false
                let host = UrlInput.host(tab.url)
                tile.set(label: tab.home ? "bork" : tab.displayTitle, host: host.isEmpty ? tab.displayTitle : host)
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            // The top of a page is what identifies it.
            if let image = preview.image, image.size.width > 0 {
                let scale = preview.bounds.width / image.size.width
                let shown = preview.bounds.height / max(1, image.size.height * scale)
                preview.layer.contentsRect = CGRect(x: 0, y: 0, width: 1, height: min(1, shown))
                preview.contentMode = .scaleToFill
            }
        }
    }
}
