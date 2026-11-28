// MARK: - DetailParts — the detail-page pattern shared by Album, Playlist and Artist (docs/redesign.md section 5)

import SwiftUI

// MARK: - Pure rules

/// When a detail page has two panes, and how big its art is. Pure, so it can be
/// tested; the same rule as Kotlin's `DetailPage`.
public enum DetailLayout {
    /// The info pane's width on a two-pane page.
    public static let infoPaneWidth: CGFloat = 320
    /// A medium page gets two panes once the page itself is this wide.
    public static let twoPaneFromWidth: CGFloat = 900
    /// The stacked page's cover: at most this on compact, and on medium.
    public static let compactMaxArt: CGFloat = 260
    public static let mediumMaxArt: CGFloat = 320

    /// Two panes on expanded windows, and on medium ones whose page (the pane
    /// beside the sidebar) is at least 900 wide; compact is always stacked.
    public static func isTwoPane(windowClass: WindowWidthClass, paneWidth: CGFloat) -> Bool {
        switch windowClass {
        case .compact: false
        case .medium: paneWidth >= twoPaneFromWidth
        case .expanded: true
        }
    }

    /// The cover on a stacked page: as wide as fits between the margins, up to 260 (320 on medium).
    public static func stackedArtSize(paneWidth: CGFloat, windowClass: WindowWidthClass) -> CGFloat {
        let fits = max(0, paneWidth - 2 * windowClass.pageMargin)
        return min(fits, windowClass == .medium ? mediumMaxArt : compactMaxArt)
    }
}

/// What a page's info block needs to know: stacked or two panes, and the art's size.
public struct DetailMetrics: Equatable {
    public var twoPane: Bool
    public var artSize: CGFloat

    public var horizontal: HorizontalAlignment { twoPane ? .leading : .center }
    public var textAlignment: TextAlignment { twoPane ? .leading : .center }
    public var frameAlignment: Alignment { twoPane ? .leading : .center }
}

// MARK: - Page

/// A detail page. Stacked (compact, and medium under 900): `info` (art, title,
/// actions), then `list` (rows and shelves), then `after` (About), one scrolling
/// column. Two panes: the info pane (320, scrolls on its own, `after` under it)
/// on the left and the list on the right. The list's rows draw their own page
/// margin. Put the page's `PageHeader` above it.
struct DetailPage<Info: View, ListContent: View, After: View>: View {
    private let info: (DetailMetrics) -> Info
    private let list: (DetailMetrics) -> ListContent
    private let after: After

    @Environment(\.windowWidthClass) private var windowClass
    @State private var paneWidth: CGFloat = 0

    init(@ViewBuilder info: @escaping (DetailMetrics) -> Info,
         @ViewBuilder list: @escaping (DetailMetrics) -> ListContent,
         @ViewBuilder after: () -> After) {
        self.info = info
        self.list = list
        self.after = after()
    }

    var body: some View {
        let margin = windowClass.pageMargin
        let twoPane = DetailLayout.isTwoPane(windowClass: windowClass, paneWidth: paneWidth)
        Group {
            if twoPane {
                let metrics = DetailMetrics(twoPane: true, artSize: DetailLayout.infoPaneWidth)
                HStack(alignment: .top, spacing: 0) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: Spacing.l) {
                            info(metrics)
                            after
                        }
                        .frame(width: DetailLayout.infoPaneWidth, alignment: .leading)
                        .padding(.leading, margin)
                        .padding(.top, Spacing.s)
                        .padding(.bottom, Spacing.xxl)
                    }
                    .frame(width: DetailLayout.infoPaneWidth + margin)
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) { list(metrics) }
                            .padding(.top, Spacing.s)
                            .padding(.bottom, Spacing.xxl)
                    }
                    .frame(maxWidth: .infinity)
                }
            } else {
                let metrics = DetailMetrics(
                    twoPane: false,
                    artSize: DetailLayout.stackedArtSize(paneWidth: paneWidth, windowClass: windowClass))
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        VStack(alignment: .center, spacing: Spacing.l) { info(metrics) }
                            .frame(maxWidth: .infinity)
                            .padding(.horizontal, margin)
                        LazyVStack(alignment: .leading, spacing: 0) { list(metrics) }
                        VStack(alignment: .leading, spacing: Spacing.l) { after }
                            .padding(.horizontal, margin)
                            .padding(.vertical, Spacing.l)
                    }
                    .padding(.bottom, Spacing.xxl)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { paneWidth = $0 }
    }
}

