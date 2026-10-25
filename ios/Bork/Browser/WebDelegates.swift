import UIKit
import WebKit

extension BrowserViewController {
    func tab(for webView: WKWebView) -> Tab? { tabs.first { $0.webView === webView } }
}

// MARK: - Navigation

extension BrowserViewController: WKNavigationDelegate {
    private static let webSchemes: Set<String> = ["http", "https", "about", "data", "blob", "file", "bork-ext", "javascript"]

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 preferences: WKWebpagePreferences,
                 decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        guard let url = action.request.url, let tab = tab(for: webView) else {
            return decisionHandler(.allow, preferences)
        }
        preferences.preferredContentMode = tab.desktop ? .desktop : .mobile
        let scheme = url.scheme?.lowercased() ?? ""

        // mailto:, tel:, maps, app links: Android's job is iOS's here too.
        if !Self.webSchemes.contains(scheme) {
            UIApplication.shared.open(url) { opened in
                if !opened { self.toast("No app can open this link") }
            }
            return decisionHandler(.cancel, preferences)
        }
        if action.shouldPerformDownload { return decisionHandler(.download, preferences) }

        // Add-ons that block navigations (Cyberswine) are asked first.
        guard action.targetFrame?.isMainFrame == true, scheme == "http" || scheme == "https" else {
            return decisionHandler(.allow, preferences)
        }
        ExtensionRuntime.shared.beforeNavigation(to: url, tabID: tab.number) { redirect in
            if let redirect {
                decisionHandler(.cancel, preferences)
                if redirect.absoluteString != "about:blank" { webView.load(URLRequest(url: redirect)) }
            } else {
                decisionHandler(.allow, preferences)
            }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if !response.canShowMIMEType { return decisionHandler(.download) }
        if let http = response.response as? HTTPURLResponse,
           let disposition = http.value(forHTTPHeaderField: "Content-Disposition"),
           disposition.lowercased().hasPrefix("attachment") {
            return decisionHandler(.download)
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let tab = tab(for: webView) else { return }
        if !tab.isPrivate, let url = webView.url?.absoluteString {
            Store.shared.recordVisit(url, title: webView.title ?? "")
        }
        scheduleSave()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let e = error as NSError
        // Cancelled (a redirect we made, a new load) and "frame load
        // interrupted" (a download took over) are not failures.
        if e.code == NSURLErrorCancelled || (e.domain == "WebKitErrorDomain" && e.code == 102) { return }
        let failing = (e.userInfo[NSURLErrorFailingURLStringErrorKey] as? String) ?? webView.url?.absoluteString ?? ""
        webView.loadHTMLString(ErrorPage.html(for: e, url: failing), baseURL: URL(string: failing))
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }
}

// MARK: - Windows, dialogs, context menus

extension BrowserViewController: WKUIDelegate {
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard let opener = tab(for: webView) else { return nil }
        let child = Tab(workspaceID: opener.workspaceID, isPrivate: opener.isPrivate)
        child.parentID = opener.id
        child.desktop = opener.desktop
        child.home = false
        child.url = action.request.url?.absoluteString ?? ""
        if let i = tabs.firstIndex(where: { $0 === opener }) { insertTab(child, at: i + 1) }
        let wv = makeChildWebView(child, configuration)
        DispatchQueue.main.async { self.show(child) }
        return wv
    }

    func webViewDidClose(_ webView: WKWebView) {
        if let tab = tab(for: webView) { close(tab) }
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: frame.securityOrigin.host, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: frame.securityOrigin.host, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        let alert = UIAlertController(title: frame.securityOrigin.host, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(nil) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(alert.textFields?.first?.text) })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, contextMenuConfigurationForElement element: WKContextMenuElementInfo,
                 completionHandler: @escaping (UIContextMenuConfiguration?) -> Void) {
        guard let link = element.linkURL, let opener = tab(for: webView) else { return completionHandler(nil) }
        let config = UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            UIMenu(title: link.absoluteString, children: [
                UIAction(title: "Open in New Tab", image: UIImage(systemName: "plus.square.on.square")) { [weak self] _ in
                    self?.newTab(link, select: false, parent: opener, isPrivate: opener.isPrivate)
                    self?.toast("Opened in a new tab")
                },
                UIAction(title: "Open in Private Tab", image: UIImage(systemName: "eyeglasses")) { [weak self] _ in
                    self?.newTab(link, select: true, isPrivate: true)
                },
                UIAction(title: "Copy Link", image: UIImage(systemName: "doc.on.doc")) { _ in
                    UIPasteboard.general.url = link
                },
                UIAction(title: "Share Link", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in
                    self?.share(link)
                },
            ] + (self.cyberswineBlockAction(for: link).map { [$0] } ?? []))
        }
        completionHandler(config)
    }

    /// Cyberswine's "block this site", for a link. On the desktop it is a
    /// right-click menu entry; iOS has no add-on menus, so it is ours.
    func cyberswineBlockAction(for url: URL) -> UIAction? {
        guard let host = url.host, ["http", "https"].contains(url.scheme ?? ""),
              ExtensionRuntime.shared.ext(host: "cyberswine")?.enabled == true else { return nil }
        let site = UrlInput.host(url.absoluteString)
        return UIAction(title: "Block \(site) (Cyberswine)", image: UIImage(systemName: "nosign"),
                        attributes: .destructive) { [weak self] _ in
            self?.cyberswineBlock(host: host, reloading: nil)
        }
    }

    func cyberswineBlock(host: String, reloading tab: Tab?) {
        ExtensionRuntime.shared.messageBackground(host: "cyberswine", ["type": "fkof-block", "host": host]) { [weak self] _ in
            self?.toast("Blocked \(UrlInput.host("https://\(host)/"))")
            // The page you are on goes now; the next visit is stopped at the door.
            tab?.webView?.reload()
        }
    }

    func share(_ url: URL) {
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.maxY - 60, width: 1, height: 1)
        present(sheet, animated: true)
    }
}

