// MARK: - RecentlyPlayedView — Home ▸ Recently Played ›

import SwiftUI
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// The albums played most recently, newest first, discs of one album as one
/// entry, in the app's album grid (the header's S / M / L sets the card size).
/// "Export grid" makes a 1200 px picture of the covers: one card, under the
/// grid on compact and medium windows, a sticky column beside it on expanded
/// ones. Pull down to reload.
///
/// Offline: the downloaded albums among the last list loaded this run
/// (or Home's row, when the page never loaded), under the offline banner.
struct RecentlyPlayedView: View {
    let api: JellyfinAPI?

    @State private var all: [JellyfinAlbum]
    @State private var loaded = false

    @Environment(\.dismiss) private var dismiss
    @Environment(\.windowWidthClass) private var windowClass

    /// Albums asked of the server: enough for a 5×5 once discs are merged.
    static let fetchLimit = 60

    /// The export panel's width beside the grid (the margin goes round it).
    static let exportPanelWidth: CGFloat = 352

    /// The last full list this run, for going back offline.
    @MainActor private static var lastLoaded: [JellyfinAlbum] = []

    /// `initial`: Home's row, shown until the longer list arrives.
    init(api: JellyfinAPI?, initial: [JellyfinAlbum] = []) {
        self.api = api
        let seed = Self.lastLoaded.count > initial.count ? Self.lastLoaded : initial
        _all = State(initialValue: seed)
    }

    private var isOffline: Bool { !NetworkMonitor.shared.isConnected }

    private var settings: GridExportSettings { .shared }

    /// Offline, only what's downloaded.
    private var shown: [JellyfinAlbum] {
        isOffline ? all.filter(LibraryOffline.isDownloaded) : all
    }

