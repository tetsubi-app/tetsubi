// MARK: - WidgetPlaybackActions — the widget extension's side (never runs)

// The buttons' intents (PlaybackIntents.swift) are `AudioPlaybackIntent`s,
// so iOS performs them in the app, which has its own
// `WidgetPlaybackActions` that drives the player. This copy only lets the
// widget extension compile the intents without the player code.
enum WidgetPlaybackActions {
    static func playPause(pausing: Bool) async throws {}
    static func next() async {}
    static func previous() async {}
}
