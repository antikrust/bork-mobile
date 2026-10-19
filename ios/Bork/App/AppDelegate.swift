import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Start compiling the block list now; the first page should not wait.
        ContentBlocker.shared.prepare {}
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default", sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var browser: BrowserViewController? {
        window?.rootViewController as? BrowserViewController
    }

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = .dark
        window.tintColor = Theme.accent
        window.rootViewController = BrowserViewController()
        window.makeKeyAndVisible()
        self.window = window
        if let url = options.urlContexts.first?.url { browser?.open(external: url) }
    }

    /// bork://open?url=… and plain http(s) links handed to the app.
    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        if let url = contexts.first?.url { browser?.open(external: url) }
    }

    func sceneDidEnterBackground(_ scene: UIScene) { browser?.saveNow() }
}
