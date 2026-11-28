// MARK: - DownloadsView — live + completed download list

import SwiftUI
import Observation

/// The Downloads tab: a page header with Edit, then
///   **Downloading** — playlists and albums in progress, with a progress bar,
///                     pause / resume / retry and cancel;
///   **Playlists**   — a shelf of downloaded playlists (a card opens its page);
///   **Downloaded**  — the downloaded albums as the app's grid, newest first
///                     (a card plays it);
///   **Storage**     — disk use and playing time: a card beside the grid on
///                     expanded windows, below it otherwise.
/// Edit selects several cards and shows a "Remove N" bar.
public struct DownloadsView: View {

    // MARK: - Inputs

    public let downloadManager: DownloadManager?
    public let player: PlayerManager?

    // MARK: - Edit state

    @State private var isEditing = false
    @State private var selection: Set<String> = []
    /// Playlists ticked in edit mode.
    @State private var playlistSelection: Set<String> = []
    /// Shows the "Remove N downloads?" confirmation.
    @State private var confirmingRemoval = false
    /// Album durations, filled in as cards appear (read from the files).
    @State private var durations: [String: TimeInterval] = [:]

    @Environment(\.windowWidthClass) private var windowClass

    // MARK: -

    public init(downloadManager: DownloadManager? = nil, player: PlayerManager? = nil) {
        self.downloadManager = downloadManager
        self.player         = player
    }

    private func hasDownloads(_ mgr: DownloadManager) -> Bool {
        !mgr.completedAlbumIDs.isEmpty || !mgr.playlists.downloadedIDs.isEmpty
    }

    // MARK: - Body

    public var body: some View {
        VStack(spacing: 0) {
            PageHeader("Downloads") {
                if let mgr = downloadManager, hasDownloads(mgr) { editButton }
            }
            if let mgr = downloadManager,
               !(mgr.activeDownloads.isEmpty && mgr.completedAlbumIDs.isEmpty && mgr.playlists.manifests.isEmpty) {
                page(mgr)
            } else {
                emptyState
            }
        }
        .ownsItsHeader()
        .onAppear {
            downloadManager?.refreshDownloaded()
            downloadManager?.playlists.refresh()
        }
    }

    /// Edit / Done.
    private var editButton: some View {
        ActionChip(isEditing ? "Done" : "Edit") {
            isEditing.toggle()
            selection = []
            playlistSelection = []
        }
        .accessibilityIdentifier("DownloadsView.edit")
    }

