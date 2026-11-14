// MARK: - iOS App Delegate

import SwiftUI
import JellyfinMusicCore

@main
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Trial: IBM Plex Sans in the UIKit-drawn bars too.
        PlexFont.applyToUIKitBars()
        // Teach Siri the names of recently played and favourite albums, so
        // "Play <album> in Tetsubi" works without a search.
        Task { @MainActor in
            if AppSession.shared.restore() {
                TetsubiShortcuts.updateAppShortcutParameters()
            }
        }
        // Audio session must be configured before any playback.
        // The actual AVAudioSession setup lives in AudioSessionConfigurator
        // and is called on first play or at launch.
        return true
    }

    // Album downloads use a background URLSession. When they finish while the
    // app is suspended or closed, iOS relaunches it in the background and
    // calls this; the session saves the files and then calls the handler.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        DownloadManager.handleBackgroundEvents(
            identifier: identifier,
            completionHandler: completionHandler
        )
    }

    // Background audio: called when app is backgrounded.
    // No additional logic needed — AVAudioSession(.playback) + UIBackgroundModes keep it alive.
    func applicationWillResignActive(_ application: UIApplication) {}
    func applicationDidEnterBackground(_ application: UIApplication) {}
    func applicationWillEnterForeground(_ application: UIApplication) {}
    func applicationDidBecomeActive(_ application: UIApplication) {}
}
