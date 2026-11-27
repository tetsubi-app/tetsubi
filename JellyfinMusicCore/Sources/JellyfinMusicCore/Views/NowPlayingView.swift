// MARK: - NowPlayingView — full-screen now-playing with seek + controls

import SwiftUI

/// A full-screen "now playing" view. Shown as a sheet from the
/// `NowPlayingBarView` when the user taps it.
///
/// Contains:
///   - large album art (loaded via `AuthenticatedImage`)
///   - title / artist / album / year
///   - a seek slider (with `elapsed / total` time labels)
///   - prev / play-pause / next
///   - shuffle + repeat toggles
///   - the queue (`NowPlayingQueue`): reorder, remove, clear, tap to play
///   - "up" button to dismiss the sheet
public struct NowPlayingView: View {

    // MARK: Inputs
    let manager: PlayerManager
    /// Called when the user presses the up button to dismiss the sheet.
    let onDismiss: () -> Void
    /// The artist line: close Now Playing and show this artist's albums.
    /// Nil: the line is plain text.
    let onShowArtist: ((JellyfinArtist) -> Void)?

    /// A name-only album artist looked up on the server, for the track
    /// whose `artistLookupKey` is `key`; `artist` nil if there's no match.
    @State private var foundArtist: (key: String, artist: JellyfinArtist?)?

    /// Show the spectrum bars under the artwork. Remembered between launches.
    @AppStorage("nowPlaying.showVisualiser") private var showVisualiser = false

    /// The info line shows the format and bitrate ("ALAC 954 kbps") rather
    /// than the sample rate ("44.1kHz/16-bit"). Remembered between launches.
    @AppStorage("nowPlaying.qualityDetail") private var showQualityDetail = false

    /// A vivid colour from the album art that stands out against its
    /// main colour, used for the
    /// progress line. Nil until worked out, or if the art has none.
    @State private var artAccent: Color?

    // MARK: Init

    public init(manager: PlayerManager,
                onShowArtist: ((JellyfinArtist) -> Void)? = nil,
                onDismiss: @escaping () -> Void) {
        self.manager = manager
        self.onShowArtist = onShowArtist
        self.onDismiss = onDismiss
    }

    // MARK: Body