// MARK: - Title block

/// The title under the art: `title` (28 bold), an optional artist line, and `meta` (secondary).
struct DetailTitleBlock<Artist: View>: View {
    let metrics: DetailMetrics
    let title: String
    var meta: String?
    let artist: Artist

    init(metrics: DetailMetrics, title: String, meta: String? = nil, @ViewBuilder artist: () -> Artist) {
        self.metrics = metrics
        self.title = title
        self.meta = meta
        self.artist = artist()
    }

    var body: some View {
        VStack(alignment: metrics.horizontal, spacing: Spacing.xs) {
            Text(title)
                .font(TetsubiType.pageTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
                .multilineTextAlignment(metrics.textAlignment)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: metrics.frameAlignment)
                .accessibilityAddTraits(.isHeader)
            artist
            if let meta, !meta.isEmpty {
                Text(meta)
                    .font(TetsubiType.body)
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .multilineTextAlignment(metrics.textAlignment)
                    .frame(maxWidth: .infinity, alignment: metrics.frameAlignment)
            }
        }
    }
}

/// The artist under a title: blue and tappable when it opens the artist
/// (`ArtistLink`), plain secondary text otherwise.
struct DetailArtistLine: View {
    let name: String
    let metrics: DetailMetrics
    let artist: JellyfinArtist?
    var identifier = "AlbumDetailView.artistLink"

    var body: some View {
        let text = Text(name)
            .font(TetsubiType.nowPlayingArtist)
            .foregroundStyle(artist != nil ? TetsubiColors.blue : TetsubiColors.textSecondary)
            .multilineTextAlignment(metrics.textAlignment)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: metrics.frameAlignment)
        if let artist {
            NavigationLink(value: artist) { text }
                .artistLinkStyle(name)
                .accessibilityIdentifier(identifier)
        } else {
            text
        }
    }
}

// MARK: - Buttons

/// An outlined pill, 40 high: a leading icon and a title (Download, Shuffle).
struct PillButton: View {
    let title: String
    var systemImage: String?
    var color: Color = TetsubiColors.textPrimary
    var enabled = true
    let action: () -> Void

