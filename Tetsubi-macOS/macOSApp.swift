// MARK: - macOS App

import SwiftUI
import SwiftData
import AppKit
import JellyfinMusicCore

@main
struct macOSApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    @State private var modelContainer: ModelContainer

    init() {
        let container = try! ModelContainer(
            for: ServerCredential.self,
                 DownloadedAlbum.self,
                 DownloadTask.self,
                 PlaybackHistoryEntry.self
        )
        self._modelContainer = State(initialValue: container)
    }

    var body: some Scene {
        // One window: the player lives in this window's root view, so a
        // second window would mean a second, independent player.
        Window("Tetsubi", id: "main") {
            AppRootView()
                .modelContainer(modelContainer)
                .frame(minWidth: 360, minHeight: 480)
        }
        .defaultSize(width: 1100, height: 760)
        .commands { AppCommands() }
    }
}

/// Quits when the window closes. Playback belongs to the window, so a
/// windowless app would just sit there silent.
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
