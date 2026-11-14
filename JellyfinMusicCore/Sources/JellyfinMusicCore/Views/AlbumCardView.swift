// MARK: - AlbumCardView — reusable card for album art, title, artist

import SwiftUI

/// A reusable album card used in horizontal scroll rows on the home screen
/// and in grid layouts on the library screen.
///
/// `artRequest` — built by `JellyfinAPI.imageURLRequest(for:size:)` —
/// carries the `Authorization` header needed for Jellyfin image endpoints.
/// When `nil` (e.g. no API / no token) a music-note placeholder is shown.
///
/// This is a **presentational view** with no tap handler of its own, so it
/// can be placed inside a `NavigationLink(value:label:)`. The enclosing
/// `NavigationLink` is the sole tap target: the card installs **no**
/// `.onTapGesture` of its own, which would otherwise compete with the link
/// and leave only a force-press (press action) navigating.
public struct AlbumCardView: View {

    // MARK: Inputs

    public let album: JellyfinAlbum
    /// Authenticated album-art request. `nil` shows the placeholder.
    public let artRequest: URLRequest?

    /// Whether to show the "downloaded" badge.  Passed in from the caller
    /// based on whether this album has been downloaded locally.
    public let isDownloaded: Bool

    /// Optional overlay text, e.g. "30 years ago" on "On This Day" cards.
    /// Pass `nil` for plain rows (Random / Recently Added / Recently Played).
    public let badge: String?

    // MARK: Init

    public init(album: JellyfinAlbum,
                artRequest: URLRequest? = nil,
                isDownloaded: Bool = false,
                badge: String? = nil) {
        self.album = album
        self.artRequest = artRequest
        self.isDownloaded = isDownloaded
        self.badge = badge
    }

    // MARK: Body

    /// Cover (and text) width: 140pt, grown on a big Mac window (`UIScale`).
    static var width: CGFloat { 140.scaled }

    public var body: some View {
        let width = Self.width
        return VStack(spacing: 4.scaled) {
            AlbumArtView(request: artRequest, size: width, cornerRadius: 8.scaled)
                .overlay(alignment: .topTrailing) {
                    if let badge, !badge.isEmpty {
                        Text(badge)
                            .font(.plex(.caption2).bold())
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Color.accentColor.opacity(0.7)))
                            .foregroundStyle(.white)
                            .padding(4)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if isDownloaded {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.plex(.title3))
                            .foregroundStyle(.green)
                            .padding(6)
                    }
                }

            Text(album.displayName)
                .font(.plex(.caption).bold())
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(width: width, alignment: .center)

            Text(album.artistName)
                .font(.plex(.caption2))
                .foregroundStyle(Color.albumArtistText)
                .lineLimit(1)
                .multilineTextAlignment(.center)
                .frame(width: width, alignment: .center)

            if let year = album.productionYear {
                Text(String(year))
                    .font(.plex(.caption2))
                    .foregroundStyle(Color.albumYearText)
                    .lineLimit(1)
                    .multilineTextAlignment(.center)
                .frame(width: width, alignment: .center)
            }
        }
        .padding(6.scaled)
    }

    // MARK: - Grid variant

    /// A compact grid variant for the Library tab.
    /// Smaller, square, no extra chrome.
    public var compact: some View {
        VStack {
            AlbumArtView(request: artRequest, size: 100.scaled, cornerRadius: 6.scaled)
            Text(album.displayName)
                .font(.plex(.caption2))
                .lineLimit(2)
                .multilineTextAlignment(.center)
        }
        .frame(width: 110.scaled)
    }
}

// MARK: - Loading placeholder

/// Same size and layout as `AlbumCardView`, shown while a Home row loads so
/// the screen doesn't jump when the albums arrive.
struct AlbumCardPlaceholder: View {
    var body: some View {
        let width = AlbumCardView.width
        return VStack(spacing: 4.scaled) {
            LoadingOutline(cornerRadius: 8.scaled)
                .frame(width: width, height: width)

            Text("Loading…")
                .font(.plex(.caption).bold())
                .lineLimit(2)
                .frame(width: width, alignment: .center)

            Text("Sit tight…")
                .font(.plex(.caption2))
                .foregroundStyle(Color.albumArtistText)
                .lineLimit(1)
                .frame(width: width, alignment: .center)

            Text("Soon…")
                .font(.plex(.caption2))
                .foregroundStyle(Color.albumYearText)
                .lineLimit(1)
                .frame(width: width, alignment: .center)
        }
        .padding(6.scaled)
    }
}

/// A row of four placeholder cards, laid out like a loaded Home row.
struct AlbumRowPlaceholder: View {
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 12.scaled) {
                ForEach(0..<4, id: \.self) { _ in AlbumCardPlaceholder() }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
        .scrollDisabled(true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading")
    }
}

/// A faint rounded outline with a pink and a blue pulse chasing each other
/// round it. All outlines share the clock, so every card moves in step.
/// Reduce Motion: just the faint outline.
///
/// Drawn at 30 fps, and still after `animationLimit`: a load that takes that
/// long is probably stuck, and a still outline costs no CPU (an endless
/// full-rate animation kept the Mac at ~40% CPU while idle).
struct LoadingOutline: View {
    let cornerRadius: CGFloat
    /// Seconds per lap.
    var period: Double = 2.0
    /// How long the pulses run before the outline goes still.
    var animationLimit: Duration = .seconds(20)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expired = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius)
        ZStack {
            shape.strokeBorder(Color.secondary.opacity(0.3), lineWidth: 1)
            if !reduceMotion && !expired {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
                    let t = context.date.timeIntervalSinceReferenceDate
                    let phase = t.truncatingRemainder(dividingBy: period) / period
                    shape.strokeBorder(
                        AngularGradient(gradient: Self.pulses, center: .center,
                                        angle: .degrees(phase * 360)),
                        lineWidth: 2
                    )
                }
            }
        }
        .task {
            try? await Task.sleep(for: animationLimit)
            if !Task.isCancelled { expired = true }
        }
    }

    /// Two comets half a lap apart: each fades in from its tail and ends
    /// at a bright head, which leads as the gradient turns clockwise.
    private static let pulses = Gradient(stops: [
        .init(color: .clear, location: 0.0),
        .init(color: Color.appPink.opacity(0), location: 0.25),
        .init(color: Color.appPink, location: 0.5),
        .init(color: .clear, location: 0.5001),
        .init(color: Color.appBlue.opacity(0), location: 0.75),
        .init(color: Color.appBlue, location: 1.0),
    ])
}