    private func page(_ mgr: DownloadManager) -> some View {
        let sideBySide = windowClass == .expanded
        return HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if !mgr.activeDownloads.isEmpty || !mgr.playlists.active.isEmpty {
                        downloadingSection(mgr)
                    }
                    if !mgr.playlists.downloadedIDs.isEmpty {
                        playlistsSection(mgr)
                    }
                    if !mgr.completedAlbumIDs.isEmpty {
                        downloadedSection(mgr)
                    }
                    if !sideBySide {
                        storageCard(mgr)
                            .padding(.horizontal, windowClass.pageMargin)
                            .padding(.top, Spacing.l)
                    }
                }
                .padding(.bottom, Spacing.xl)
            }
            .frame(maxWidth: .infinity)
            if sideBySide {
                // Beside the grid, kept in view.
                ScrollView {
                    storageCard(mgr)
                        .padding(.top, Spacing.xxl)
                        .padding(.trailing, Spacing.xxl)
                        .padding(.bottom, Spacing.xxl)
                }
                .frame(width: 352 + Spacing.xxl)
            }
        }
        .frame(maxHeight: .infinity)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isEditing { removeBar(mgr) }
        }
        // Nothing left to edit (removed elsewhere): leave edit mode.
        .onChange(of: hasDownloads(mgr)) { _, has in
            if !has { isEditing = false; selection = []; playlistSelection = [] }
        }
        .confirmationDialog(Self.removalTitle(count: selection.count + playlistSelection.count),
                            isPresented: $confirmingRemoval, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                // Playlists first: an album going would otherwise
                // set them fetching its songs again.
                for id in playlistSelection { mgr.playlists.remove(id) }
                for id in selection { mgr.deleteDownloadedAlbum(id) }
                selection = []
                playlistSelection = []
                isEditing = false
            }
            .accessibilityIdentifier("DownloadsView.confirmRemove")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(Self.removalMessage(albums: selection.count, playlists: playlistSelection.count))
        }
    }

    /// "12 songs · 1 h 12 min · 512 MB", for a downloaded playlist.
    static func playlistDetailLine(_ playlist: JellyfinPlaylist, size: Int64) -> String {
        playlist.summary + " · " + DownloadPaths.formatBytes(size)
    }

    /// What the remove confirmation says under its title.
    static func removalMessage(albums: Int, playlists: Int) -> String {
        let from = playlists == 0 ? "from the album page"
            : albums == 0 ? "from the playlist page" : "from their pages"
        return "They'll be deleted from this device. You can download them again \(from)."
    }

    /// "12 tracks · 47m 12s · 512 MB" (duration left out until it's known).
    static func detailLine(count: Int, duration: TimeInterval?, size: Int64) -> String {
        var parts = ["\(count) \(count == 1 ? "track" : "tracks")"]
        if let duration, duration > 0 { parts.append(NowPlayingView.formatAlbumDuration(duration)) }
        parts.append(DownloadPaths.formatBytes(size))
        return parts.joined(separator: " · ")
    }

    /// Title of the remove confirmation (same wording as Android).
    static func removalTitle(count: Int) -> String {
        count == 1 ? "Remove this download?" : "Remove \(count) downloads?"
    }

    /// Sum of the albums' durations, or nil while any is still unknown.
    /// Albums whose files couldn't be read count as 0. `playlists` is the
    /// time of the songs kept in downloaded playlists' own folders, each
    /// once; songs a playlist plays from an album are already in the album's.
    static func totalDuration(of albumIDs: Set<String>, durations: [String: TimeInterval],
                              playlists: TimeInterval = 0) -> TimeInterval? {
        var total = playlists
        for id in albumIDs {
            guard let d = durations[id] else { return nil }
            total += d
        }
        return total
    }

    // MARK: - Sections

    private func downloadingSection(_ mgr: DownloadManager) -> some View {
        let albums = mgr.activeDownloads.values.sorted { ($0.title, $0.albumID) < ($1.title, $1.albumID) }
        let playlists = mgr.playlists.active.values.sorted { $0.name < $1.name }
        return VStack(alignment: .leading, spacing: 0) {
            SectionHeader("Downloading")
            ListGridContent {
                ForEach(playlists, id: \.playlistID) { state in
                    playlistDownloadRow(state, playlists: mgr.playlists)
                }
                ForEach(albums, id: \.albumID) { state in
                    downloadRow(state, mgr: mgr)
                }
            }
        }
    }

    /// Downloaded playlists, by name, as a shelf. A card opens the playlist's
    /// page; in edit mode it's ticked for removal.
    private func playlistsSection(_ mgr: DownloadManager) -> some View {
        let playlists = PlaylistsViewModel.sorted(mgr.playlists.downloadedPlaylists, by: .name)
        return VStack(alignment: .leading, spacing: 0) {
            SectionHeader("Playlists")
            CardShelf(items: playlists) { playlist, width in
                playlistCard(playlist, width: width, mgr: mgr)
            }
        }
        .padding(.top, Spacing.s)
    }

    @ViewBuilder
    private func playlistCard(_ playlist: JellyfinPlaylist, width: CGFloat, mgr: DownloadManager) -> some View {
        let card = MediaCard(width: width, title: playlist.name,
                             subtitle: Self.playlistDetailLine(playlist, size: mgr.playlists.size(playlist.id))) { corner in
            AlbumArtView(request: playlistArtRequest(playlist.id, playlists: mgr.playlists), size: width,
                         cornerRadius: corner, placeholderColor: TetsubiColors.art)
        } overlay: {
            if isEditing {
                SelectionMark(selected: playlistSelection.contains(playlist.id))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .accessibilityIdentifier("DownloadsView.selectPlaylist.\(playlist.id)")
            }
        }
        if isEditing {
            card.onTapGesture {
                if !playlistSelection.insert(playlist.id).inserted { playlistSelection.remove(playlist.id) }
            }
            .accessibilityAddTraits(playlistSelection.contains(playlist.id) ? [.isButton, .isSelected] : .isButton)
        } else {
            NavigationLink(value: playlist) { card }
                .buttonStyle(.plain)
                .playlistQueueMenu(playlist)
                .accessibilityIdentifier("DownloadsView.playlist.\(playlist.id)")
        }
    }

    /// A downloaded playlist's saved picture.
    private func playlistArtRequest(_ playlistID: String, playlists: PlaylistDownloads) -> URLRequest? {
        _ = playlists.artVersion
        return PlaylistDownloadPaths.savedCover(playlistID: playlistID).map { URLRequest(url: $0) }
    }

    /// The downloaded albums, newest first, as the app's grid. A card plays the
    /// album; in edit mode it is ticked for removal.
    private func downloadedSection(_ mgr: DownloadManager) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader("Downloaded").padding(.top, Spacing.s)
            CardGridContent { metrics in
                ForEach(DownloadPaths.newestFirst(mgr.completedAlbumIDs), id: \.self) { albumID in
                    downloadedCard(albumID, width: metrics.cardWidth, mgr: mgr)
                }
            }
            .padding(.vertical, Spacing.s)
        }
    }

    private func downloadedCard(_ albumID: String, width: CGFloat, mgr: DownloadManager) -> some View {
        let info = mgr.albumInfo(for: albumID)
        let size = DownloadPaths.totalSize(albumID: albumID)
        let count = DownloadPaths.fileCount(albumID: albumID)
        let title = info?.title ?? "Album \(albumID.prefix(6))"
        return MediaCard(width: width, title: title, subtitle: info?.artist,
                         detail: Self.detailLine(count: count, duration: durations[albumID], size: size)) { corner in
            AlbumArtView(request: mgr.artRequest(for: albumID), size: width, cornerRadius: corner,
                         placeholderColor: TetsubiColors.art)
        } overlay: {
            if isEditing {
                SelectionMark(selected: selection.contains(albumID))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .accessibilityIdentifier("DownloadsView.select.\(albumID)")
            }
        }
        .onTapGesture {
            if isEditing {
                if !selection.insert(albumID).inserted { selection.remove(albumID) }
            } else {
                QueueActions.playDownloaded(id: albumID, title: info?.title ?? "", artist: info?.artist ?? "")
            }
        }
        .task(id: count) {
            if let d = await DownloadPaths.albumDuration(albumID: albumID) { durations[albumID] = d }
        }
        .contextMenu {
            if !isEditing, player != nil {
                QueueMenuItems {
                    QueueActions.queue(downloadedAlbumIDs: [albumID], sourceId: albumID,
                                       title: info?.title ?? "", artist: info?.artist ?? "", $0)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(isEditing ? title : "Play \(title)")
        .accessibilityIdentifier("DownloadsView.cover.\(albumID)")
    }

    /// The "Remove N" bar shown in edit mode.
    private func removeBar(_ mgr: DownloadManager) -> some View {
        let selectedCount = selection.count + playlistSelection.count
        // As the tab bar: navy at 85% with a hairline on top, the button at the page margin.
        return VStack(spacing: 0) {
            Hairline()
            PrimaryButton(selectedCount == 0 ? "Remove selected" : "Remove \(selectedCount)",
                          enabled: selectedCount > 0, color: TetsubiColors.red, fullWidth: true) {
                confirmingRemoval = true
            }
            .padding(.horizontal, windowClass.pageMargin)
            .padding(.vertical, Spacing.m)
        }
        .frame(maxWidth: .infinity)
        .background(TetsubiColors.navy.opacity(0.85))
        .accessibilityIdentifier("DownloadsView.removeSelected")
    }

    /// Storage used and total playing time: one card.
    private func storageCard(_ mgr: DownloadManager) -> some View {
        let showsTotal = hasDownloads(mgr)
        return VStack(spacing: 0) {
            SettingsRow("Storage used", divider: showsTotal) {
                SettingsValue(DownloadPaths.formatBytes(mgr.totalDiskUsage))
            }
            if showsTotal {
                SettingsRow("Total playing time", divider: false) {
                    if let total = Self.totalDuration(of: mgr.completedAlbumIDs, durations: durations,
                                                      playlists: mgr.playlists.ownPlayingTime) {
                        SettingsValue(NowPlayingView.formatAlbumDuration(total))
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("DownloadsView.totalDuration")
                // Cards only read durations as they scroll into view; read
                // every album's here so the total covers them all.
                .task(id: mgr.completedAlbumIDs) {
                    for albumID in DownloadPaths.newestFirst(mgr.completedAlbumIDs) where durations[albumID] == nil {
                        durations[albumID] = await DownloadPaths.albumDuration(albumID: albumID) ?? 0
                    }
                }
            }
        }
        .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
        .clipShape(RoundedRectangle(cornerRadius: Radii.panel))
    }

    // MARK: - Rows

    @ViewBuilder
    private func downloadRow(_ state: DownloadAlbumState, mgr: DownloadManager) -> some View {
        let paused = state.status == .paused
        let failed = state.status == .failed
        DownloadingCard(
            art: mgr.artRequest(for: state.albumID, size: 100), title: state.title, subtitle: state.artistName,
            detail: state.format.displayName + " · "
                + String(format: "%d/%d tracks", state.completedTracks, state.expectedTrackCount),
            status: state.status, progress: state.progress, errorMessage: state.errorMessage,
            canToggle: state.status != .pending,
            toggleID: "DownloadsView.pause.\(state.albumID)",
            cancelID: "DownloadsView.cancel.\(state.albumID)",
            toggle: {
                if failed { mgr.retry(albumID: state.albumID) }
                else if paused { mgr.resume(albumID: state.albumID) }
                else { mgr.pause(albumID: state.albumID) }
            },
            cancel: { mgr.cancel(albumID: state.albumID) })
    }

    /// A playlist still downloading (or paused, or failed): as an album's
    /// row, counted in songs. Cancel removes what was downloaded for it.
    @ViewBuilder
    private func playlistDownloadRow(_ state: PlaylistDownloadState, playlists: PlaylistDownloads) -> some View {
        let paused = state.status == .paused
        let failed = state.status == .failed
        DownloadingCard(
            art: playlistArtRequest(state.playlistID, playlists: playlists), title: state.name, subtitle: "Playlist",
            detail: String(format: "%d/%d songs", state.completedTracks, state.expectedTrackCount),
            status: state.status, progress: state.progress, errorMessage: state.errorMessage,
            canToggle: true,
            toggleID: "DownloadsView.pausePlaylist.\(state.playlistID)",
            cancelID: "DownloadsView.cancelPlaylist.\(state.playlistID)",
            toggle: {
                if failed { playlists.retry(state.playlistID) }
                else if paused { playlists.resume(state.playlistID) }
                else { playlists.pause(state.playlistID) }
            },
            cancel: { playlists.remove(state.playlistID) })
    }

    // MARK: - Empty state

    private var emptyState: some View {
        EmptyState("No downloads yet", message: "Download an album from its detail page to play it offline.",
                   systemImage: "arrow.down.circle")
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

// MARK: - Pieces

/// In edit mode: an empty or ticked circle over the cover's corner.
private struct SelectionMark: View {
    let selected: Bool
    var body: some View {
        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 24.scaled))
            .foregroundStyle(selected ? TetsubiColors.blue : TetsubiColors.textPrimary)
            .shadow(color: .black.opacity(0.4), radius: 2)
            .padding(8)
    }
}

/// A downloading album or playlist: a surface card with its cover, names,
/// progress bar, Pause / Resume / Retry and Cancel.
private struct DownloadingCard: View {
    let art: URLRequest?
    let title: String
    let subtitle: String
    let detail: String
    let status: DownloadStatus
    let progress: Double
    let errorMessage: String?
    let canToggle: Bool
    let toggleID: String
    let cancelID: String
    let toggle: () -> Void
    let cancel: () -> Void

    @Environment(\.windowWidthClass) private var windowClass

    var body: some View {
        let paused = status == .paused
        let failed = status == .failed
        let percent = Int(progress * 100)
        let waitingForWiFi = DownloadNetworkRule.isWaitingForWiFi(
            status: status, allowCellular: PlaybackSettings.downloadOverCellular,
            isExpensive: NetworkMonitor.shared.isExpensive)
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(spacing: Spacing.m) {
                AlbumArtView(request: art, size: 40.scaled, cornerRadius: Radii.thumb,
                             placeholderColor: TetsubiColors.art)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(TetsubiType.rowTitle).foregroundStyle(TetsubiColors.textPrimary).lineLimit(1)
                    Text(subtitle).font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
                    Text(detail).font(TetsubiType.caption).foregroundStyle(TetsubiColors.textTertiary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // Retry (failed) / resume (paused) / pause
                Button(action: toggle) {
                    Image(systemName: failed ? "arrow.clockwise" : paused ? "play.fill" : "pause.fill")
                        .font(.system(size: 18.scaled))
                        .foregroundStyle(TetsubiColors.blue)
                        .frame(width: 40.scaled, height: 40.scaled)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(!canToggle)  // still fetching tracks
                .help(failed ? "Retry" : paused ? "Resume" : "Pause")
                .accessibilityLabel(failed ? "Retry download" : paused ? "Resume download" : "Pause download")
                .accessibilityIdentifier(toggleID)
                Button(role: .destructive, action: cancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 20.scaled))
                        .foregroundStyle(TetsubiColors.red)
                        .frame(width: 40.scaled, height: 40.scaled)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .help("Cancel")
                .accessibilityLabel("Cancel download")
                .accessibilityIdentifier(cancelID)
            }
            PulsingProgressBar(progress: progress, isPaused: paused || failed)
            Text(failed ? "Failed · \(percent)%"
                 : paused ? "Paused · \(percent)%"
                 : waitingForWiFi ? "Waiting for Wi‑Fi · \(percent)%"
                 : "\(percent)%")
                .font(TetsubiType.caption)
                .foregroundStyle(TetsubiColors.textSecondary)
            if let errorMessage, !errorMessage.isEmpty {
                Text(errorMessage).font(TetsubiType.caption).foregroundStyle(TetsubiColors.red).lineLimit(2)
            }
        }
        .padding(Spacing.m)
        .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
        .padding(.horizontal, windowClass.pageMargin)
        .padding(.vertical, Spacing.xs)
    }
}
