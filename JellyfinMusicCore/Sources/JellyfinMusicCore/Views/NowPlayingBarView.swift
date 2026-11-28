// MARK: - NowPlayingBarView — persistent now-playing lozenge

import SwiftUI
import AVFoundation

/// A compact now-playing lozenge that floats above the tab bar.
///
/// Displays the current track's title / artist, an image
/// (fetched via `AuthenticatedImage` with the `Authorization` header), play/pause
/// toggle, next / previous, and a thin progress bar. Tapping the
/// bar's title area opens the full-screen `NowPlayingView` sheet.
public struct NowPlayingBarView: View {

    // `PlayerManager` is `@Observable`; reading its properties from within
    // a SwiftUI view property (e.g. `body`) automatically registers a
    // dependency through the Observation framework, so `let` is enough
    // to trigger re-renders.
    let manager: PlayerManager
    /// The progress line's colour, from the art (nil = the usual blue), as Now Playing.
    @State private var artAccent: Color?

    /// `.floating` draws its own glass capsule (with a thin progress line);
    /// `.accessory` is used inside `tabViewBottomAccessory`, which supplies
    /// the glass itself.
    public enum Style { case floating, accessory }

    let style: Style

    public init(manager: PlayerManager, style: Style = .floating) {
        self.manager = manager
        self.style = style
    }

    public var body: some View {
        Group {
            barContent
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var barContent: some View {
        if let track = manager.currentTrack {
            VStack(spacing: 0) {
                HStack(spacing: 12.scaled) {
                    // Tapping the title/art area opens the full-screen
                    // `NowPlayingView`. The controls to the right stay
                    // as separate, independent buttons.
                    Button { manager.isNowPlayingPresented = true } label: {
                        HStack(spacing: 12.scaled) {
                            AlbumArtView(
                            request: manager.artRequest(for: track, size: 100),
                            size: 36.scaled, cornerRadius: 6.scaled
                        )

                            VStack(alignment: .leading, spacing: 0) {
                                Text(track.title)
                                    .font(.plex(.subheadline)).bold()
                                    .lineLimit(1)
                                if manager.lastError == .trackFailed {
                                    Text("Couldn't play \u{2014} press Play to try again")
                                        .font(.plex(.caption2))
                                        .foregroundStyle(Color(red: 1, green: 0.55, blue: 0.55))
                                        .lineLimit(1)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("NowPlayingBarView.open")

                    HStack(spacing: 14.scaled) {
                        if !manager.maniacMode {
                        Button { manager.skipToPrevious() } label: {
                            Image(systemName: "backward.fill")
                                .imageScale(.large)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("NowPlayingBarView.previous")
                        }

                        Button { manager.togglePlayPause() } label: {
                            Group {
                                // Starting (loading, no sound yet): a spinner in the same box; a press does nothing.
                                if manager.isStarting {
                                    ProgressView().controlSize(.small)
                                        .accessibilityLabel("Loading")
                                } else {
                                    Image(systemName: manager.isPlaying ? "pause.fill" : "play.fill")
                                        .font(.plex(.title2))
                                }
                            }
                            .frame(width: 28.scaled, height: 28.scaled)  // play/pause swap doesn't shift the buttons or the bar's height
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("NowPlayingBarView.playPause")

                        if !manager.maniacMode {
                        Button { manager.skipToNext() } label: {
                            Image(systemName: "forward.fill")
                                .imageScale(.large)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("NowPlayingBarView.next")
                        }
                    }
                }
                .padding(.horizontal, 20.scaled)
                .padding(.vertical, 12.scaled)

                if style == .floating {
                    // Always there, empty and invisible while the length is unknown:
                    // taking the line away shortened the bar, and the buttons above
                    // it jumped when a skip landed on a track with no length yet.
                    let known = manager.duration > 0
                    ProgressView(value: known ? min(manager.position, manager.duration) : 0,
                                 total: known ? manager.duration : 1)
                        .progressViewStyle(.linear)
                        .tint(artAccent ?? Color.appBlue)
                        .opacity(known ? 1 : 0)
                        .padding(.horizontal, 28.scaled)
                        .padding(.bottom, 12.scaled)
                        .accessibilityHidden(!known)
                }
            }
            .modifier(LozengeBackground(enabled: style == .floating))
            .task(id: track.artKey) {
                let accent = await NowPlayingView.artAccent(for: manager)
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 0.3)) { artAccent = accent }
            }
        } else {
            // No current track — collapse to zero height so the
            // bar disappears entirely.
            Color.clear
                .frame(height: 0)
        }
    }

    // MARK: Helpers

    static func format(_ t: TimeInterval) -> String {
        let total = max(0, Int(t))
        let mm = total / 60
        let ss = total % 60
        return String(format: "%d:%02d", mm, ss)
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Empty bar") {
    NowPlayingBarView(manager: PlayerManager(api: JellyfinAPI(baseURL: URL(string: "http://localhost:8096")!)))
}
#endif

/// Liquid-glass capsule on iOS/macOS 26, a material capsule before that.
private struct LozengeBackground: ViewModifier {
    let enabled: Bool

    /// The Mac's glass is not "interactive": that makes the whole capsule squash
    /// and shift under the pointer on every click, so the buttons in it appeared
    /// to move when pressed.
    @available(iOS 26.0, macOS 26.0, *)
    private static var glass: Glass {
        #if os(macOS)
        .regular
        #else
        .regular.interactive()
        #endif
    }

    /// Space under the capsule. On the iPhone the tab bar or the safe area
    /// provides it; the Mac window has neither, so the bar sat flush against the
    /// bottom edge. Matches the 16 pt at its sides.
    private static var bottomGap: CGFloat {
        #if os(macOS)
        16
        #else
        0
        #endif
    }

    func body(content: Content) -> some View {
        if !enabled {
            content
        } else if #available(iOS 26.0, macOS 26.0, *) {
            content
                .glassEffect(Self.glass, in: .capsule)
                .padding(.horizontal, 16)
                .padding(.bottom, Self.bottomGap)
        } else {
            content
                .background(.thinMaterial, in: Capsule())
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.12)))
                .padding(.horizontal, 16)
                .padding(.bottom, Self.bottomGap)
        }
    }
}
