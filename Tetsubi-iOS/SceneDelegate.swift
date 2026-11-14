// MARK: - iOS Scene Delegate

import SwiftUI
import SwiftData
import JellyfinMusicCore

class SceneDelegate: NSObject, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        window = UIWindow(windowScene: windowScene)
        window?.rootViewController = UIHostingController(
            // No NavigationStack here: each tab has its own, and nesting
            // stacks hides the inner bars (titles, toolbars, search field).
            rootView: AppRootView()
                .modelContainer(try! container())
        )
        window?.makeKeyAndVisible()
        // Opened by tapping the home-screen widget.
        if let url = connectionOptions.urlContexts.first?.url {
            Task { @MainActor in AppSession.shared.open(widgetURL: url) }
        }
    }

    /// A tap on the home-screen widget while the app is running: Now
    /// Playing, or Home when nothing is playing.
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        guard let url = URLContexts.first?.url else { return }
        Task { @MainActor in AppSession.shared.open(widgetURL: url) }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        window?.rootViewController = nil
    }
}

// MARK: - Model container placeholder

import SwiftData

func container() throws -> ModelContainer {
    try ModelContainer(
        for: ServerCredential.self,
             DownloadedAlbum.self,
             DownloadTask.self,
             PlaybackHistoryEntry.self
    )
}
