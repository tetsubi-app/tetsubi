// MARK: - Tetsubi's home-screen widget

import WidgetKit
import SwiftUI
import TetsubiWidgetData

@main
struct TetsubiWidgetBundle: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
    }
}

/// The playing (or last played) track with play/pause, in two sizes.
/// The app writes a snapshot whenever it changes and asks WidgetKit to
/// redraw; the widget only reads it (it never talks to the server).
struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetShared.kind, provider: NowPlayingProvider()) { entry in
            NowPlayingWidgetView(entry: entry)
        }
        .configurationDisplayName("Now Playing")
        .description("What's playing in Tetsubi, with play and pause.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

/// One drawing of the widget.
struct NowPlayingEntry: TimelineEntry {
    let date: Date
    let display: WidgetDisplay
    /// The album art (a small JPEG the app saved), if any.
    let art: UIImage?
}

struct NowPlayingProvider: TimelineProvider {

    /// Shown while the widget first loads: a track with no art.
    func placeholder(in context: Context) -> NowPlayingEntry {
        NowPlayingEntry(date: Date(), display: .track(.init(
            title: "Tetsubi", artist: "", album: "", isPlaying: false,
            showsSkips: true, accent: nil, artFile: nil)), art: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (NowPlayingEntry) -> Void) {
        completion(entry(for: WidgetSnapshotStore.read(), at: Date()))
    }

    /// Now, plus (while playing) the end of the track: if the app was
    /// closed nothing rewrites the snapshot, so the widget turns its Pause
    /// back into Play then and drops the finish time. While paused, one
    /// entry each minute (for up to an hour) so the finish time keeps up
    /// with the clock. The app reloads the timeline on every change.
    func getTimeline(in context: Context, completion: @escaping (Timeline<NowPlayingEntry>) -> Void) {
        let snapshot = WidgetSnapshotStore.read()
        let dates = WidgetDisplay.timelineDates(for: snapshot, now: Date())
        // The art is read once and shared by every entry.
        let art = artImage(for: WidgetDisplay.decide(snapshot, now: dates[0]))
        let entries = dates.map { date in
            NowPlayingEntry(date: date, display: WidgetDisplay.decide(snapshot, now: date), art: art)
        }
        completion(Timeline(entries: entries, policy: .never))
    }

    private func entry(for snapshot: WidgetSnapshot?, at date: Date) -> NowPlayingEntry {
        let display = WidgetDisplay.decide(snapshot, now: date)
        return NowPlayingEntry(date: date, display: display, art: artImage(for: display))
    }

    private func artImage(for display: WidgetDisplay) -> UIImage? {
        guard case .track(let track) = display, let url = WidgetSnapshotStore.artURL(track.artFile) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }
}
