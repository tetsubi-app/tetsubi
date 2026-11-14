// MARK: - The widget's buttons: play/pause, next, previous

import AppIntents

// This file is in both the app and the widget extension. The widget needs
// the intents to draw its buttons; because they are `AudioPlaybackIntent`s
// and the app has them too, iOS runs them in the app's process (launching
// it in the background if it was closed), where they can start audio.
// The work is done by `WidgetPlaybackActions`: the app's version drives
// the player; the widget's version is never called.

/// Play or Pause, as the widget's button showed it.
struct TogglePlaybackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play or Pause"
    static let description = IntentDescription("Plays or pauses the music in Tetsubi.")
    /// Only for the widget, not offered in Shortcuts.
    static let isDiscoverable = false

    /// True when the button showed Pause. The button does what it shows,
    /// even if the widget was a moment behind the app.
    @Parameter(title: "Pause")
    var pause: Bool

    init() {}

    init(pause: Bool) {
        self.pause = pause
    }

    func perform() async throws -> some IntentResult {
        try await WidgetPlaybackActions.playPause(pausing: pause)
        return .result()
    }
}

/// Next track (does nothing in Maniac Mode: the player ignores skips).
struct NextTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Next Track"
    static let description = IntentDescription("Skips to the next track in Tetsubi.")
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await WidgetPlaybackActions.next()
        return .result()
    }
}

/// Previous track, or the start of this one (does nothing in Maniac Mode).
struct PreviousTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Previous Track"
    static let description = IntentDescription("Goes back a track in Tetsubi.")
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await WidgetPlaybackActions.previous()
        return .result()
    }
}
