// MARK: - NowPlayingView — full-screen now-playing with seek + controls

import SwiftUI

/// A full-screen "now playing" view. Shown as a sheet from the
/// `NowPlayingBarView` when the user taps it (a full-screen cover on iPad).
///
/// Two layouts, chosen from the width it is shown at (`NowPlayingLayout`):
/// one column (compact: an iPhone, a narrow window) with the queue below the
/// controls, or two (the player on the left, the queue in a panel on the
/// right). Contains:
///   - close and ⋯ on top
///   - large album art (loaded via `AuthenticatedImage`)
///   - title / artist / album, the format and the queue's length
///   - a seek slider (with `elapsed / total` time labels)
///   - prev / play-pause / next
///   - round buttons: shuffle, repeat, visualiser, AirPlay
///   - the queue (`NowPlayingQueue`): reorder, remove, clear, tap to play
public struct NowPlayingView: View {

    // MARK: Inputs
    let manager: PlayerManager
    /// Called when the user presses the close button to dismiss the sheet.
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
        GeometryReader { geo in
            Group {
                if NowPlayingLayout.usesTwoColumns(width: geo.size.width, isPhone: AppRootView.isPhone) {
                    wideLayout(size: geo.size)
                } else {
                    compactLayout(size: geo.size)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .background(AppBackgroundView())
        .foregroundStyle(TetsubiColors.textPrimary)
        .task(id: manager.currentTrack?.artKey) { await loadArtAccent() }
        .task(id: artistLookupTaskID) { await lookUpArtist() }
        // The sheet and its notice show here while Now Playing is in front.
        .playlistEventsHost(isActive: true)
    }

    // MARK: - Layouts

    /// One column: close and ⋯ on top, everything else in one scrolling list,
    /// the queue last (a `List`, so its rows can be dragged and swiped).
    private func compactLayout(size: CGSize) -> some View {
        VStack(spacing: 0) {
            topBar
                .padding(.horizontal, NowPlayingLayout.compactMargin)
                .padding(.top, Spacing.s)
            List {
                VStack(spacing: Spacing.l) {
                    player(artSize: NowPlayingLayout.compactArtSize(width: size.width, height: size.height))
                }
                .frame(maxWidth: .infinity)
                .listRowInsets(EdgeInsets(top: Spacing.s, leading: NowPlayingLayout.compactMargin, bottom: 0,
                                          trailing: NowPlayingLayout.compactMargin))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .moveDisabled(true)

                NowPlayingQueue(manager: manager, showsHeader: true)
            }
            .nowPlayingList(bottom: Spacing.xl)
        }
    }

    /// Two columns, each scrolling on its own: the player (with close and ⋯
    /// on top) and the queue in a panel.
    private func wideLayout(size: CGSize) -> some View {
        let left = NowPlayingLayout.leftColumnWidth(width: size.width)
        let art = NowPlayingLayout.wideArtSize(columnWidth: left, height: size.height, visualiser: showVisualiser)
        return HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(spacing: Spacing.l) {
                    topBar
                    player(artSize: art)
                }
                .padding(.horizontal, Spacing.xxl)
                .padding(.top, Spacing.s)
                .padding(.bottom, Spacing.xl)
            }
            .frame(width: left)

            if manager.playQueue.currentEntry != nil {
                VStack(spacing: 0) {
                    NowPlayingQueueHeader(manager: manager)
                        .padding(.horizontal, Spacing.l)
                        .padding(.top, Spacing.m)
                        .padding(.bottom, Spacing.xs)
                    List {
                        NowPlayingQueue(manager: manager, showsHeader: false)
                    }
                    .nowPlayingList(bottom: Spacing.s)
                }
                .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
                .clipShape(RoundedRectangle(cornerRadius: Radii.panel))
                .padding(.top, Spacing.m)
                .padding(.trailing, Spacing.xl)
                .padding(.bottom, Spacing.l)
            } else {
                Color.clear
            }
        }
    }

    /// Art, titles, progress and buttons: the single column's top and the left column.
    @ViewBuilder
    private func player(artSize: CGFloat) -> some View {
        artSection(size: artSize)
        visualiserSection
        titleSection
        seekSection
        controlsSection
        togglesSection
    }

    // MARK: - Top bar

