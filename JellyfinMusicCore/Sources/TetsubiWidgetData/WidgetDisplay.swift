// MARK: - WidgetDisplay — what the widget shows for a snapshot

import Foundation

/// The widget's three faces, worked out from the last snapshot. Kept apart
/// from the views so the decision can be tested.
public enum WidgetDisplay: Equatable, Sendable {
    /// Signed out: the Tetsubi mark and "Open Tetsubi to sign in".
    case signedOut
    /// Nothing played yet: the Tetsubi mark and "Nothing playing".
    case nothingPlaying
    /// The playing track, or the last one played (paused or stopped).
    case track(Track)

    public struct Track: Equatable, Sendable {
        public let title: String
        public let artist: String
        public let album: String
        /// Whether to show Pause (true) or Play.
        public let isPlaying: Bool
        /// Previous and next are hidden in Maniac Mode.
        public let showsSkips: Bool
        public let accent: WidgetSnapshot.RGB?
        public let artFile: String?
        /// The queue's length in seconds; 0 hides the duration line.
        public let queueSeconds: Int
        /// When the queue will finish, or nil to show only the duration.
        public let finishesAt: Date?

        public init(title: String, artist: String, album: String, isPlaying: Bool,
                    showsSkips: Bool, accent: WidgetSnapshot.RGB?, artFile: String?,
                    queueSeconds: Int = 0, finishesAt: Date? = nil) {
            self.title = title
            self.artist = artist
            self.album = album
            self.isPlaying = isPlaying
            self.showsSkips = showsSkips
            self.accent = accent
            self.artFile = artFile
            self.queueSeconds = queueSeconds
            self.finishesAt = queueSeconds > 0 ? finishesAt : nil
        }

        /// "Duration: 51m 06s", or nil when the length isn't known.
        public var durationText: String? {
            queueSeconds > 0 ? "Duration: \(WidgetDisplay.formatDuration(queueSeconds))" : nil
        }

        /// "Finishes at: 13:42" in the device's 12- or 24-hour time, or nil.
        public func finishText(locale: Locale = .autoupdatingCurrent,
                               timeZone: TimeZone = .autoupdatingCurrent) -> String? {
            guard let finishesAt else { return nil }
            var style = Date.FormatStyle(date: .omitted, time: .shortened)
            style.locale = locale
            style.timeZone = timeZone
            return "Finishes at: \(finishesAt.formatted(style))"
        }

        /// The medium widget's line under the album, as Now Playing's:
        /// "Duration: 51m 06s   Finishes at: 13:42", just the duration once
        /// the finish isn't known, nil when the length isn't known.
        public func timesLine(separator: String = "   ", locale: Locale = .autoupdatingCurrent,
                              timeZone: TimeZone = .autoupdatingCurrent) -> String? {
            guard let durationText else { return nil }
            return [durationText, finishText(locale: locale, timeZone: timeZone)]
                .compactMap { $0 }.joined(separator: separator)
        }

        /// What VoiceOver reads for the widget: "<title> by <artist>".
        public var accessibilityLabel: String {
            artist.isEmpty ? title : "\(title) by \(artist)"
        }
    }

    /// Decides what to show at `now`. No snapshot yet (the app hasn't run
    /// since the widget was added) counts as nothing played.
    public static func decide(_ snapshot: WidgetSnapshot?, now: Date = Date()) -> WidgetDisplay {
        guard let snapshot else { return .nothingPlaying }
        guard snapshot.signedIn else { return .signedOut }
        guard let title = snapshot.trackTitle, snapshot.albumId != nil else { return .nothingPlaying }
        // A Pause button for music that has stopped (the app was closed
        // while playing) would be wrong: after the track's end, show Play.
        let stillPlaying = snapshot.isPlaying && (snapshot.playingUntil.map { now < $0 } ?? true)
        return .track(Track(
            title: title,
            artist: snapshot.artist ?? "",
            album: snapshot.album ?? "",
            isPlaying: stillPlaying,
            showsSkips: !snapshot.maniacMode,
            accent: snapshot.accentRGB,
            artFile: snapshot.artFile,
            queueSeconds: max(0, snapshot.queueSeconds),
            finishesAt: finish(snapshot, stillPlaying: stillPlaying, now: now)
        ))
    }

    /// While paused the finish time moves with the clock, for up to this
    /// long after the snapshot; after that (the app may have been closed
    /// while paused) only the duration is shown.
    public static let pausedFinishWindow: TimeInterval = 60 * 60

    /// Playing: `updatedAt` plus what was left then. Paused: now plus
    /// what's left. Nil (duration only) once a playing snapshot is out of
    /// date (the app was closed: see `playingUntil`) or a paused one is
    /// over `pausedFinishWindow` old.
    static func finish(_ snapshot: WidgetSnapshot, stillPlaying: Bool, now: Date) -> Date? {
        guard snapshot.queueSeconds > 0 else { return nil }
        let left = TimeInterval(max(0, snapshot.remainingSeconds))
        if snapshot.isPlaying {
            return stillPlaying ? snapshot.playingFinish : nil
        }
        guard now.timeIntervalSince(snapshot.updatedAt) < pausedFinishWindow else { return nil }
        return now.addingTimeInterval(left)
    }

    /// The queue's length as Now Playing writes it: `47m 12s`, or
    /// `1h 45m 05s` from an hour up.
    public static func formatDuration(_ seconds: Int) -> String {
        let total = max(0, seconds)
        let h = total / 3600, m = (total % 3600) / 60, sec = total % 60
        return h > 0 ? String(format: "%dh %02dm %02ds", h, m, sec) : String(format: "%dm %02ds", m, sec)
    }

    /// Where tapping the widget (outside its buttons) takes you.
    public var openURL: URL {
        if case .track = self { return WidgetShared.nowPlayingURL }
        return WidgetShared.homeURL
    }

    /// When the widget should be drawn, starting with `now`. Playing: also
    /// at the end of the track, so it can switch to Play (and drop the
    /// finish time) if the app was closed. Paused with a finish time
    /// shown: each time the shown minute changes, up to
    /// `pausedFinishWindow` after the snapshot, then once more without it.
    public static func timelineDates(for snapshot: WidgetSnapshot?, now: Date) -> [Date] {
        var dates = [now]
        guard let snapshot, snapshot.signedIn, snapshot.trackTitle != nil else { return dates }
        if snapshot.isPlaying {
            if let until = snapshot.playingUntil, until > now { dates.append(until) }
            return dates
        }
        guard snapshot.queueSeconds > 0 else { return dates }
        let end = snapshot.updatedAt.addingTimeInterval(pausedFinishWindow)
        guard end > now else { return dates }
        // Finish = date + left, so the shown minute turns over when
        // date + left reaches a whole minute.
        let left = TimeInterval(max(0, snapshot.remainingSeconds))
        let finishNow = now.addingTimeInterval(left).timeIntervalSince1970
        var next = Date(timeIntervalSince1970: ((finishNow / 60).rounded(.down) + 1) * 60 - left)
        while next < end {
            dates.append(next)
            next = next.addingTimeInterval(60)
        }
        dates.append(end)
        return dates
    }
}
