// MARK: - MediaCard — the one card for albums, artists and playlists (docs/redesign.md section 3)

import SwiftUI

/// Cover sizes asked of the server, in pixels: a few steps, so the image cache
/// keeps hitting as cards change size.
public enum CardArt {
    public static let sizes = [300, 600, 1000, 1500]

    /// The smallest of `sizes` that covers a card `width` points wide on a display of `displayScale`.
    public static func requestSize(width: CGFloat, displayScale: CGFloat) -> Int {
        let pixels = width * displayScale
        return sizes.first { CGFloat($0) >= pixels } ?? sizes[sizes.count - 1]
    }
}

/// A square `art` (radius 10, or a circle for artists) as wide as the column,
/// then `title` (14 semibold) and `subtitle` (12 secondary), left aligned so a
/// grid reads as columns. `overlay` draws over the art (a badge, the downloaded
/// tick). `art` gets the corner radius to use.
public struct MediaCard<Art: View, Overlay: View>: View {
    let width: CGFloat
    let title: String
    var subtitle: String?
    var detail: String?
    var circle = false
    var titleLines = 1
    let art: (CGFloat) -> Art
    let overlay: Overlay

    public init(width: CGFloat, title: String, subtitle: String? = nil, detail: String? = nil,
                circle: Bool = false, titleLines: Int = 1,
                @ViewBuilder art: @escaping (CGFloat) -> Art, @ViewBuilder overlay: () -> Overlay) {
        self.width = width; self.title = title; self.subtitle = subtitle; self.detail = detail
        self.circle = circle; self.titleLines = titleLines; self.art = art; self.overlay = overlay()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            ZStack { art(circle ? width / 2 : Radii.card); overlay }
                .frame(width: width, height: width)
            Text(title)
                .font(TetsubiType.cardTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
                .lineLimit(titleLines)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(TetsubiType.caption)
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let detail {
                Text(detail)
                    .font(TetsubiType.caption)
                    .foregroundStyle(TetsubiColors.textTertiary)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(width: width, alignment: .topLeading)
        .contentShape(Rectangle())
    }
}

public extension MediaCard where Overlay == EmptyView {
    init(width: CGFloat, title: String, subtitle: String? = nil, detail: String? = nil,
         circle: Bool = false, titleLines: Int = 1, @ViewBuilder art: @escaping (CGFloat) -> Art) {
        self.init(width: width, title: title, subtitle: subtitle, detail: detail, circle: circle,
                  titleLines: titleLines, art: art) { EmptyView() }
    }
}

/// The green tick over a downloaded cover's corner.
struct DownloadedBadge: View {
    var size: CGFloat = 20
    var body: some View {
        Image(systemName: "checkmark.circle.fill")
            .font(.system(size: size.scaled))
            .foregroundStyle(TetsubiColors.green)
            .padding(6)
            .accessibilityLabel("Downloaded")
    }
}

/// A label over a cover's corner ("30 years ago").
struct CardBadge: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.plex(size: 11, weight: .bold))
            .foregroundStyle(.white)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, 3)
            .background(TetsubiColors.blue.opacity(0.7), in: Capsule())
            .padding(6)
    }
}

/// The server cover of `itemID`, or (offline) the saved one of a downloaded album.
@MainActor
func coverRequest(api: JellyfinAPI?, id: String, size: Int) -> URLRequest? {
    if !NetworkMonitor.shared.isConnected, let saved = DownloadPaths.savedCover(albumID: id) {
        return URLRequest(url: saved)
    }
    return api?.imageURLRequest(for: id, size: size)
}

// MARK: - Cells

/// An album card `width` wide that opens the album. Long press or right-click: the queue menu.
struct AlbumCardLink: View {
    let api: JellyfinAPI?
    let album: JellyfinAlbum
    let width: CGFloat
    var isDownloaded = false
    var badge: String?
    /// The release year under the title instead of the artist (an artist's page).
    var showsYear = false
    var titleLines = 1

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let size = CardArt.requestSize(width: width, displayScale: displayScale)
        NavigationLink(value: album) {
            MediaCard(width: width, title: album.displayName,
                      subtitle: showsYear ? ArtistPage.yearText(album) : album.artistName,
                      titleLines: titleLines) { corner in
                AlbumArtView(request: coverRequest(api: api, id: album.id, size: size), size: width,
                             cornerRadius: corner, placeholderColor: TetsubiColors.art)
            } overlay: {
                ZStack {
                    if let badge, !badge.isEmpty { CardBadge(text: badge).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing) }
                    if isDownloaded { DownloadedBadge().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing) }
                }
            }
        }
        .buttonStyle(.plain)
        .albumQueueMenu(album)
    }
}

/// An artist card: a circular picture, then the name.
struct ArtistCardLink: View {
    let api: JellyfinAPI?
    let artist: JellyfinArtist
    let width: CGFloat

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let size = CardArt.requestSize(width: width, displayScale: displayScale)
        NavigationLink(value: artist) {
            MediaCard(width: width, title: artist.name, circle: true) { corner in
                AlbumArtView(request: api?.imageURLRequest(for: artist.id, size: size), size: width,
                             cornerRadius: corner, placeholderColor: TetsubiColors.surface)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A playlist card: its picture, name and "12 songs · 1 h 12 min".
struct PlaylistCardLink: View {
    let api: JellyfinAPI?
    let playlist: JellyfinPlaylist
    let width: CGFloat

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let size = CardArt.requestSize(width: width, displayScale: displayScale)
        NavigationLink(value: playlist) {
            MediaCard(width: width, title: playlist.name, subtitle: playlist.summary) { corner in
                PlaylistArtView(api: api, playlist: playlist, size: width, cornerRadius: corner, requestSize: size)
            }
        }
        .buttonStyle(.plain)
        .playlistQueueMenu(playlist)
    }
}

/// The shape of a card while its row loads: the cover as a pulsing outline,
/// bars for the text, so the screen doesn't jump.
struct CardPlaceholder: View {
    let width: CGFloat
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            LoadingOutline(cornerRadius: Radii.card).frame(width: width, height: width)
            RoundedRectangle(cornerRadius: Radii.thumb).fill(TetsubiColors.surface)
                .frame(width: width * 0.7, height: 12).padding(.top, Spacing.xs)
            RoundedRectangle(cornerRadius: Radii.thumb).fill(TetsubiColors.surface)
                .frame(width: width * 0.45, height: 10).padding(.top, 2)
        }
        .frame(width: width, alignment: .topLeading)
    }
}

/// A loading shelf: a screenful of placeholders laid out like the loaded row.
struct CardShelfPlaceholder: View {
    private struct Slot: Identifiable { let id: Int }
    var body: some View {
        CardShelf(items: (0..<8).map(Slot.init)) { _, width in CardPlaceholder(width: width) }
            .scrollDisabled(true)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Loading")
    }
}