    /// ⌄ (close) on the left and ⋯ (Add to Playlist…) on the right.
    private var topBar: some View {
        HStack {
            RoundIconButton("Close Now Playing", systemImage: "chevron.down", size: 40, action: onDismiss)
                .accessibilityIdentifier("NowPlayingView.dismiss")
            Spacer(minLength: 0)
            moreMenu
        }
    }

    @ViewBuilder
    private var moreMenu: some View {
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
                    .font(.system(size: 18))
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .frame(width: 40, height: 40)
                    .background(TetsubiColors.surface, in: Circle())
                    .contentShape(Circle())
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.plain)
            .fixedSize()
            .accessibilityLabel("More")
            .accessibilityIdentifier("NowPlayingView.moreMenu")
        }
    }

    // MARK: - Visualiser

    @ViewBuilder
    private var visualiserSection: some View {
        if showVisualiser {
            SpectrumView(isPlaying: manager.isPlaying, tint: seekTint)
                .frame(height: 64)
                .padding(.horizontal, Spacing.s)
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func artSection(size: CGFloat) -> some View {
        if let track = manager.currentTrack {
            AlbumArtView(
                request: manager.artRequest(for: track, size: 800),
                size: size, cornerRadius: Radii.panel, placeholderColor: TetsubiColors.surface
            )
        } else {
            AlbumArtView(request: nil, size: size, cornerRadius: Radii.panel, placeholderColor: TetsubiColors.surface)
        }
    }

    private var titleSection: some View {
        VStack(spacing: 6) {
            if let track = manager.currentTrack {
                Text(track.title)
                    .font(TetsubiType.nowPlayingTitle)
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                artistLine(track)
                Text(track.album)
                    .font(TetsubiType.rowTitle.weight(.regular))
                    .foregroundStyle(TetsubiColors.textSecondary)
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
                                infoLine(track, ends: ends)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            infoLine(track, ends: variants.last ?? "")
                                .minimumScaleFactor(0.8)
                        }
                        .font(TetsubiType.caption)
                        .foregroundStyle(TetsubiColors.textSecondary)
                        .lineLimit(1)
                    }
                }
                if let message = manager.errorMessage {
                    PlaybackErrorText(message: message)
                }
            } else if manager.isLoading {
                // Album Play opens the sheet before a streamed album's tracks arrive.
                ProgressView()
                Text("Loading…")
                    .font(TetsubiType.sectionTitle)
                    .foregroundStyle(TetsubiColors.textSecondary)
            } else if let message = manager.errorMessage {
                PlaybackErrorText(message: message)
            } else {
                Text("Nothing playing")
                    .font(TetsubiType.sectionTitle)
                    .foregroundStyle(TetsubiColors.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    /// Quality, the queue's length and the end times, on one line.
    private func infoLine(_ track: PlaybackTrack, ends: String) -> some View {
        HStack(spacing: Spacing.l) {
            qualityText(for: track)
            Text("Duration: \(Self.formatAlbumDuration(manager.queueLength))")
            Text(ends)
        }
    }

    /// The artist under the title; when it can (`ArtistLink`), blue, and a tap
    /// closes Now Playing and shows the artist's albums.
    @ViewBuilder
    private func artistLine(_ track: PlaybackTrack) -> some View {
        let link = onShowArtist != nil ? artistLink(for: track) : nil
        let text = Text(track.artist)
            .font(TetsubiType.nowPlayingArtist)
            .foregroundStyle(link != nil ? TetsubiColors.blue : TetsubiColors.textSecondary)
            .lineLimit(1)
        if let onShowArtist, let artist = link {
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
            .buttonStyle(QualityToggleStyle(tint: seekTint ?? TetsubiColors.blue))
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

    private var seekSection: some View {
        VStack(spacing: Spacing.xs) {
            if manager.maniacMode {
                // No scrubbing: a plain progress bar.
                ProgressView(value: min(manager.position / max(manager.duration, 1), 1.0))
                    .progressViewStyle(.linear)
                    .tint(seekTint ?? TetsubiColors.blue)
                    .padding(.vertical, 10)
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
                .tint(seekTint ?? TetsubiColors.blue)
            }
            HStack {
                Text(Self.format(manager.position))
                    .font(TetsubiType.navLabel)
                    .foregroundStyle(TetsubiColors.textSecondary)
                Spacer()
                Text(Self.format(manager.duration))
                    .font(TetsubiType.navLabel)
                    .foregroundStyle(TetsubiColors.textSecondary)
            }
        }
    }

    /// Previous 56, play / pause 72, next 56, 24 apart.
    private var controlsSection: some View {
        HStack(spacing: Spacing.xl) {
            if !manager.maniacMode {
                Button {
                    manager.skipToPrevious()
                } label: {
                    Image(systemName: "backward.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(TetsubiColors.textPrimary)
                        .frame(width: 56, height: 56)
                        .contentShape(Circle())
                }
                .accessibilityLabel("Previous")
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
                            .font(.system(size: 44))
                            .foregroundStyle(TetsubiColors.textPrimary)
                    }
                }
                // A fixed box: the play and pause symbols differ in size, and the
                // row (and everything below it) shifted when they swapped.
                .frame(width: 72, height: 72)
                .contentShape(Circle())
            }
            .accessibilityLabel(manager.isPlaying ? "Pause" : "Play")
            .accessibilityIdentifier("NowPlayingView.playPause")

            if !manager.maniacMode {
                Button {
                    manager.skipToNext()
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(TetsubiColors.textPrimary)
                        .frame(width: 56, height: 56)
                        .contentShape(Circle())
                }
                .accessibilityLabel("Next")
                .accessibilityIdentifier("NowPlayingView.next")
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }

    /// Round 44 pt buttons: a blue tint and a blue icon when on. Shuffle and
    /// Repeat are hidden in Maniac Mode; the Visualiser and the output picker stay.
    private var togglesSection: some View {
        HStack(spacing: Spacing.l) {
            if !manager.maniacMode {
                RoundIconButton("Shuffle: \(manager.shuffle ? "On" : "Off")", systemImage: "shuffle",
                                on: manager.shuffle) { manager.toggleShuffle() }
                    .accessibilityIdentifier("NowPlayingView.shuffle")

                RoundIconButton("Repeat: \(Self.repeatLabel(manager.repeatMode))", systemImage: Self.repeatIcon(manager.repeatMode),
                                on: manager.repeatMode != .off) { manager.cycleRepeatMode() }
                    .accessibilityIdentifier("NowPlayingView.repeat")
            }

            RoundIconButton(showVisualiser ? "Visualiser, on" : "Visualiser, off", systemImage: "waveform",
                            on: showVisualiser) { showVisualiser.toggle() }
                .accessibilityIdentifier("NowPlayingView.visualiser")

            // The system output picker (AirPlay, speakers, headphones). Its icon turns blue
            // while audio goes somewhere other than this device.
            AirPlayButton(player: manager.outputPlayer, tint: TetsubiColors.textPrimary, activeTint: TetsubiColors.blue)
                .frame(width: 44, height: 44)
                .background(TetsubiColors.surface, in: Circle())
                .clipShape(Circle())
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Choose speakers or headphones")
                .accessibilityIdentifier("NowPlayingView.airPlay")
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Helpers

    static func repeatIcon(_ mode: RepeatMode) -> String {
        mode == .one ? "repeat.1" : "repeat"
    }

    static func repeatLabel(_ mode: RepeatMode) -> String {
        switch mode {
        case .off: "Off"
        case .one: "1"
        case .all: "All"
        }
    }

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

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(TetsubiType.control.weight(.regular))
            .foregroundStyle(TetsubiColors.red)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, Spacing.xs)
    }
}

/// The info line's quality text as a button: secondary like the rest of
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
                                                                    : AnyShapeStyle(TetsubiColors.textSecondary))
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
        // A narrow window (down to 360 wide) must not be forced wider by the sheet.
        let minWidth = window.width > 0 ? min(460, window.width) : 460
        let minHeight = window.height > 0 ? min(560, window.height) : 560
        let sized = frame(minWidth: minWidth,
                          idealWidth: max(minWidth, min(NowPlayingLayout.macSheetIdealWidth(windowWidth: window.width), window.width > 0 ? window.width : .infinity)),
                          maxWidth: .infinity,
                          minHeight: minHeight,
                          idealHeight: max(minHeight, min(NowPlayingLayout.macSheetIdealHeight(windowHeight: window.height), window.height > 0 ? window.height : .infinity)),
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
