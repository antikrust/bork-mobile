import UIKit
import WebKit

/// An add-on's popup (YouTube Manager's switches, Cyberswine's "block this
/// site") in a sheet over the page it is about.
final class PopupViewController: UIViewController, WKUIDelegate {
    let webView: WKWebView
    private let ext: BorkExtension
    private let url: URL

    init(ext: BorkExtension, url: URL) {
        self.ext = ext
        self.url = url
        let config = WKWebViewConfiguration()
        ExtensionRuntime.shared.configureForExtensionPage(config)
        webView = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil)
        ExtensionRuntime.shared.track(webView)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.surface
        let title = UILabel()
        title.text = ext.name
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.textColor = Theme.fg
        title.translatesAutoresizingMaskIntoConstraints = false
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = Theme.surface
        view.addSubview(title)
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: view.topAnchor, constant: 18),
            title.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            webView.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 10),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        webView.load(URLRequest(url: url))
    }

    func webViewDidClose(_ webView: WKWebView) { dismiss(animated: true) }

    /// window.open from a popup (a settings link) becomes a tab.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url { ExtensionRuntime.shared.host?.extensionOpen(url, active: true) }
        return nil
    }
}
