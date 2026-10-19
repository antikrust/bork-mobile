import UIKit

/// Pinned sites as tiles under the bork eyes. Long-press a tile to edit it.
final class StartPageView: UIView, UICollectionViewDataSource, UICollectionViewDelegate {
    weak var browser: BrowserViewController?
    private var sites: [Site] = []
    private lazy var grid: UICollectionView = {
        let item = NSCollectionLayoutItem(layoutSize: .init(widthDimension: .fractionalWidth(0.25), heightDimension: .absolute(96)))
        let group = NSCollectionLayoutGroup.horizontal(layoutSize: .init(widthDimension: .fractionalWidth(1), heightDimension: .absolute(96)), subitems: [item])
        let section = NSCollectionLayoutSection(group: group)
        section.contentInsets = .init(top: 0, leading: 12, bottom: 24, trailing: 12)
        let header = NSCollectionLayoutBoundarySupplementaryItem(
            layoutSize: .init(widthDimension: .fractionalWidth(1), heightDimension: .absolute(290)),
            elementKind: UICollectionView.elementKindSectionHeader, alignment: .top)
        section.boundarySupplementaryItems = [header]
        let view = UICollectionView(frame: .zero, collectionViewLayout: UICollectionViewCompositionalLayout(section: section))
        view.backgroundColor = Theme.bg
        view.dataSource = self
        view.delegate = self
        view.register(TileCell.self, forCellWithReuseIdentifier: "tile")
        view.register(Header.self, forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader, withReuseIdentifier: "head")
        view.keyboardDismissMode = .onDrag
        return view
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.bg
        grid.translatesAutoresizingMaskIntoConstraints = false
        addSubview(grid)
        NSLayoutConstraint.activate([
            grid.topAnchor.constraint(equalTo: topAnchor),
            grid.bottomAnchor.constraint(equalTo: bottomAnchor),
            grid.leadingAnchor.constraint(equalTo: leadingAnchor),
            grid.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func reload() {
        sites = Store.shared.bookmarks()
        grid.reloadData()
    }

    func collectionView(_ cv: UICollectionView, numberOfItemsInSection section: Int) -> Int { sites.count }

    func collectionView(_ cv: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = cv.dequeueReusableCell(withReuseIdentifier: "tile", for: indexPath) as! TileCell
        cell.set(sites[indexPath.item])
        return cell
    }

    func collectionView(_ cv: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        let head = cv.dequeueReusableSupplementaryView(ofKind: kind, withReuseIdentifier: "head", for: indexPath) as! Header
        head.onSearch = { [weak self] in self?.browser?.focusAddressBar() }
        head.hint.isHidden = !sites.isEmpty
        return head
    }

    func collectionView(_ cv: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard let url = URL(string: sites[indexPath.item].url) else { return }
        browser?.load(url)
    }

    func collectionView(_ cv: UICollectionView, contextMenuConfigurationForItemsAt paths: [IndexPath], point: CGPoint) -> UIContextMenuConfiguration? {
        guard let path = paths.first else { return nil }
        let site = sites[path.item]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            UIMenu(title: site.title, children: [
                UIAction(title: "Open in New Tab", image: UIImage(systemName: "plus.square.on.square")) { _ in
                    if let url = URL(string: site.url) { self?.browser?.newTab(url) }
                },
                UIAction(title: "Rename", image: UIImage(systemName: "pencil")) { _ in
                    guard let b = self?.browser else { return }
                    b.ask(title: "Rename", placeholder: "Name", initial: site.title, action: "Save", from: b) { name in
                        Store.shared.renameBookmark(site.url, name)
                        self?.reload()
                    }
                },
                UIAction(title: "Move Left", image: UIImage(systemName: "arrow.left")) { _ in
                    Store.shared.moveBookmark(site.url, by: -1); self?.reload()
                },
                UIAction(title: "Move Right", image: UIImage(systemName: "arrow.right")) { _ in
                    Store.shared.moveBookmark(site.url, by: 1); self?.reload()
                },
                UIAction(title: "Unpin", image: UIImage(systemName: "star.slash"), attributes: .destructive) { _ in
                    Store.shared.removeBookmark(site.url); self?.reload()
                },
            ])
        }
    }

    private final class TileCell: UICollectionViewCell {
        private let tile = LetterTile(size: 52)
        private let label = UILabel()
        override init(frame: CGRect) {
            super.init(frame: frame)
            label.font = .systemFont(ofSize: 12)
            label.textColor = Theme.fg
            label.textAlignment = .center
            let stack = UIStackView(arrangedSubviews: [tile, label])
            stack.axis = .vertical
            stack.alignment = .center
            stack.spacing = 6
            stack.translatesAutoresizingMaskIntoConstraints = false
            contentView.addSubview(stack)
            NSLayoutConstraint.activate([
                stack.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
                stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
                label.widthAnchor.constraint(lessThanOrEqualTo: contentView.widthAnchor, constant: -8),
            ])
        }
        required init?(coder: NSCoder) { fatalError() }
        func set(_ site: Site) {
            let host = UrlInput.host(site.url)
            label.text = site.title.isEmpty ? host : site.title
            tile.set(label: label.text ?? "", host: host)
        }
    }

    private final class Header: UICollectionReusableView {
        var onSearch: (() -> Void)?
        let hint = UILabel()
        override init(frame: CGRect) {
            super.init(frame: frame)
            let logo = UIImageView(image: UIImage(named: "Eyes"))
            logo.contentMode = .scaleAspectFit
            logo.widthAnchor.constraint(equalToConstant: 72).isActive = true
            logo.heightAnchor.constraint(equalToConstant: 72).isActive = true
            let word = UILabel()
            word.text = "bork"
            word.font = .systemFont(ofSize: 30, weight: .bold)
            word.textColor = Theme.fg
            var config = UIButton.Configuration.filled()
            config.baseBackgroundColor = Theme.field
            config.baseForegroundColor = Theme.dim
            config.title = "Search the web"
            config.image = UIImage(systemName: "magnifyingglass")
            config.imagePadding = 10
            config.cornerStyle = .medium
            let search = UIButton(configuration: config)
            search.contentHorizontalAlignment = .leading
            search.addAction(UIAction { [weak self] _ in self?.onSearch?() }, for: .touchUpInside)
            search.heightAnchor.constraint(equalToConstant: 48).isActive = true
            hint.text = "Pin a page from the menu and it lands here.\nLong-press a tile to edit it."
            hint.numberOfLines = 0
            hint.textAlignment = .center
            hint.font = .systemFont(ofSize: 13)
            hint.textColor = Theme.dim
            let stack = UIStackView(arrangedSubviews: [logo, word, search, hint])
            stack.axis = .vertical
            stack.alignment = .center
            stack.spacing = 10
            stack.setCustomSpacing(26, after: word)
            stack.translatesAutoresizingMaskIntoConstraints = false
            addSubview(stack)
            NSLayoutConstraint.activate([
                stack.topAnchor.constraint(equalTo: topAnchor, constant: 48),
                stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
                stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
                search.widthAnchor.constraint(equalTo: stack.widthAnchor),
            ])
        }
        required init?(coder: NSCoder) { fatalError() }
    }
}