    var body: some View {
        let albums = shown
        VStack(spacing: 0) {
            PageHeader(nil, onBack: { dismiss() }) { PageControls(density: true) }
            PageTitle("Recently played")
            if isOffline { Banner.offline }
            if albums.isEmpty, !loaded, !isOffline {
                LoadingSpinner().frame(maxHeight: .infinity, alignment: .top)
            } else if albums.isEmpty {
                EmptyState("No recently played music yet").frame(maxHeight: .infinity, alignment: .top)
            } else {
                let distinct = GridExport.distinct(albums)
                if windowClass == .expanded {
                    HStack(alignment: .top, spacing: 0) {
                        grid(distinct, footer: nil)
                        // Sticky: the grid scrolls, this stays.
                        ScrollView {
                            GridExportSection(api: api, albums: albums, settings: settings)
                                .padding(.top, Spacing.xxl)
                                .padding(.trailing, Spacing.xxl)
                                .padding(.bottom, Spacing.xxl)
                        }
                        .frame(width: Self.exportPanelWidth + Spacing.xxl)
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    grid(distinct, footer: AnyView(
                        GridExportSection(api: api, albums: albums, settings: settings)
                            .padding(.horizontal, windowClass.pageMargin)
                    ))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ownsItsHeader(isDetail: true)
        // Again when the connection comes back.
        .task(id: NetworkMonitor.shared.isConnected) { await load() }
    }

    private func grid(_ albums: [JellyfinAlbum], footer: AnyView?) -> some View {
        CardGrid(footer: footer) { metrics in
            ForEach(albums) { album in
                AlbumCardLink(api: api, album: album, width: metrics.cardWidth,
                              isDownloaded: LibraryOffline.isDownloaded(album))
            }
        }
        .frame(maxWidth: .infinity)
        .refreshable { await load() }
    }

    /// Nothing is asked of the server offline.
    private func load() async {
        guard NetworkMonitor.shared.isConnected, let api else {
            loaded = true
            return
        }
        let result: Result<[JellyfinAlbum], JellyfinError> = await withCheckedContinuation { c in
            api.recentlyPlayedAlbums(limit: Self.fetchLimit) { c.resume(returning: $0) }
        }
        if case .success(let items) = result {
            let merged = DiscGrouping.merge(items)
            all = merged
            Self.lastLoaded = merged
        }
        loaded = true
    }
}

// MARK: - Export grid

/// "Export grid", one card: size (3×3, 4×4, 5×5), "Show album and artist",
/// Copy and Save. Sizes that need more albums than there are are greyed out;
/// below 9 albums only a note shows. `albums` is the whole list (for which
/// sizes can be filled), not just the ones the grid shows.
struct GridExportSection: View {
    let api: JellyfinAPI?
    let albums: [JellyfinAlbum]
    let settings: GridExportSettings

    private enum Action { case copy, save }

    @State private var busy: Action?
    @State private var message: String?
    @State private var messageID = 0
    @State private var document: PNGDocument?
    @State private var showsExporter = false
    @State private var fileName = GridExport.fileName()

    var body: some View {
        let count = Set(albums.map(\.id)).count
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text("Export grid")
                .font(TetsubiType.sectionTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
                .padding(.horizontal, Spacing.l)
                .padding(.vertical, Spacing.s)
                .accessibilityAddTraits(.isHeader)
            if let size = GridExport.effectiveSize(chosen: settings.size, albumCount: count) {
                SettingsRow("Grid size") {
                    GridSizeSegments(selected: size, albumCount: count) { settings.size = $0 }
                }
                SettingsToggleRow("Show album and artist",
                                  isOn: Binding(get: { settings.showsLabels }, set: { settings.showsLabels = $0 }),
                                  divider: false)
                HStack(spacing: Spacing.m) {
                    PrimaryButton("Copy", enabled: busy == nil, working: busy == .copy) { run(.copy, size: size) }
                    PrimaryButton("Save", enabled: busy == nil, working: busy == .save) { run(.save, size: size) }
                    if let message {
                        Text(message)
                            .font(TetsubiType.body)
                            .foregroundStyle(TetsubiColors.textSecondary)
                            .transition(.opacity)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, Spacing.l)
                .padding(.vertical, Spacing.s)
            } else {
                Text(GridExport.tooFewMessage)
                    .font(TetsubiType.body)
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .padding(.horizontal, Spacing.l)
                    .padding(.vertical, Spacing.s)
            }
        }
        .padding(.vertical, Spacing.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
        .fileExporter(isPresented: $showsExporter, document: document, contentType: .png,
                      defaultFilename: fileName) { result in
            switch result {
            case .success: show("Saved")
            case .failure(let error):
                if (error as? CocoaError)?.code != .userCancelled { show("Couldn't save the image.") }
            }
            document = nil
        }
    }

    private func run(_ action: Action, size: Int) {
        busy = action
        message = nil
        let labels = settings.showsLabels
        let picked = GridExport.albums(albums, size: size) ?? []
        let api = api
        Task {
            let data = await Self.compose(picked, size: size, labels: labels, api: api)
            busy = nil
            guard let data else { show("Couldn't make the image."); return }
            switch action {
            case .copy:
                Self.copyToClipboard(data)
                show("Copied")
            case .save:
                fileName = GridExport.fileName()
                document = PNGDocument(data: data)
                showsExporter = true
            }
        }
    }

    /// A short note beside the buttons, gone after a couple of seconds.
    private func show(_ text: String) {
        messageID += 1
        let id = messageID
        withAnimation { message = text }
        Task {
            try? await Task.sleep(for: .seconds(2.5))
            if messageID == id { withAnimation { message = nil } }
        }
    }

    // MARK: Composing

    /// Covers fetched (600 px, through the art cache; a download's saved
    /// cover offline), then drawn off the main thread.
    private static func compose(_ albums: [JellyfinAlbum], size: Int, labels: Bool,
                                api: JellyfinAPI?) async -> Data? {
        guard albums.count == size * size else { return nil }
        let offline = !NetworkMonitor.shared.isConnected
        let requests: [(URLRequest?, URL?)] = albums.map {
            (api?.imageURLRequest(for: $0.id, size: GridExport.coverRequestSize),
             DownloadPaths.savedCover(albumID: $0.id))
        }
        var covers = [CGImage?](repeating: nil, count: albums.count)
        await withTaskGroup(of: (Int, CGImage?).self) { group in
            for (index, pair) in requests.enumerated() {
                group.addTask {
                    let server = pair.0
                    let saved = pair.1.map { URLRequest(url: $0) }
                    let order = offline ? [saved, server] : [server, saved]
                    for request in order.compactMap({ $0 }) {
                        if let image = await ImageCache.load(request) { return (index, image) }
                    }
                    return (index, nil)
                }
            }
            for await (index, image) in group { covers[index] = image }
        }
        let tiles = zip(albums, covers).map { album, cover in
            GridExport.Tile(cover: cover, title: album.displayName, artist: album.artistName)
        }
        return await Task.detached(priority: .userInitiated) {
            GridExport.renderPNG(tiles: tiles, size: size, labels: labels)
        }.value
    }

    @MainActor
    private static func copyToClipboard(_ png: Data) {
        #if canImport(UIKit)
        UIPasteboard.general.setData(png, forPasteboardType: UTType.png.identifier)
        #elseif canImport(AppKit)
        let board = NSPasteboard.general
        board.clearContents()
        board.setData(png, forType: .png)
        #endif
    }
}

/// 3×3 | 4×4 | 5×5, with the sizes there aren't enough albums for greyed out.
private struct GridSizeSegments: View {
    let selected: Int
    let albumCount: Int
    let choose: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(GridExport.sizes, id: \.self) { size in
                let enabled = GridExport.isAvailable(size: size, albumCount: albumCount)
                let on = size == selected
                Button { choose(size) } label: {
                    Text("\(size)×\(size)")
                        .font(TetsubiType.control)
                        .foregroundStyle(on ? TetsubiColors.blue : TetsubiColors.textSecondary)
                        .opacity(enabled ? 1 : 0.4)
                        .padding(.horizontal, Spacing.m)
                        .frame(height: 28.scaled)
                        .background(on ? TetsubiColors.blueTint : Color.clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityAddTraits(on ? .isSelected : [])
                .accessibilityLabel("\(size) by \(size)")
            }
        }
        .padding(2)
        .background(TetsubiColors.surface, in: Capsule())
    }
}

/// The exported PNG, for the save dialog.
struct PNGDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
