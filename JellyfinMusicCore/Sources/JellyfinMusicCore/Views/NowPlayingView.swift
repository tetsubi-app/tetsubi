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
///   - "Up next": the tracks that will play after this one (tap to play)
///   - "up" button to dismiss the sheet
public struct NowPlayingView: View {

    // MARK: Inputs
    let manager: PlayerManager
    /// Called when the user presses the up button to dismiss the sheet.
    let onDismiss: () -> Void

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

    public init(manager: PlayerManager, onDismiss: @escaping () -> Void) {
        self.manager = manager
        self.onDismiss = onDismiss
    }

    // MARK: Body

    public var body: some View {
        ScrollView {
            VStack(spacing: 16.scaled) {
                artSection

                if showVisualiser {
                    SpectrumView(isPlaying: manager.isPlaying, tint: seekTint)
                        .frame(height: 64.scaled)
                        .padding(.horizontal, 8.scaled)
                }

                titleSection

                seekSection

                controlsSection

                togglesSection

                upNextSection
            }
            .padding(20.scaled)
            .padding(.top, 8.scaled)
            .padding(.bottom, 24.scaled)
        }
        .background(AppBackgroundView())
        .foregroundStyle(.white)
        .task(id: manager.currentTrack?.artKey) { await loadArtAccent() }
        .overlay(alignment: .topTrailing) {
            Button(action: onDismiss) {
                Image(systemName: "chevron.down")
                    .imageScale(.large)
                    .padding(8.scaled)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 20.scaled)
            .padding(.trailing, 20.scaled)
            .accessibilityIdentifier("NowPlayingView.dismiss")
        }
        .overlay(alignment: .topLeading) {
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
                        .imageScale(.large)
                        .frame(width: 20.scaled, height: 20.scaled)
                        .padding(8.scaled)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .padding(.top, 20.scaled)
                .padding(.leading, 20.scaled)
                .accessibilityLabel("More")
                .accessibilityIdentifier("NowPlayingView.moreMenu")
            }
        }
        // The sheet and its notice show here while Now Playing is in front.
        .playlistEventsHost(isActive: true)
        #if os(iOS)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        #endif
    }

    // MARK: - Sections

    @ViewBuilder
    private var artSection: some View {
        if let track = manager.currentTrack {
            AlbumArtView(
                request: manager.artRequest(for: track, size: 600),
                size: 220.scaled, cornerRadius: 16.scaled
            )
        } else {
            AlbumArtView(request: nil, size: 220.scaled, cornerRadius: 16.scaled)
        }
    }

    private var titleSection: some View {
        VStack(spacing: 6.scaled) {
            if let track = manager.currentTrack {
                Text(track.title)
                    .font(.plex(.title2).bold())
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                Text(track.artist)
                    .font(.plex(.headline))
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(1)
                Text(track.album)
                    .font(.plex(.subheadline))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
                if manager.albumLength > 0 {
                    // One line: album duration, then the finish time.
                    // Re-evaluated periodically so the estimate keeps up while paused.
                    TimelineView(.periodic(from: .now, by: 15)) { context in
                        let end = context.date.addingTimeInterval(manager.albumRemaining)
                        HStack(spacing: 16.scaled) {
                            qualityText(for: track)
                            Text("Duration: \(Self.formatAlbumDuration(manager.albumLength))")
                            Text("Finishes at: \(end.formatted(date: .omitted, time: .shortened))")
                        }
                        .font(.plex(.caption))
                        .foregroundStyle(.white.opacity(0.5))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    }
                }
                if let message = manager.errorMessage {
                    PlaybackErrorText(message: message)
                }
            } else if manager.isLoading {
                // Album Play opens the sheet before a streamed album's tracks arrive.
                ProgressView()
                Text("Loading…")
                    .font(.plex(.title3))
                    .foregroundStyle(.secondary)
            } else if let message = manager.errorMessage {
                PlaybackErrorText(message: message)
            } else {
                Text("Nothing playing")
                    .font(.plex(.title3))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
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

    private var seekSection: some View {
        VStack(spacing: 4) {
            if manager.maniacMode {
                // No scrubbing: a plain progress bar.
                ProgressView(value: min(manager.position / max(manager.duration, 1), 1.0))
                    .progressViewStyle(.linear)
                    .tint(seekTint ?? Color.accentColor)
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
            .tint(seekTint)
            }
            HStack {
                Text(Self.format(manager.position))
                    .font(.plex(.caption2))
                    .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Text(Self.format(manager.duration))
                    .font(.plex(.caption2))
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
    }

    private var controlsSection: some View {
        HStack(spacing: 28.scaled) {
            if !manager.maniacMode {
            Button {
                manager.skipToPrevious()
            } label: {
                Image(systemName: "backward.fill")
                    .font(.plex(.title))
                    .foregroundStyle(.white)
            }
            .accessibilityIdentifier("NowPlayingView.previous")
            }

            Button {
                manager.togglePlayPause()
            } label: {
                Image(systemName: manager.isPlaying ? "pause.fill" : "play.fill")
                    .font(.plex(.largeTitle))
                    .foregroundStyle(.white)
                    // A fixed box: the play and pause symbols differ in size, and the
                    // row (and everything below it) shifted when they swapped.
                    .frame(width: 52.scaled, height: 52.scaled)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("NowPlayingView.playPause")

            if !manager.maniacMode {
            Button {
                manager.skipToNext()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.plex(.title))
                    .foregroundStyle(.white)
            }
            .accessibilityIdentifier("NowPlayingView.next")
            }
        }
        .buttonStyle(.plain)
        .padding(.vertical, 12.scaled)
        .frame(maxWidth: .infinity)
    }

    private var togglesSection: some View {
        HStack(spacing: 32.scaled) {
            if !manager.maniacMode {
            Button {
                manager.toggleShuffle()
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: "shuffle")
                        .font(.plex(.title3))
                        .foregroundStyle(manager.shuffle ? (seekTint ?? Color.accentColor) : .white.opacity(0.6))
                    Text("Shuffle")
                        .font(.plex(.caption2))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("NowPlayingView.shuffle")

            Button {
                manager.cycleRepeatMode()
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: nowRepeatIcon)
                        .font(.plex(.title3))
                        .foregroundStyle(repeatActive ? (seekTint ?? Color.accentColor) : .white.opacity(0.6))
                    Text(nowRepeatLabel)
                        .font(.plex(.caption2))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("NowPlayingView.repeat")
            }

            Button {
                showVisualiser.toggle()
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: "waveform")
                        .font(.plex(.title3))
                        .foregroundStyle(showVisualiser ? (seekTint ?? Color.accentColor) : .white.opacity(0.6))
                    Text("Visualiser")
                        .font(.plex(.caption2))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("NowPlayingView.visualiser")

            VStack(spacing: 2) {
                AirPlayButton(player: manager.outputPlayer, activeTint: seekTint ?? .accentColor)
                    .frame(width: 30.scaled, height: 24.scaled)
                Text("AirPlay")
                    .font(.plex(.caption2))
                    .foregroundStyle(.white.opacity(0.7))
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("NowPlayingView.airPlay")
        }
        .padding(.top, 4)
    }

    // MARK: - Up next

    @ViewBuilder
    private var upNextSection: some View {
        let upcoming = manager.upNext
        if manager.currentTrack != nil {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Up next")
                        .font(.plex(.headline))
                    Spacer()
                    if manager.repeatMode == .one {
                        Text("Repeating this track")
                            .font(.plex(.caption))
                            .foregroundStyle(.white.opacity(0.5))
                    } else if manager.repeatMode == .all, !upcoming.isEmpty {
                        Text("Then repeats")
                            .font(.plex(.caption))
                            .foregroundStyle(.white.opacity(0.5))
                    }
                }
                .padding(.bottom, 8)

                if upcoming.isEmpty {
                    Text("Last track")
                        .font(.plex(.subheadline))
                        .foregroundStyle(.white.opacity(0.5))
                        .padding(.vertical, 6)
                } else {
                    ForEach(upcoming, id: \.index) { item in
                        upNextRow(item.track, onTap: manager.maniacMode ? nil : {
                            manager.skip(toQueueIndex: item.index)
                        })
                        if item.index != upcoming.last?.index {
                            Divider().overlay(Color.white.opacity(0.1))
                        }
                    }
                }
            }
            .padding(.top, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("NowPlayingView.upNext")
        }
    }

    /// `onTap` nil (Maniac Mode): the row isn't a button.
    private func upNextRow(_ track: PlaybackTrack, onTap: (() -> Void)?) -> some View {
        Button { onTap?() } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(.plex(.subheadline))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    // Artist only when it differs (compilations, mixed queues).
                    if track.artist != manager.currentTrack?.artist, !track.artist.isEmpty {
                        Text(track.artist)
                            .font(.plex(.caption))
                            .foregroundStyle(.white.opacity(0.6))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                if track.duration > 0 {
                    Text(Self.format(track.duration))
                        .font(.plex(.caption).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.5))
                }
            }
            .padding(.vertical, 8.scaled)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(onTap == nil)
        .accessibilityHint(onTap == nil ? "" : "Plays this track")
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
/// A playback failure: what went wrong and what to do (in Now Playing).
struct PlaybackErrorText: View {
    let message: String

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(.plex(.footnote))
            .foregroundStyle(Color(red: 1, green: 0.55, blue: 0.55))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 4)
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
