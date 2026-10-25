import Foundation
import WebKit

/**
 YouTube ad blocking on iOS, built into the app.

 uBlock Origin is what does this on Android and the desktop; iOS lets no
 extension run in an app, and YouTube's video ads come from youtube.com
 itself, so a rule list alone cannot tell them from the video. Two parts:

  - youtube-ads.js, in the page's own world before YouTube's scripts run,
    takes the ad sections out of the player and feed data (the way
    uBlock's YouTube filters do) and hides sponsored cards;
  - a small rule list cuts YouTube's ad and ad-tracking requests.

 It needs maintaining the way uBlock's YouTube filters do: when YouTube
 moves its ads somewhere new, youtube-ads.js learns the new place.
 */
final class YouTubeAds {
    static let shared = YouTubeAds()

    var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "youtubeAds.enabled") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "youtubeAds.enabled") }
    }

    private(set) var rules: WKContentRuleList?
    private lazy var source: String = {
        guard let url = Bundle.main.url(forResource: "youtube-ads", withExtension: "js"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text
    }()

    /// The script, for a fresh tab's configuration. Its frames too: a video
    /// embedded on another site plays from a youtube.com frame.
    func configure(_ config: WKWebViewConfiguration) {
        guard enabled, !source.isEmpty else { return }
        config.userContentController.addUserScript(
            WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: false, in: .page))
        #if DEBUG
        config.userContentController.add(DebugLog.shared, contentWorld: .page, name: "borkDebug")
        #endif
    }

    /// YouTube's own ad and ad-tracking addresses. One pattern per rule:
    /// WebKit's url-filter has no alternation.
    private static let blocked = [
        #"^https?://([^/]+\.)?youtube\.com/api/stats/ads"#,
        #"^https?://([^/]+\.)?youtube\.com/pagead/"#,
        #"^https?://([^/]+\.)?youtube\.com/ptracking"#,
        #"^https?://([^/]+\.)?youtube\.com/get_midroll_"#,
        #"^https?://([^/]+\.)?youtube\.com/youtubei/v1/player/ad_break"#,
        #"^https?://([^/]+\.)?doubleclick\.net/"#,
        #"^https?://([^/]+\.)?googleadservices\.com/"#,
        #"^https?://imasdk\.googleapis\.com/"#,
        #"^https?://([^/]+\.)?googlesyndication\.com/"#,
    ]

    func prepare(_ done: @escaping () -> Void) {
        let rules = Self.blocked.map { ["trigger": ["url-filter": $0], "action": ["type": "block"]] }
        let json = String(data: try! JSONSerialization.data(withJSONObject: rules), encoding: .utf8)!
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "bork-youtube-ads-1", encodedContentRuleList: json
        ) { [weak self] list, error in
            if let error { NSLog("[bork] YouTube ad rules did not compile: \(error)") }
            self?.rules = list
            done()
        }
    }
}

#if DEBUG
/// Debug builds only: lets the page scripts report what they did.
final class DebugLog: NSObject, WKScriptMessageHandler {
    static let shared = DebugLog()
    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        NSLog("[bork-debug] %@ %@", message.frameInfo.securityOrigin.host, "\(message.body)")
    }
}
#endif
