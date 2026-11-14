// MARK: - WidgetPlaybackActions — what the widget's buttons do, in the app

import JellyfinMusicCore
import TetsubiWidgetData

/// Run by the widget's intents (Tetsubi-Widget/PlaybackIntents.swift) in the
/// app's process. If the app was closed, iOS has just launched it in the
/// background: the saved login is restored and, with nothing queued, Play
/// starts the widget's album again.
enum WidgetPlaybackActions {

    @MainActor
    static func playPause(pausing: Bool) async throws {
        // Whatever happens, redraw the widget with the real state.
        defer { WidgetSnapshotWriter.shared.flush() }
        let session = AppSession.shared
        guard session.restore(), let player = session.player else {
            if pausing { return }
            throw PlayIntentError.notSignedIn
        }
        if pausing {
            player.pause()
            return
        }
        if player.currentTrack != nil {
            player.resume()
            return
        }
        // Nothing queued: the album the widget shows, from the start, as
        // Siri's Play Album plays it (downloads first, so offline works).
        guard let snapshot = WidgetSnapshotStore.read(), snapshot.albumId != nil else {
            throw PlayIntentError.albumNotFound
        }
        do {
            try await session.playAgain(snapshot)
        } catch AppSession.PlayError.notSignedIn {
            throw PlayIntentError.notSignedIn
        } catch {
            throw PlayIntentError.albumNotFound
        }
    }

    @MainActor
    static func next() async {
        AppSession.shared.restore()
        AppSession.shared.player?.skipToNext()
        WidgetSnapshotWriter.shared.flush()
    }

    @MainActor
    static func previous() async {
        AppSession.shared.restore()
        AppSession.shared.player?.skipToPrevious()
        WidgetSnapshotWriter.shared.flush()
    }
}