// MARK: - Downloads

extension BrowserViewController: WKDownloadDelegate {
    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse,
                  suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(suggestedFilename)
        try? FileManager.default.removeItem(at: file)
        pendingDownloads[ObjectIdentifier(download)] = file
        toast("Downloading \(suggestedFilename)")
        completionHandler(file)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let file = pendingDownloads.removeValue(forKey: ObjectIdentifier(download)) else { return }
        // Hand it to the user to keep: Files, AirDrop, any app.
        let picker = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
        present(picker, animated: true)
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        pendingDownloads.removeValue(forKey: ObjectIdentifier(download))
        toast("Download failed")
    }
}

// MARK: - Add-ons

extension BrowserViewController: ExtensionHost {
    func extensionTabID(for webView: WKWebView) -> Int? { tab(for: webView)?.number }

    func extensionActiveTab() -> (id: Int, url: String, title: String)? {
        guard let tab = current else { return nil }
        return (tab.number, tab.url, tab.title)
    }

    func extensionWebView(forTab id: Int) -> WKWebView? { tabs.first { $0.number == id }?.webView }

    @discardableResult
    func extensionOpen(_ url: URL, active: Bool) -> Int? {
        if presentedViewController is PopupViewController { dismiss(animated: true) }
        return newTab(url, select: active).number
    }

    func extensionClose(_ webView: WKWebView) {
        if let popup = presentedViewController as? PopupViewController, popup.webView === webView {
            popup.dismiss(animated: true)
        } else if let tab = tab(for: webView) {
            close(tab)
        }
    }
}

enum ErrorPage {
    static func html(for error: NSError, url: String) -> String {
        let (title, detail): (String, String)
        switch error.code {
        case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed:
            (title, detail) = ("Can't find that site", "The address may be mistyped, or you may be offline.")
        case NSURLErrorNotConnectedToInternet:
            (title, detail) = ("You're offline", "Check the connection and try again.")
        case NSURLErrorTimedOut:
            (title, detail) = ("The site took too long", "It may be busy or unreachable from here.")
        case NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateHasUnknownRoot, NSURLErrorSecureConnectionFailed:
            (title, detail) = ("Not a secure connection", "The site's certificate could not be trusted, so bork stopped before sending anything.")
        case NSURLErrorCannotConnectToHost:
            (title, detail) = ("Connection refused", "The site is up but would not take the connection.")
        default:
            (title, detail) = ("This page could not be loaded", error.localizedDescription)
        }
        let esc: (String) -> String = {
            $0.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
        }
        return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>\(esc(title))</title>
        <style>
          :root { color-scheme: dark; }
          body { margin: 0; padding: 48px 24px; font: 17px/1.5 -apple-system, system-ui, sans-serif; background: #050506; color: #ececef; }
          .eyes { font-size: 56px; letter-spacing: -6px; margin-bottom: 12px; }
          h1 { font-size: 22px; margin: 0 0 8px; }
          p { margin: 0 0 12px; opacity: .8; }
          code { word-break: break-all; opacity: .7; font-size: 13px; }
          button { margin-top: 20px; padding: 12px 22px; border: 0; border-radius: 10px; background: #0a84ff; color: #fff; font: 600 16px -apple-system, sans-serif; }
        </style></head><body>
        <div class="eyes">◉◉</div>
        <h1>\(esc(title))</h1><p>\(esc(detail))</p><code>\(esc(url))</code><br>
        <button onclick="location.href=\(ExtensionRuntime.jsString(url).replacingOccurrences(of: "\"", with: "&quot;"))">Try again</button>
        </body></html>
        """
    }
}