    public var body: some View {
        // Wide (an unfolded Duo, an iPad, a wide Mac window): cover and info on the
        // left, the track list on the right. Otherwise one scrolling column.
        // Everything here is sized by `s`, Now Playing's own scale (never the
        // Mac's `UIScale`): 1 on one column, bigger on a big wide screen.
        GeometryReader { geo in
            let wide = Self.isWide(width: geo.size.width, height: geo.size.height)
            let s = Self.scale(width: geo.size.width, height: geo.size.height, wide: wide)
            Group {
                if wide {
                    wideLayout(size: geo.size, s)
                } else {
                    narrowLayout(s)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .overlay(alignment: .topTrailing) { dismissButton(s) }
            .overlay(alignment: .topLeading) { moreMenu(s) }
        }
        .background(AppBackgroundView())
        .foregroundStyle(.white)
        .task(id: manager.currentTrack?.artKey) { await loadArtAccent() }
        .task(id: artistLookupTaskID) { await lookUpArtist() }
        // The sheet and its notice show here while Now Playing is in front.
        .playlistEventsHost(isActive: true)
        #if os(iOS)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        #endif
    }

    // MARK: - Corner buttons

    private func dismissButton(_ s: CGFloat) -> some View {
        Button(action: onDismiss) {
            Image(systemName: "chevron.down")
                .font(.plex(.body, scale: s))
                .imageScale(.large)
                .padding(8 * s)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .padding(.top, 20 * s)
        .padding(.trailing, 20 * s)
        .accessibilityIdentifier("NowPlayingView.dismiss")
    }

    @ViewBuilder
    private func moreMenu(_ s: CGFloat) -> some View {
        // A song played from a download has no server ID to add.
        if let track = manager.currentTrack, track.localURL == nil {
            Menu {
                Button {
                    PlaylistEvents.shared.add(trackIds: [track.id])
                } label: {
                    Label("Add to Playlist…", systemImage: "text.badge.plus")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.plex(.body, scale: s))
                    .imageScale(.large)
                    .frame(width: 20 * s, height: 20 * s)
                    .padding(8 * s)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .padding(.top, 20 * s)
            .padding(.leading, 20 * s)
            .accessibilityLabel("More")
            .accessibilityIdentifier("NowPlayingView.moreMenu")
        }
    }

    // MARK: - Scale

    /// Now Playing's own size factor `s`. Two columns on a big screen get bigger
    /// type, buttons, spacing and cover; one column (phones, narrow windows) is
    /// always 1. Kotlin's `NowPlayingLayout.scale(width, height)` is the same (it works out "wide" itself).
    ///
    /// `s = min(width / 800, height / 600)`, clamped to 1.0…1.6 and rounded down
    /// to 0.05 so small resizes don't re-lay out. On the Mac it takes the place of
    /// `UIScale` here (never both), and is measured from the sheet (the Now Playing
    /// area), not the window.
    static let scaleBaseWidth: CGFloat = 800
    static let scaleBaseHeight: CGFloat = 600
    static let scaleMax: CGFloat = 1.6
    static let scaleStep: CGFloat = 0.05

    static func scale(width: CGFloat, height: CGFloat, wide: Bool) -> CGFloat {
        guard wide, width > 0, height > 0 else { return 1.0 }
        let raw = min(width / scaleBaseWidth, height / scaleBaseHeight)
        let clamped = min(max(raw, 1.0), scaleMax)
        return (clamped / scaleStep + 1e-6).rounded(.down) * scaleStep
    }

    // MARK: - Layouts

    /// Size from which the two-column layout is used, in points (on the Mac too:
    /// Now Playing doesn't use `UIScale`). The width takes in an unfolded Duo
    /// (about 670 pt across), an iPad and a Mac window; the height keeps an iPhone
    /// on its side (about 400 pt tall) on one column, where two would leave the
    /// controls off screen.
    static let wideMinimum: CGFloat = 640
    static let wideMinimumHeight: CGFloat = 520

    static func isWide(width: CGFloat, height: CGFloat) -> Bool {
        width >= wideMinimum && height >= wideMinimumHeight
    }

    /// Mac sheet: how wide it opens. The two columns when the window has room
    /// (leaving a margin each side), else the narrow card it always was.
    /// `s` is Now Playing's scale for that window.
    static func macSheetIdealWidth(windowWidth: CGFloat, s: CGFloat) -> CGFloat {
        let room = min(windowWidth - 160, 980 * s)
        return room >= wideMinimum ? room : 460
    }

    /// The cover in the two-column layout: as big as fits, so the title, progress
    /// and buttons below it are all on screen without scrolling. The rest of that
    /// column is about 430 pt (510 with the visualiser) at scale 1.
    static func wideArtSize(columnWidth: CGFloat, height: CGFloat, visualiser: Bool, s: CGFloat) -> CGFloat {
        let others = (visualiser ? 510 : 430) * s
        let byWidth = min(columnWidth - 56 * s, 360 * s)
        return max(140 * s, min(byWidth, height - others))
    }

    /// The left column's width in the two-column layout.
    static func wideLeftColumn(width: CGFloat, s: CGFloat) -> CGFloat {
        min(max(width * 0.46, 320 * s), 520 * s)
    }

    /// Mac sheet: how tall it opens. Most of the window, up to 880 pt at scale 1.
    static func macSheetIdealHeight(windowHeight: CGFloat, s: CGFloat) -> CGFloat {
        let room = min(windowHeight - 80, 880 * s)
        return max(room, 700)
    }

    /// The single column: everything in one scrolling list, the queue last
    /// (a `List`, so its rows can be dragged and swiped).
    private func narrowLayout(_ s: CGFloat) -> some View {
        List {
            VStack(spacing: 16 * s) {
                artSection(size: 220 * s, s)
                visualiserSection(s)
                titleSection(s)
                seekSection(s)
                controlsSection(s)
                togglesSection(s)
            }
            .listRowInsets(EdgeInsets(top: 28 * s, leading: 20 * s, bottom: 0, trailing: 20 * s))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .moveDisabled(true)

            NowPlayingQueue(manager: manager, s: s)
        }
        .nowPlayingList(bottom: 44 * s)
    }

    /// Two columns, each scrolling on its own. The top padding keeps both
    /// clear of the More and dismiss buttons in the corners.
    private func wideLayout(size: CGSize, _ s: CGFloat) -> some View {
        let left = Self.wideLeftColumn(width: size.width, s: s)
        let art = Self.wideArtSize(columnWidth: left, height: size.height, visualiser: showVisualiser, s: s)
        return HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(spacing: 16 * s) {
                    artSection(size: art, s)
                    visualiserSection(s)
                    titleSection(s)
                    seekSection(s)
                    controlsSection(s)
                    togglesSection(s)
                }
                .padding(.horizontal, 28 * s)
                .padding(.top, 56 * s)
                .padding(.bottom, 24 * s)
            }
            .frame(width: left)

            List {
                NowPlayingQueue(manager: manager, s: s)
            }
            .nowPlayingList(top: 32 * s, bottom: 24 * s)
        }
    }

    @ViewBuilder
    private func visualiserSection(_ s: CGFloat) -> some View {
        if showVisualiser {
            SpectrumView(isPlaying: manager.isPlaying, tint: seekTint)
                .frame(height: 64 * s)
                .padding(.horizontal, 8 * s)
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func artSection(size: CGFloat, _ s: CGFloat) -> some View {
        if let track = manager.currentTrack {
            AlbumArtView(
                request: manager.artRequest(for: track, size: 600),
                size: size, cornerRadius: 16 * s
            )
        } else {
            AlbumArtView(request: nil, size: size, cornerRadius: 16 * s)
        }
    }

    private func titleSection(_ s: CGFloat) -> some View {
        VStack(spacing: 6 * s) {
            if let track = manager.currentTrack {
                Text(track.title)
                    .font(.plex(.title2, scale: s).bold())
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                artistLine(track, s)
                Text(track.album)
                    .font(.plex(.subheadline, scale: s))
                    .foregroundStyle(Color.albumYearText)
                    .lineLimit(1)
                if manager.queueLength > 0 {
                    // One line: album duration, then the finish time.
                    // Re-evaluated periodically so the estimate keeps up while paused.
                    // With more than this album queued: "Album ends 13:02 · Queue
                    // ends 15:40", dropping the album's end when it doesn't fit.
                    TimelineView(.periodic(from: .now, by: 15)) { context in
                        let variants = manager.endTimesVariants(now: context.date) {
                            $0.formatted(date: .omitted, time: .shortened)
                        }
                        ViewThatFits(in: .horizontal) {
                            ForEach(variants, id: \.self) { ends in
                                infoLine(track, ends: ends, s)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            infoLine(track, ends: variants.last ?? "", s)
                                .minimumScaleFactor(0.8)
                        }
                        .font(.plex(.caption, scale: s))
                        .foregroundStyle(.white.opacity(0.5))
                        .lineLimit(1)
                    }
                }
                if let message = manager.errorMessage {
                    PlaybackErrorText(message: message, scale: s)
                }
            } else if manager.isLoading {
                // Album Play opens the sheet before a streamed album's tracks arrive.
                ProgressView()
                Text("Loading…")
                    .font(.plex(.title3, scale: s))
                    .foregroundStyle(.secondary)
            } else if let message = manager.errorMessage {
                PlaybackErrorText(message: message, scale: s)
            } else {
                Text("Nothing playing")
                    .font(.plex(.title3, scale: s))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    /// Quality, the queue's length and the end times, on one line.
    private func infoLine(_ track: PlaybackTrack, ends: String, _ s: CGFloat) -> some View {
        HStack(spacing: 16 * s) {
            qualityText(for: track)
            Text("Duration: \(Self.formatAlbumDuration(manager.queueLength))")
            Text(ends)
        }
    }

    /// The artist under the title; when it can (`ArtistLink`), a tap closes
    /// Now Playing and shows the artist's albums. It looks the same either way.
    @ViewBuilder
    private func artistLine(_ track: PlaybackTrack, _ s: CGFloat) -> some View {
        let text = Text(track.artist)
            .font(.plex(.headline, scale: s))
            .foregroundStyle(Color.albumArtistText)
            .lineLimit(1)
        if let onShowArtist, let artist = artistLink(for: track) {
            Button { onShowArtist(artist) } label: { text }
                .artistLinkStyle(track.artist)
                .accessibilityIdentifier("NowPlayingView.artistLink")
        } else {
            text
        }
    }

    /// The artist the line opens: the track's album artist by ID, or the
    /// match found by name (`lookUpArtist`); nil: not tappable.
    private func artistLink(for track: PlaybackTrack) -> JellyfinArtist? {
        switch ArtistLink.resolve(name: track.artist, id: track.albumArtistId) {
        case .artist(let artist): return artist
        case .lookup:
            guard let found = foundArtist, found.key == Self.artistLookupKey(track) else { return nil }
            return found.artist
        case nil: return nil
        }
    }

    private static func artistLookupKey(_ track: PlaybackTrack) -> String {
        track.artist + "\u{1}" + (track.albumArtistId ?? "")
    }

    /// Changes when a name-only artist needs looking up (again on reconnecting).
    private var artistLookupTaskID: String? {
        guard let track = manager.currentTrack, onShowArtist != nil,
              case .lookup = ArtistLink.resolve(name: track.artist, id: track.albumArtistId) else { return nil }
        return Self.artistLookupKey(track) + (NetworkMonitor.shared.isConnected ? "\u{1}online" : "")
    }

    private func lookUpArtist() async {
        guard artistLookupTaskID != nil, let track = manager.currentTrack else { return }
        let key = Self.artistLookupKey(track)
        if let found = foundArtist, found.key == key, found.artist != nil { return }
        let artist = await manager.albumArtistLink(for: track)
        guard !Task.isCancelled else { return }
        foundArtist = (key, artist)
    }

    /// The info line's quality: the sample rate, or the format and bitrate
    /// after a tap (tap again to go back). Without a sample rate, just the
    /// format and bitrate, and no toggle.
    @ViewBuilder
    private func qualityText(for track: PlaybackTrack) -> some View {
        if let rate = track.sampleRateLabel, let detail = track.qualityLabel {
            let shown = showQualityDetail ? detail : rate
            Button {
                showQualityDetail.toggle()
            } label: {
                Text(shown)
            }
            .buttonStyle(QualityToggleStyle(tint: seekTint ?? Color.accentColor))
            .accessibilityLabel(Text(shown))
            .accessibilityHint(showQualityDetail ? "Shows the sample rate and bit depth"
                                                 : "Shows the format and bitrate")
        } else if let quality = track.qualityLabel ?? track.sampleRateLabel {
            Text(quality)
        }
    }

    /// Album length as `47m 12s`, or `1h 45m 05s` from an hour up.
    static func formatAlbumDuration(_ t: TimeInterval) -> String {
        let total = max(0, Int(t.rounded()))
        let h = total / 3600, m = (total % 3600) / 60, sec = total % 60
        return h > 0 ? String(format: "%dh %02dm %02ds", h, m, sec) : String(format: "%dm %02ds", m, sec)
    }

    /// Progress line colour: picked from the album art (nil = the usual accent).
    private var seekTint: Color? { artAccent }

    /// Works out `artAccent` from the current album's art (already cached
    /// by the artwork view, so normally no extra download).
    private func loadArtAccent() async {
        let accent = await Self.artAccent(for: manager)
        guard !Task.isCancelled else { return }
        withAnimation(.easeInOut(duration: 0.3)) { artAccent = accent }
    }

    /// The key colour of the playing track's art (nil if it has none): the
    /// progress line and active buttons here, and the mini player's line.
    static func artAccent(for manager: PlayerManager) async -> Color? {
        guard let track = manager.currentTrack,
              let request = manager.artRequest(for: track, size: 600),
              let image = await ImageCache.load(request) else { return nil }
        let rgb = await Task.detached(priority: .utility) { ArtPalette.accentColor(of: image) }.value
        return rgb.map { Color(.sRGB, red: $0.red, green: $0.green, blue: $0.blue) }
    }

    private func seekSection(_ s: CGFloat) -> some View {
        VStack(spacing: 4 * s) {
            if manager.maniacMode {
                // No scrubbing: a plain progress bar.
                ProgressView(value: min(manager.position / max(manager.duration, 1), 1.0))
                    .progressViewStyle(.linear)
                    .tint(seekTint ?? Color.accentColor)
                    .padding(.vertical, 10 * s)
            } else {
            Slider(
                value: Binding(
                    get: {
                        let total = max(manager.duration, 1)
                        return min(manager.position / total, 1.0)
                    },
                    set: { p in
                        manager.seek(toProgress: p)
                    }
                ),
                in: 0...1
            )
            .tint(seekTint)
            }
            HStack {
                Text(Self.format(manager.position))
                    .font(.plex(.caption2, scale: s))
                    .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Text(Self.format(manager.duration))
                    .font(.plex(.caption2, scale: s))
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
    }

    private func controlsSection(_ s: CGFloat) -> some View {
        HStack(spacing: 28 * s) {
            if !manager.maniacMode {
            Button {
                manager.skipToPrevious()
            } label: {
                Image(systemName: "backward.fill")
                    .font(.plex(.title, scale: s))
                    .foregroundStyle(.white)
            }
            .accessibilityIdentifier("NowPlayingView.previous")
            }

            Button {
                manager.togglePlayPause()
            } label: {
                Group {
                    // Starting (loading, no sound yet): a spinner in the same box; a press does nothing.
                    if manager.isStarting {
                        ProgressView().tint(.white)
                            .accessibilityLabel("Loading")
                    } else {
                        Image(systemName: manager.isPlaying ? "pause.fill" : "play.fill")
                            .font(.plex(.largeTitle, scale: s))
                            .foregroundStyle(.white)
                    }
                }
                // A fixed box: the play and pause symbols differ in size, and the
                // row (and everything below it) shifted when they swapped.
                .frame(width: 52 * s, height: 52 * s)
                .contentShape(Rectangle())
            }
            .accessibilityIdentifier("NowPlayingView.playPause")

            if !manager.maniacMode {
            Button {
                manager.skipToNext()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.plex(.title, scale: s))
                    .foregroundStyle(.white)
            }
            .accessibilityIdentifier("NowPlayingView.next")
            }
        }
        .buttonStyle(.plain)
        .padding(.vertical, 12 * s)
        .frame(maxWidth: .infinity)
    }

    private func togglesSection(_ s: CGFloat) -> some View {
        HStack(spacing: 32 * s) {
            if !manager.maniacMode {
            Button {
                manager.toggleShuffle()
            } label: {
                VStack(spacing: 2 * s) {
                    Image(systemName: "shuffle")
                        .font(.plex(.title3, scale: s))
                        .foregroundStyle(manager.shuffle ? (seekTint ?? Color.accentColor) : .white.opacity(0.6))
                    Text("Shuffle")
                        .font(.plex(.caption2, scale: s))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("NowPlayingView.shuffle")

            Button {
                manager.cycleRepeatMode()
            } label: {
                VStack(spacing: 2 * s) {
                    Image(systemName: nowRepeatIcon)
                        .font(.plex(.title3, scale: s))
                        .foregroundStyle(repeatActive ? (seekTint ?? Color.accentColor) : .white.opacity(0.6))
                    Text(nowRepeatLabel)
                        .font(.plex(.caption2, scale: s))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("NowPlayingView.repeat")
            }

            Button {
                showVisualiser.toggle()
            } label: {
                VStack(spacing: 2 * s) {
                    Image(systemName: "waveform")
                        .font(.plex(.title3, scale: s))
                        .foregroundStyle(showVisualiser ? (seekTint ?? Color.accentColor) : .white.opacity(0.6))
                    Text("Visualiser")
                        .font(.plex(.caption2, scale: s))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("NowPlayingView.visualiser")

            VStack(spacing: 2 * s) {
                AirPlayButton(player: manager.outputPlayer, activeTint: seekTint ?? .accentColor)
                    .frame(width: 30 * s, height: 24 * s)
                Text("AirPlay")
                    .font(.plex(.caption2, scale: s))
                    .foregroundStyle(.white.opacity(0.7))
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("NowPlayingView.airPlay")
        }
        .padding(.top, 4 * s)
    }

    // MARK: - Helpers

    private var nowRepeatIcon: String {
        switch manager.repeatMode {
        case .off:  "repeat"
        case .one:  "repeat.1"
        case .all:  "repeat"
        }
    }

    private var nowRepeatLabel: String {
        switch manager.repeatMode {
        case .off:  "Off"
        case .one:  "1"
        case .all:  "All"
        }
    }

    private var repeatActive: Bool { manager.repeatMode != .off }

    static func format(_ t: TimeInterval) -> String {
        let total = max(0, Int(t))
        let mm = total / 60
        let ss = total % 60
        return String(format: "%d:%02d", mm, ss)
    }
}
extension View {
    /// Now Playing's lists: plain, see-through, with room at the ends.
    func nowPlayingList(top: CGFloat = 0, bottom: CGFloat) -> some View {
        listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 1)
            .contentMargins(.top, top, for: .scrollContent)
            .contentMargins(.bottom, bottom, for: .scrollContent)
    }
}

/// A playback failure: what went wrong and what to do (in Now Playing).
struct PlaybackErrorText: View {
    let message: String
    /// Now Playing's scale (`NowPlayingView.scale`).
    var scale: CGFloat = 1

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(.plex(.footnote, scale: scale))
            .foregroundStyle(Color(red: 1, green: 0.55, blue: 0.55))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 4 * scale)
    }
}

/// The info line's quality text as a button: dim white like the rest of
/// the line, the art's key colour while hovered or pressed.
private struct QualityToggleStyle: ButtonStyle {
    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        QualityToggleLabel(configuration: configuration, tint: tint)
    }

    private struct QualityToggleLabel: View {
        let configuration: Configuration
        let tint: Color
        @State private var hovering = false

        var body: some View {
            configuration.label
                .foregroundStyle(hovering || configuration.isPressed ? AnyShapeStyle(tint)
                                                                    : AnyShapeStyle(.white.opacity(0.5)))
                .contentShape(Rectangle())
                .onHover { hovering = $0 }
        }
    }
}

#if os(macOS)
extension View {
    /// The Mac's Now Playing sheet: opens wide enough for two columns when the
    /// window allows, and can be resized.
    @ViewBuilder
    func nowPlayingSheetFrame() -> some View {
        let window = CGSize(width: UIScale.shared.windowWidth, height: UIScale.shared.windowHeight)
        // The window's scale sets how big the sheet may open (no loop: it comes
        // from the window, not the sheet). The content then works out its own
        // `s` from the sheet's actual size, e.g. 1100 × 760 window → 940 × 700
        // sheet → s 1.15.
        let s = NowPlayingView.scale(width: window.width, height: window.height, wide: true)
        // A narrow window (down to 360 wide) must not be forced wider by the sheet.
        let minWidth = window.width > 0 ? min(460, window.width) : 460
        let minHeight = window.height > 0 ? min(560, window.height) : 560
        let sized = frame(minWidth: minWidth,
                          idealWidth: max(minWidth, min(NowPlayingView.macSheetIdealWidth(windowWidth: window.width, s: s), window.width > 0 ? window.width : .infinity)),
                          maxWidth: .infinity,
                          minHeight: minHeight,
                          idealHeight: max(minHeight, min(NowPlayingView.macSheetIdealHeight(windowHeight: window.height, s: s), window.height > 0 ? window.height : .infinity)),
                          maxHeight: .infinity)
        // The default sheet sizing kept the width at about 470 whatever the
        // frame said; "fitted" takes the content's ideal size.
        if #available(macOS 15.0, *) {
            sized.presentationSizing(.fitted)
        } else {
            sized
        }
    }
}
#endif
