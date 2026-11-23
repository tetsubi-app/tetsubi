// MARK: - DownloadsView — live + completed download list

import SwiftUI
import Observation

/// The Downloads tab.
///
/// Sections:
///   **Downloading** — playlists and albums currently in progress, with
///                     a progress bar and a cancel button.
///   **Playlists**   — playlists that have been fully downloaded; a row
///                     opens the playlist's page.
///   **Downloaded**  — albums that have been fully downloaded,
///                     with format, size, and a delete button.
///   **Storage**     — total disk usage at the bottom.
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
    /// Album durations, filled in as rows appear (read from the files).
    @State private var durations: [String: TimeInterval] = [:]

    // MARK: -

    public init(downloadManager: DownloadManager? = nil, player: PlayerManager? = nil) {
        self.downloadManager = downloadManager
        self.player         = player
    }

    // MARK: - Body

    public var body: some View {
        Group {
            if let mgr = downloadManager {
                if mgr.activeDownloads.isEmpty && mgr.completedAlbumIDs.isEmpty
                    && mgr.playlists.manifests.isEmpty {
                    emptyState
                } else {
                    List {
                        if !mgr.activeDownloads.isEmpty || !mgr.playlists.active.isEmpty {
                            downloadingSection(mgr)
                                .listRowBackground(Color.appGroupBackground)
                        }
                        if !mgr.playlists.downloadedIDs.isEmpty {
                            playlistsSection(mgr)
                                .listRowBackground(Color.appGroupBackground)
                        }
                        downloadedSection(mgr)
                            .listRowBackground(Color.appGroupBackground)
                        storageSection(mgr)
                            .listRowBackground(Color.appGroupBackground)
                    }
                    .scrollContentBackground(.hidden)
                    .readableWidthOnMac()
                }
            } else {
                emptyState
            }
        }
        .untitledTab()
        .onAppear {
            downloadManager?.refreshDownloaded()
            downloadManager?.playlists.refresh()
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

    // MARK: - Sections

    @ViewBuilder
    private func downloadingSection(_ mgr: DownloadManager) -> some View {
        Section("Downloading") {
            ForEach(mgr.playlists.active.values.sorted { $0.name < $1.name }, id: \.playlistID) { state in
                playlistDownloadRow(state, playlists: mgr.playlists)
            }
            ForEach(Array(mgr.activeDownloads.values), id: \.albumID) { state in
                downloadRow(state, mgr: mgr)
            }
        }
    }

    /// Downloaded playlists, by name. A row opens the playlist's page; in
    /// edit mode it's ticked for removal.
    @ViewBuilder
    private func playlistsSection(_ mgr: DownloadManager) -> some View {
        Section {
            ForEach(PlaylistsViewModel.sorted(mgr.playlists.downloadedPlaylists, by: .name)) { playlist in
                if isEditing {
                    playlistRow(playlist, mgr: mgr)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if !playlistSelection.insert(playlist.id).inserted {
                                playlistSelection.remove(playlist.id)
                            }
                        }
                } else {
                    NavigationLink(value: playlist) {
                        playlistRow(playlist, mgr: mgr)
                    }
                    .playlistQueueMenu(playlist)
                    .accessibilityIdentifier("DownloadsView.playlist.\(playlist.id)")
                }
            }
        } header: {
            HStack {
                Text("Playlists")
                Spacer()
                editButton
            }
        }
    }

    private func playlistRow(_ playlist: JellyfinPlaylist, mgr: DownloadManager) -> some View {
        HStack(spacing: 12.scaled) {
            if isEditing {
                let selected = playlistSelection.contains(playlist.id)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.plex(.title3))
                    .foregroundStyle(selected ? Color.appBlue : .secondary)
                    .accessibilityIdentifier("DownloadsView.selectPlaylist.\(playlist.id)")
            }
            AlbumArtView(request: playlistArtRequest(playlist.id, playlists: mgr.playlists),
                         size: 56.scaled, cornerRadius: 6.scaled)
            VStack(alignment: .leading, spacing: 2) {
                Text(playlist.name)
                    .font(.plex(.subheadline).bold())
                    .lineLimit(1)
                Text(Self.playlistDetailLine(playlist, size: mgr.playlists.size(playlist.id)))
                    .font(.plex(.caption))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 4.scaled)
    }

    /// A downloaded playlist's saved picture.
    private func playlistArtRequest(_ playlistID: String, playlists: PlaylistDownloads) -> URLRequest? {
        _ = playlists.artVersion
        return PlaylistDownloadPaths.savedCover(playlistID: playlistID).map { URLRequest(url: $0) }
    }

    /// Edit / Done, on the first of the two downloaded sections.
    private var editButton: some View {
        Button(isEditing ? "Done" : "Edit") {
            isEditing.toggle()
            selection = []
            playlistSelection = []
        }
        .textCase(nil)
        .accessibilityIdentifier("DownloadsView.edit")
    }

    @ViewBuilder
    private func downloadedSection(_ mgr: DownloadManager) -> some View {
        let selectedCount = selection.count + playlistSelection.count
        Section {
            // Newest download first.
            ForEach(DownloadPaths.newestFirst(mgr.completedAlbumIDs), id: \.self) { albumID in
                let size = DownloadPaths.totalSize(albumID: albumID)
                let count = DownloadPaths.fileCount(albumID: albumID)
                HStack(spacing: 12.scaled) {
                    if isEditing {
                        Image(systemName: selection.contains(albumID) ? "checkmark.circle.fill" : "circle")
                            .font(.plex(.title3))
                            .foregroundStyle(selection.contains(albumID) ? Color.appBlue : .secondary)
                            .accessibilityIdentifier("DownloadsView.select.\(albumID)")
                    }
                    Button {
                        QueueActions.playDownloaded(id: albumID, title: mgr.albumInfo(for: albumID)?.title ?? "",
                                                    artist: mgr.albumInfo(for: albumID)?.artist ?? "")
                    } label: {
                        AlbumArtView(request: mgr.artRequest(for: albumID), size: 56.scaled, cornerRadius: 6.scaled)
                    }
                    .buttonStyle(.plain)
                    .disabled(isEditing)
                    .accessibilityLabel("Play album")
                    .accessibilityIdentifier("DownloadsView.cover.\(albumID)")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mgr.albumInfo(for: albumID)?.title ?? "Album \(albumID.prefix(6))")
                            .font(.plex(.subheadline).bold())
                            .lineLimit(1)
                        if let artist = mgr.albumInfo(for: albumID)?.artist, !artist.isEmpty {
                            Text(artist)
                                .font(.plex(.caption))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Text(Self.detailLine(count: count, duration: durations[albumID], size: size))
                            .font(.plex(.caption))
                            .foregroundStyle(.secondary)
                    }
                    .task(id: count) {
                        if let d = await DownloadPaths.albumDuration(albumID: albumID) {
                            durations[albumID] = d
                        }
                    }
                    Spacer()
                    if !isEditing {
                        Button {
                            QueueActions.playDownloaded(id: albumID, title: mgr.albumInfo(for: albumID)?.title ?? "",
                                                    artist: mgr.albumInfo(for: albumID)?.artist ?? "")
                        } label: {
                            Image(systemName: "play.fill")
                                .font(.plex(.title2))
                        }
                        .buttonStyle(.plain)
                        .help("Play locally")
                        .accessibilityIdentifier("DownloadsView.play.\(albumID)")
                    }
                }
                .padding(.vertical, 4.scaled)
                .contentShape(Rectangle())
                .onTapGesture {
                    guard isEditing else { return }
                    if !selection.insert(albumID).inserted { selection.remove(albumID) }
                }
                .contextMenu {
                    if !isEditing, player != nil {
                        QueueMenuItems {
                            QueueActions.queue(downloadedAlbumIDs: [albumID], sourceId: albumID,
                                               title: mgr.albumInfo(for: albumID)?.title ?? "",
                                               artist: mgr.albumInfo(for: albumID)?.artist ?? "", $0)
                        }
                    }
                }
            }
            if isEditing {
                Button(role: .destructive) {
                    confirmingRemoval = true
                } label: {
                    Text(selectedCount == 0 ? "Remove selected" : "Remove selected (\(selectedCount))")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .disabled(selectedCount == 0)
                .accessibilityIdentifier("DownloadsView.removeSelected")
                .confirmationDialog(Self.removalTitle(count: selectedCount),
                                    isPresented: $confirmingRemoval,
                                    titleVisibility: .visible) {
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
        } header: {
            HStack {
                Text("Downloaded")
                Spacer()
                // With downloaded playlists, their section (above) has it.
                if !mgr.completedAlbumIDs.isEmpty, mgr.playlists.downloadedIDs.isEmpty {
                    editButton
                }
            }
        }
    }

    @ViewBuilder
    private func storageSection(_ mgr: DownloadManager) -> some View {
        Section {
            HStack {
                Text("Storage used")
                    .foregroundStyle(.secondary)
                Spacer()
                Text(DownloadPaths.formatBytes(mgr.totalDiskUsage))
                    .font(.plex(.subheadline).monospacedDigit())
            }
            if !mgr.completedAlbumIDs.isEmpty || !mgr.playlists.downloadedIDs.isEmpty {
                HStack {
                    Text("Total playing time")
                        .foregroundStyle(.secondary)
                    Spacer()
                    if let total = Self.totalDuration(of: mgr.completedAlbumIDs, durations: durations,
                                                      playlists: mgr.playlists.ownPlayingTime) {
                        Text(NowPlayingView.formatAlbumDuration(total))
                            .font(.plex(.subheadline).monospacedDigit())
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("DownloadsView.totalDuration")
                // Rows only read durations as they scroll into view; read
                // every album's here so the total covers them all.
                .task(id: mgr.completedAlbumIDs) {
                    for albumID in DownloadPaths.newestFirst(mgr.completedAlbumIDs) where durations[albumID] == nil {
                        durations[albumID] = await DownloadPaths.albumDuration(albumID: albumID) ?? 0
                    }
                }
            }
        }
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

    // MARK: - Row

    @ViewBuilder
    private func downloadRow(_ state: DownloadAlbumState, mgr: DownloadManager) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12.scaled) {
                AlbumArtView(request: mgr.artRequest(for: state.albumID), size: 56.scaled, cornerRadius: 6.scaled)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.title)
                        .font(.plex(.subheadline).bold())
                        .lineLimit(1)
                    Text(state.artistName)
                        .font(.plex(.caption))
                        .foregroundStyle(.secondary)
                    Text(state.format.displayName + " · "
                            + String(format: "%d/%d tracks", state.completedTracks, state.expectedTrackCount))
                        .font(.plex(.caption2))
                        .foregroundStyle(.tertiary)
                }
                Spacer()
                // Retry (failed) / pause / resume button
                let paused = state.status == .paused
                let failed = state.status == .failed
                Button {
                    if failed {
                        mgr.retry(albumID: state.albumID)
                    } else if paused {
                        mgr.resume(albumID: state.albumID)
                    } else {
                        mgr.pause(albumID: state.albumID)
                    }
                } label: {
                    Image(systemName: failed ? "arrow.clockwise"
                                     : paused ? "play.fill" : "pause.fill")
                        .font(.plex(.title2))
                        .foregroundStyle(Color.appBlue)
                        .frame(width: 28.scaled)  // icons swap without shifting the row
                }
                .buttonStyle(.plain)
                .disabled(state.status == .pending)  // still fetching tracks
                .help(failed ? "Retry" : paused ? "Resume" : "Pause")
                .accessibilityLabel(failed ? "Retry download" : paused ? "Resume download" : "Pause download")
                .accessibilityIdentifier("DownloadsView.pause.\(state.albumID)")
                // Cancel button
                Button(role: .destructive) {
                    mgr.cancel(albumID: state.albumID)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.plex(.title3))
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
                .help("Cancel")
                .accessibilityIdentifier("DownloadsView.cancel.\(state.albumID)")
            }

            PulsingProgressBar(progress: state.progress,
                               isPaused: state.status == .paused || state.status == .failed)
            let waitingForWiFi = DownloadNetworkRule.isWaitingForWiFi(
                status: state.status, allowCellular: PlaybackSettings.downloadOverCellular,
                isExpensive: NetworkMonitor.shared.isExpensive)
            Text(state.status == .failed ? "Failed · \(Int(state.progress * 100))%"
                 : state.status == .paused ? "Paused · \(Int(state.progress * 100))%"
                 : waitingForWiFi ? "Waiting for Wi‑Fi · \(Int(state.progress * 100))%"
                 : "\(Int(state.progress * 100))%")
                .font(.plex(.caption))
                .foregroundStyle(.secondary)

            if let err = state.errorMessage, !err.isEmpty {
                Text(err)
                    .font(.plex(.caption2))
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4.scaled)
    }

    /// A playlist still downloading (or paused, or failed): as an album's
    /// row, counted in songs. Cancel removes what was downloaded for it.
    @ViewBuilder
    private func playlistDownloadRow(_ state: PlaylistDownloadState, playlists: PlaylistDownloads) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12.scaled) {
                AlbumArtView(request: playlistArtRequest(state.playlistID, playlists: playlists),
                             size: 56.scaled, cornerRadius: 6.scaled)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.name)
                        .font(.plex(.subheadline).bold())
                        .lineLimit(1)
                    Text("Playlist")
                        .font(.plex(.caption))
                        .foregroundStyle(.secondary)
                    Text(String(format: "%d/%d songs", state.completedTracks, state.expectedTrackCount))
                        .font(.plex(.caption2))
                        .foregroundStyle(.tertiary)
                }
                Spacer()
                let paused = state.status == .paused
                let failed = state.status == .failed
                Button {
                    if failed {
                        playlists.retry(state.playlistID)
                    } else if paused {
                        playlists.resume(state.playlistID)
                    } else {
                        playlists.pause(state.playlistID)
                    }
                } label: {
                    Image(systemName: failed ? "arrow.clockwise"
                                     : paused ? "play.fill" : "pause.fill")
                        .font(.plex(.title2))
                        .foregroundStyle(Color.appBlue)
                        .frame(width: 28.scaled)  // icons swap without shifting the row
                }
                .buttonStyle(.plain)
                .help(failed ? "Retry" : paused ? "Resume" : "Pause")
                .accessibilityLabel(failed ? "Retry download" : paused ? "Resume download" : "Pause download")
                .accessibilityIdentifier("DownloadsView.pausePlaylist.\(state.playlistID)")
                Button(role: .destructive) {
                    playlists.remove(state.playlistID)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.plex(.title3))
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
                .help("Cancel")
                .accessibilityIdentifier("DownloadsView.cancelPlaylist.\(state.playlistID)")
            }

            PulsingProgressBar(progress: state.progress,
                               isPaused: state.status == .paused || state.status == .failed)
            let waitingForWiFi = DownloadNetworkRule.isWaitingForWiFi(
                status: state.status, allowCellular: PlaybackSettings.downloadOverCellular,
                isExpensive: NetworkMonitor.shared.isExpensive)
            Text(state.status == .failed ? "Failed · \(Int(state.progress * 100))%"
                 : state.status == .paused ? "Paused · \(Int(state.progress * 100))%"
                 : waitingForWiFi ? "Waiting for Wi‑Fi · \(Int(state.progress * 100))%"
                 : "\(Int(state.progress * 100))%")
                .font(.plex(.caption))
                .foregroundStyle(.secondary)

            if let err = state.errorMessage, !err.isEmpty {
                Text(err)
                    .font(.plex(.caption2))
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4.scaled)
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "arrow.down.circle")
                .font(.system(size: 44.scaled))
                .foregroundStyle(.secondary)
            Text("No downloads yet")
                .font(.plex(.headline))
                .foregroundStyle(.secondary)
            Text("Download an album from its detail page to play it offline.")
                .font(.plex(.caption))
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding()
    }
}
