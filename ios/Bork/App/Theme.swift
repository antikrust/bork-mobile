import UIKit

/// bork's compact dark chrome (skin/bork/css/bork.css), as on Android.
enum Theme {
    static let bg = UIColor(rgb: 0x050506)
    static let surface = UIColor(rgb: 0x131317)
    static let surfaceHi = UIColor(rgb: 0x1E1E24)
    static let field = UIColor(rgb: 0x26262C)
    static let line = UIColor(rgb: 0x2E2E36)
    static let fg = UIColor(rgb: 0xECECEF)
    static let dim = UIColor(rgb: 0x9A9AA4)
    static let accent = UIColor(rgb: 0x0A84FF)
    static let pink = UIColor(rgb: 0xFF4AA2)
    static let privateField = UIColor(rgb: 0x3B1F5C)

    static let tilePalette: [UIColor] = [0x0A84FF, 0xFF4AA2, 0x30D158, 0xFF9F0A, 0xBF5AF2, 0x64D2FF, 0xFF453A, 0xFFD60A]
        .map { UIColor(rgb: $0) }

    /// A stable colour per host, for letter tiles.
    static func tileColor(for host: String) -> UIColor {
        var h: UInt32 = 5381
        for b in host.utf8 { h = (h &* 33) &+ UInt32(b) }
        return tilePalette[Int(h % UInt32(tilePalette.count))]
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

/// A rounded letter tile for a site with no icon.
final class LetterTile: UIView {
    private let label = UILabel()
    init(size: CGFloat) {
        super.init(frame: .zero)
        layer.cornerRadius = size / 4
        layer.cornerCurve = .continuous
        label.font = .systemFont(ofSize: size / 2.3, weight: .semibold)
        label.textColor = Theme.bg
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: size),
            heightAnchor.constraint(equalToConstant: size),
            label.centerXAnchor.constraint(equalTo: centerXAnchor),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func set(label text: String, host: String) {
        label.text = String((text.trimmingCharacters(in: .whitespaces).first ?? host.first ?? "?")).uppercased()
        backgroundColor = Theme.tileColor(for: host.isEmpty ? text : host)
    }
}