    var body: some View {
        let tint = enabled ? color : color.opacity(0.5)
        Button(action: action) {
            HStack(spacing: Spacing.s) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 17.scaled)) }
                Text(title).font(TetsubiType.rowTitle).lineLimit(1)
            }
            .foregroundStyle(tint)
            .padding(.horizontal, Spacing.l)
            .frame(height: 40.scaled)
            .overlay(Capsule().strokeBorder(tint.opacity(enabled ? 0.4 : 0.25), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// A round icon button: surface fill, or a blue tint with a blue icon when `on`.
/// 44 for Now Playing's toggles, 40 beside the Play button.
struct RoundIconButton<Icon: View>: View {
    let label: String
    var size: CGFloat = 44
    var on = false
    var enabled = true
    let action: () -> Void
    let icon: (Color) -> Icon

    init(_ label: String, size: CGFloat = 44, on: Bool = false, enabled: Bool = true,
         action: @escaping () -> Void, @ViewBuilder icon: @escaping (Color) -> Icon) {
        self.label = label; self.size = size; self.on = on; self.enabled = enabled
        self.action = action; self.icon = icon
    }

    var body: some View {
        let tint = on ? TetsubiColors.blue : TetsubiColors.textPrimary
        Button(action: action) {
            icon(enabled ? tint : tint.opacity(0.4))
                .frame(width: size, height: size)
                .background(on ? TetsubiColors.blueTint : TetsubiColors.surface, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

extension RoundIconButton where Icon == Image {
    /// A round button with an SF Symbol.
    init(_ label: String, systemImage: String, size: CGFloat = 44, on: Bool = false, enabled: Bool = true,
         action: @escaping () -> Void) {
        self.init(label, size: size, on: on, enabled: enabled, action: action) { _ in Image(systemName: systemImage) }
    }
}

/// The ⋯ in a page header: a 40 pt round target opening `content` as a menu.
struct HeaderMenuButton<Content: View>: View {
    let label: String
    var enabled = true
    let content: Content

    init(_ label: String = "More", enabled: Bool = true, @ViewBuilder content: () -> Content) {
        self.label = label
        self.enabled = enabled
        self.content = content()
    }

    var body: some View {
        Menu {
            content
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18.scaled))
                .foregroundStyle(enabled ? TetsubiColors.textPrimary : TetsubiColors.textTertiary)
                .frame(width: 40.scaled, height: 40.scaled)
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .fixedSize()
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// A track row's ⋯ (Play next, Add to queue, Add to playlist…): 32 pt, secondary.
struct RowMenuButton<Content: View>: View {
    let label: String
    let content: Content

    init(_ label: String, @ViewBuilder content: () -> Content) {
        self.label = label
        self.content = content()
    }

    var body: some View {
        Menu {
            content
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 16.scaled))
                .foregroundStyle(TetsubiColors.textSecondary)
                .frame(width: 32.scaled, height: 32.scaled)
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .fixedSize()
        .accessibilityLabel(label)
    }
}

// MARK: - About the artist

/// "About <artist>": MusicBrainz facts and genres, the Wikipedia introduction,
/// and links to both (the attribution is required). Used by Album and Artist.
struct ArtistAboutView: View {
    let artistName: String
    let info: ArtistInfo
    @State private var showFullBio = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionHeader("About \(artistName)", horizontalPadding: 0)
            if let facts = info.facts {
                Text(facts).font(TetsubiType.body).foregroundStyle(TetsubiColors.textSecondary)
            }
            if !info.genres.isEmpty {
                Text(info.genres.joined(separator: " · ")).font(TetsubiType.caption).foregroundStyle(TetsubiColors.blue)
            }
            if let summary = info.summary {
                Text(summary)
                    .font(TetsubiType.body)
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .lineLimit(showFullBio ? nil : 6)
                if summary.count > 300 {
                    Button(showFullBio ? "Less" : "More") { showFullBio.toggle() }
                        .font(TetsubiType.control)
                        .buttonStyle(.plain)
                        .foregroundStyle(TetsubiColors.blue)
                }
            }
            HStack(spacing: Spacing.xs) {
                Link("MusicBrainz", destination: info.musicBrainzURL)
                if let wiki = info.wikipediaURL {
                    Text("·").foregroundStyle(TetsubiColors.textTertiary)
                    Link("Wikipedia", destination: wiki)
                    Text("(CC BY-SA)").foregroundStyle(TetsubiColors.textTertiary)
                }
            }
            .font(TetsubiType.caption)
            .foregroundStyle(TetsubiColors.textSecondary)
            .tint(TetsubiColors.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Messages

/// A page's loading, empty or error line inside a list.
struct DetailMessage: View {
    let text: String
    var color: Color = TetsubiColors.textSecondary
    @Environment(\.windowWidthClass) private var windowClass

    var body: some View {
        Text(text)
            .font(TetsubiType.body)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, windowClass.pageMargin)
            .padding(.vertical, Spacing.s)
    }
}

/// A download's progress under the actions: the bar, a status line, Pause / Resume / Retry and any error.
struct DownloadProgressBlock: View {
    let progress: Double
    let status: DownloadStatus
    let completed: Int
    let expected: Int
    /// "tracks" or "songs".
    let unit: String
    let error: String?
    let identifier: String
    let onToggle: () -> Void

    var body: some View {
        let paused = status == .paused
        let failed = status == .failed
        let waiting = DownloadNetworkRule.isWaitingForWiFi(
            status: status, allowCellular: PlaybackSettings.downloadOverCellular,
            isExpensive: NetworkMonitor.shared.isExpensive)
        VStack(alignment: .leading, spacing: Spacing.s) {
            PulsingProgressBar(progress: progress, isPaused: paused || failed)
            HStack(spacing: Spacing.s) {
                Text(DownloadNetworkRule.statusText(failed: failed, paused: paused, waitingForWiFi: waiting))
                    .font(TetsubiType.caption).foregroundStyle(TetsubiColors.textPrimary)
                Text("\(completed)/\(expected) \(unit)")
                    .font(TetsubiType.caption).foregroundStyle(TetsubiColors.textTertiary)
                Spacer(minLength: 0)
                ActionChip(failed ? "Retry" : paused ? "Resume" : "Pause", action: onToggle)
                    .disabled(status == .pending)
                    .accessibilityIdentifier(identifier)
            }
            if let error, !error.isEmpty {
                Text(error).font(TetsubiType.caption).foregroundStyle(TetsubiColors.red)
            }
        }
    }
}
