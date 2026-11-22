// MARK: - NowPlayingWidgetView — the widget's two sizes

import WidgetKit
import AppIntents
import SwiftUI
import TetsubiWidgetData

/// Small: the art fills the widget, with title, artist, album and finish
/// time (no duration) over a dark fade at the bottom (as many lines as fit) and
/// play/pause at the top right. Medium: art on the
/// left; title, artist, album, duration and finish time; previous,
/// play/pause and next (no skips in Maniac
/// Mode); on an iPhone the type is smaller and the duration and finish time
/// take a line each (`WidgetDisplay.MediumStyle`). Dark, like the app's mini player, tinted with the art's key
/// colour (Tetsubi's blue when the art has none). No progress bar.
struct NowPlayingWidgetView: View {
    let entry: NowPlayingEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .foregroundStyle(.white)
            .widgetURL(entry.display.openURL)
            .containerBackground(for: .widget) { background }
    }

    @ViewBuilder
    private var content: some View {
        switch entry.display {
        case .track(let track):
            if family == .systemMedium {
                MediumTrackView(track: track, art: entry.art,
                                style: .of(isPhone: UIDevice.current.userInterfaceIdiom == .phone))
            } else {
                SmallTrackView(track: track, art: entry.art)
            }
        case .nothingPlaying:
            EmptyStateView(message: "Nothing playing", wide: family == .systemMedium)
        case .signedOut:
            EmptyStateView(message: "Open Tetsubi to sign in", wide: family == .systemMedium)
        }
    }

    /// The app's navy with a soft wash of the key colour from the top; on
    /// the small size with art, the art itself, edge to edge, darkened at
    /// the bottom so the text reads.
    private var background: some View {
        ZStack {
            Color.widgetBackground
            if case .track(let track) = entry.display {
                if family != .systemMedium, let art = entry.art {
                    FullArtView(art: art)
                    LinearGradient(stops: [.init(color: .black.opacity(0), location: 0.25),
                                           .init(color: .black.opacity(0.85), location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                } else {
                    LinearGradient(colors: [track.keyColor.opacity(0.35), track.keyColor.opacity(0.05)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                }
            }
        }
    }
}

// MARK: - Sizes

private struct SmallTrackView: View {
    let track: WidgetDisplay.Track
    let art: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 0) {
                if art == nil {
                    // No art to fill the widget with: the placeholder, as before.
                    ArtView(art: nil, cornerRadius: 10)
                        .frame(width: 56, height: 56)
                        .accessibilityHidden(true)
                }
                Spacer(minLength: 4)
                PlayPauseButton(track: track, diameter: 40)
            }
            Spacer(minLength: 4)
            // As many lines as fit: the album goes first, then the artist.
            ViewThatFits(in: .vertical) {
                lines(artist: true, album: true)
                lines(artist: true, album: false)
                lines(artist: false, album: false)
            }
            .shadow(color: .black.opacity(0.5), radius: 2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func lines(artist: Bool, album: Bool) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(track.title)
                .font(.tetsubi(size: 15, weight: .semibold))
                .accessibilityLabel(track.accessibilityLabel)
            Group {
                if artist, !track.artist.isEmpty {
                    Text(track.artist).font(.tetsubi(size: 12)).foregroundStyle(Color.white.opacity(0.9))
                }
                if album, !track.album.isEmpty {
                    Text(track.album).font(.tetsubi(size: 12)).foregroundStyle(Color.white.opacity(0.75))
                }
                if let finish = track.finishText() {
                    Text(finish).font(.tetsubi(size: 11)).foregroundStyle(Color.white.opacity(0.75))
                }
            }
            .accessibilityHidden(true)
        }
        .lineLimit(1)
    }
}

/// The art edge to edge, for the small widget's background.
private struct FullArtView: View {
    let art: UIImage

    var body: some View {
        GeometryReader { proxy in
            Group {
                if #available(iOS 18.0, *) {
                    Image(uiImage: art).resizable().widgetAccentedRenderingMode(.fullColor).scaledToFill()
                } else {
                    Image(uiImage: art).resizable().scaledToFill()
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .clipped()
        }
        .accessibilityHidden(true)
    }
}

private struct MediumTrackView: View {
    let track: WidgetDisplay.Track
    let art: UIImage?
    let style: WidgetDisplay.MediumStyle

    var body: some View {
        HStack(spacing: 14) {
            ArtView(art: art, cornerRadius: 12)
                .aspectRatio(1, contentMode: .fit)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: style.lineSpacing) {
                VStack(alignment: .leading, spacing: style.lineSpacing) {
                    Text(track.title)
                        .font(.tetsubi(size: style.titleSize, weight: .semibold))
                    if !track.artist.isEmpty {
                        Text(track.artist)
                            .font(.tetsubi(size: style.secondarySize))
                            .foregroundStyle(Color.white.opacity(0.85))
                    }
                    if !track.album.isEmpty {
                        Text(track.album)
                            .font(.tetsubi(size: style.secondarySize))
                            .foregroundStyle(Color.white.opacity(0.65))
                    }
                }
                .lineLimit(1)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(track.accessibilityLabel)
                if style.twoLineTimes {
                    QueueTimesLines(track: track, size: style.timesSize)
                } else {
                    QueueTimesLine(track: track)
                }
                Spacer(minLength: style.minGap)
                HStack(spacing: 22) {
                    if track.showsSkips {
                        SkipButton(intent: PreviousTrackIntent(), symbol: "backward.fill", label: "Previous",
                                   height: style.playDiameter)
                    }
                    PlayPauseButton(track: track, diameter: style.playDiameter)
                    if track.showsSkips {
                        SkipButton(intent: NextTrackIntent(), symbol: "forward.fill", label: "Next",
                                   height: style.playDiameter)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}

/// An iPad's line under the album, as Now Playing's: "Duration: 51m 06s   Ends: 13:42"
/// (just the duration once the finish time isn't known; nothing when the
/// length isn't known).
private struct QueueTimesLine: View {
    let track: WidgetDisplay.Track

    var body: some View {
        if let line = track.timesLine() {
            // A narrow widget with a long album and a 12-hour
            // clock: smaller type, then just the finish time.
            ViewThatFits(in: .horizontal) {
                times(line, size: 11)
                if let compact = track.timesLine(separator: "  ") {
                    times(compact, size: 10)
                    times(compact, size: 9)
                }
                times(track.finishText() ?? line, size: 11)
            }
            .padding(.top, 1)
        }
    }

    private func times(_ text: String, size: CGFloat) -> some View {
        Text(text)
            .font(.tetsubi(size: size))
            .foregroundStyle(Color.white.opacity(0.55))
            .lineLimit(1)
    }
}

/// An iPhone's times under the album: "Duration: 51m 06s", then "Ends: 13:42"
/// on a line of its own (on one line the finish time was cut off). Just the
/// duration once the finish time isn't known; nothing when the length isn't known.
private struct QueueTimesLines: View {
    let track: WidgetDisplay.Track
    let size: Double

    var body: some View {
        let lines = track.timesLines(twoLines: true)
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(lines, id: \.self) { line in
                    Text(line)
                        .font(.tetsubi(size: size))
                        .foregroundStyle(Color.white.opacity(0.55))
                        .lineLimit(1)
                }
            }
            .padding(.top, 1)
        }
    }
}

/// Nothing to show: the Tetsubi mark and a line of text. Tapping opens the app.
private struct EmptyStateView: View {
    let message: String
    let wide: Bool

    var body: some View {
        let layout = wide ? AnyLayout(HStackLayout(spacing: 16)) : AnyLayout(VStackLayout(spacing: 10))
        layout {
            Image("DiscLogo")
                .resizable()
                .scaledToFit()
                .frame(width: wide ? 72 : 56, height: wide ? 72 : 56)
                .accessibilityHidden(true)
            Text(message)
                .font(.tetsubi(size: wide ? 17 : 14, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.85))
                .multilineTextAlignment(wide ? .leading : .center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Pieces

private struct ArtView: View {
    let art: UIImage?
    let cornerRadius: CGFloat

    var body: some View {
        Group {
            if let art {
                artImage(art)
            } else {
                ZStack {
                    Color.white.opacity(0.08)
                    Image(systemName: "music.note")
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(Color.white.opacity(0.4))
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    /// The art keeps its colours when the home screen tints widgets.
    @ViewBuilder
    private func artImage(_ image: UIImage) -> some View {
        if #available(iOS 18.0, *) {
            Image(uiImage: image).resizable().widgetAccentedRenderingMode(.fullColor).scaledToFill()
        } else {
            Image(uiImage: image).resizable().scaledToFill()
        }
    }
}

/// The key-coloured round Play/Pause button. It does what it shows.
private struct PlayPauseButton: View {
    let track: WidgetDisplay.Track
    let diameter: CGFloat

    var body: some View {
        Button(intent: TogglePlaybackIntent(pause: track.isPlaying)) {
            Image(systemName: track.isPlaying ? "pause.fill" : "play.fill")
                .font(.system(size: diameter * 0.42, weight: .semibold))
                .foregroundStyle(Color.widgetBackground)
                .frame(width: diameter, height: diameter)
                .background(Circle().fill(track.keyColor))
                .widgetAccentable()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(track.isPlaying ? "Pause" : "Play")
    }
}

private struct SkipButton<Intent: AppIntent>: View {
    let intent: Intent
    let symbol: String
    let label: String
    var height: Double = 44

    var body: some View {
        Button(intent: intent) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .frame(width: 32, height: height)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// MARK: - Colours and type

private extension Color {
    /// The app's background navy, #000B25.
    static let widgetBackground = Color(red: 0 / 255, green: 11 / 255, blue: 37 / 255)
    /// Tetsubi's blue, #039FD6, for art with no key colour.
    static let tetsubiBlue = Color(red: 3 / 255, green: 159 / 255, blue: 214 / 255)
}

private extension WidgetDisplay.Track {
    /// The art's key colour, or Tetsubi's blue.
    var keyColor: Color {
        accent.map { Color(.sRGB, red: $0.red, green: $0.green, blue: $0.blue) } ?? .tetsubiBlue
    }
}

private extension Font {
    /// The app's typeface (DEKIRU Sans, bundled with the widget).
    static func tetsubi(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("DEKIRU Sans", size: size).weight(weight)
    }
}

// MARK: - Previews

#if DEBUG
private let previewTrack = WidgetDisplay.Track(
    title: "Push", artist: "Inside Yours", album: "Inside Yours", isPlaying: true,
    showsSkips: true, accent: .init(red: 0.95, green: 0.55, blue: 0.2), artFile: nil,
    queueSeconds: 3066, finishesAt: .now.addingTimeInterval(1900))

#Preview("Small", as: .systemSmall) {
    NowPlayingWidget()
} timeline: {
    NowPlayingEntry(date: .now, display: .track(previewTrack), art: nil)
    NowPlayingEntry(date: .now, display: .nothingPlaying, art: nil)
}

#Preview("Medium", as: .systemMedium) {
    NowPlayingWidget()
} timeline: {
    NowPlayingEntry(date: .now, display: .track(previewTrack), art: nil)
    NowPlayingEntry(date: .now, display: .signedOut, art: nil)
}
#endif
