// MARK: - DockedMiniPlayer — the mini player docked full width of the content area (docs/redesign.md section 2)

import SwiftUI

/// The mini player for the Mac and iPad: full width of the content area (not a
/// floating pill), 72 high on medium and expanded (64 compact), a 3 pt progress
/// line on its top edge. 44 art, title and artist · album, previous / play /
/// next; from medium up also a seek bar and the AirPlay (Cast) button.
/// Tapping the art or title opens Now Playing. Draws nothing without a track.
///
/// The iPhone keeps `NowPlayingBarView` (a bottom accessory on iOS 26.1, a
/// lozenge before).
public struct DockedMiniPlayer: View {
    let manager: PlayerManager
    @Environment(\.windowWidthClass) private var windowClass

    public init(manager: PlayerManager) { self.manager = manager }

    public var body: some View {
        if let track = manager.currentTrack {
            let wide = windowClass.isWide
            VStack(spacing: 0) {
                progressLine
                HStack(spacing: Spacing.m) {
                    Button { manager.isNowPlayingPresented = true } label: {
                        HStack(spacing: Spacing.m) {
                            AlbumArtView(request: manager.artRequest(for: track, size: 100),
                                         size: 44.scaled, cornerRadius: Radii.thumb)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(track.title)
                                    .font(TetsubiType.rowTitle).foregroundStyle(TetsubiColors.textPrimary).lineLimit(1)
                                if manager.lastError == .trackFailed {
                                    Text("Couldn't play \u{2014} press Play to try again")
                                        .font(TetsubiType.caption).foregroundStyle(TetsubiColors.red).lineLimit(1)
                                } else {
                                    Text(subtitle(track))
                                        .font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("NowPlayingBarView.open")
                    .accessibilityLabel("Now Playing, \(track.title)")

                    controls
                    if wide && !manager.maniacMode {
                        MiniSeekBar(manager: manager)
                            .frame(minWidth: 120, maxWidth: 360)
                    }
                    if wide {
                        AirPlayButton(player: manager.outputPlayer, activeTint: TetsubiColors.blue)
                            .frame(width: 32.scaled, height: 32.scaled)
                    }
                }
                .padding(.horizontal, windowClass.pageMargin)
                .frame(maxHeight: .infinity)
            }
            .frame(height: windowClass.miniPlayerHeight.scaled)
            .background { Color.appBackground.opacity(0.88); TetsubiColors.surface }
            .overlay(alignment: .top) { Hairline() }
        }
    }

    private func subtitle(_ track: PlaybackTrack) -> String {
        [track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " \u{00B7} ")
    }

    /// A 3 pt line over the top edge, blue to the current position.
    private var progressLine: some View {
        let known = manager.duration > 0
        let fraction = known ? min(1, max(0, manager.position / manager.duration)) : 0
        return GeometryReader { geo in
            Rectangle().fill(TetsubiColors.blue)
                .frame(width: geo.size.width * fraction)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: 3)
        .background(TetsubiColors.hairline)
        .accessibilityHidden(true)
    }

    private var controls: some View {
        HStack(spacing: Spacing.m) {
            if !manager.maniacMode {
                Button { manager.skipToPrevious() } label: {
                    Image(systemName: "backward.fill").font(.system(size: 16.scaled))
                        .frame(width: 32.scaled, height: 32.scaled).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Previous")
                .accessibilityIdentifier("NowPlayingBarView.previous")
            }
            Button { manager.togglePlayPause() } label: {
                Group {
                    if manager.isStarting {
                        ProgressView().controlSize(.small).accessibilityLabel("Loading")
                    } else {
                        Image(systemName: manager.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 22.scaled))
                    }
                }
                .frame(width: 36.scaled, height: 36.scaled).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(manager.isPlaying ? "Pause" : "Play")
            .accessibilityIdentifier("NowPlayingBarView.playPause")
            if !manager.maniacMode {
                Button { manager.skipToNext() } label: {
                    Image(systemName: "forward.fill").font(.system(size: 16.scaled))
                        .frame(width: 32.scaled, height: 32.scaled).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Next")
                .accessibilityIdentifier("NowPlayingBarView.next")
            }
        }
        .foregroundStyle(TetsubiColors.textPrimary)
    }
}

/// Elapsed time, a draggable thin bar, the track's length.
private struct MiniSeekBar: View {
    let manager: PlayerManager
    @State private var dragFraction: Double?

    var body: some View {
        let known = manager.duration > 0
        let shown = dragFraction ?? (known ? min(1, max(0, manager.position / manager.duration)) : 0)
        HStack(spacing: Spacing.s) {
            Text(NowPlayingBarView.format(shown * manager.duration))
                .font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary).monospacedDigit()
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(TetsubiColors.art).frame(height: 4)
                    Capsule().fill(TetsubiColors.blue).frame(width: geo.size.width * shown, height: 4)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard known else { return }
                        dragFraction = min(1, max(0, value.location.x / max(1, geo.size.width)))
                    }
                    .onEnded { value in
                        if known { manager.seek(toProgress: min(1, max(0, value.location.x / max(1, geo.size.width)))) }
                        dragFraction = nil
                    })
            }
            .frame(height: 24)
            Text(NowPlayingBarView.format(manager.duration))
                .font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary).monospacedDigit()
        }
        .opacity(known ? 1 : 0.4)
        .accessibilityElement()
        .accessibilityLabel("Seek")
        .accessibilityValue("\(NowPlayingBarView.format(manager.position)) of \(NowPlayingBarView.format(manager.duration))")
    }
}
