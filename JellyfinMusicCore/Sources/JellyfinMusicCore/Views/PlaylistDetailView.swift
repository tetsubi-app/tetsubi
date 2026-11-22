// MARK: - PlaylistDetailView — playlist art, track list, play/shuffle buttons

import SwiftUI
import Observation

/// Loads a playlist's tracks and plays them. Rows are known by their
/// position: the same song can be in a playlist twice.
@MainActor
@Observable
public final class PlaylistDetailViewModel {

    // MARK: Published state

    /// Its name changes when the playlist is renamed here.
    public private(set) var playlist: JellyfinPlaylist
    public private(set) var entries: [PlaylistEntry] = []
    public private(set) var isLoading = false
    /// The tracks have been fetched (the list may be empty).
    public private(set) var hasLoaded = false
    public private(set) var errorMessage: String?
    /// "Go to Album" couldn't fetch the album.
    public private(set) var albumErrorMessage: String?
    /// Opened offline: nothing is asked of the server.
    public private(set) var isOffline = false
    /// Set by "Go to Album" once the album is fetched; the view pushes it.
    public var albumToOpen: JellyfinAlbum?
    /// The user may change this playlist (asked of the server on load).
    public private(set) var canEdit = false
    /// Edit mode: reorder, remove, rename, delete.
    public private(set) var isEditing = false
    /// "Couldn't save your changes. <error>" / "Couldn't delete the
    /// playlist. <error>", after an edit failed.
    public private(set) var editErrorMessage: String?
    /// The playlist was deleted; the view leaves the page.
    public private(set) var isDeleted = false
    public private(set) var isDeleting = false

    // MARK: Dependencies

    let api: JellyfinAPI
    private let player: PlayerManager?
    private let isOnline: @MainActor () -> Bool
    private let server: PlaylistServer
    private let events: PlaylistEvents
    /// Downloaded playlists; nil where there are no downloads (previews, tests).
    private let downloads: PlaylistDownloads?
    /// The name the server has, to go back to when a rename fails.
    private var savedName: String
    /// Moves with every edit, so a fetch asked for before it is ignored.
    private var fetchGeneration = 0
    @ObservationIgnored private lazy var saveQueue = makeSaveQueue()

    // MARK: Init

    public convenience init(api: JellyfinAPI, playlist: JellyfinPlaylist, player: PlayerManager? = nil,
                downloads: PlaylistDownloads? = nil,
                isOnline: @escaping @MainActor () -> Bool = { NetworkMonitor.shared.isConnected }) {
        self.init(api: api, playlist: playlist, player: player, isOnline: isOnline, server: nil, events: nil,
                  downloads: downloads)
    }

    /// `server` and `events` stand in for the real ones in tests.
    init(api: JellyfinAPI, playlist: JellyfinPlaylist, player: PlayerManager? = nil,
         isOnline: @escaping @MainActor () -> Bool = { NetworkMonitor.shared.isConnected },
         server: PlaylistServer?, events: PlaylistEvents?, downloads: PlaylistDownloads? = nil) {
        self.api = api
        self.playlist = playlist
        self.player = player
        self.isOnline = isOnline
        self.server = server ?? PlaylistServer(api: api)
        self.events = events ?? .shared
        self.downloads = downloads
        self.savedName = playlist.name
    }

    // MARK: - Shown values

    /// "12 songs · 1 h 12 min", counted from the loaded tracks (the
    /// server's own figures until they arrive).
    public var summary: String {
        hasLoaded ? Self.summary(of: entries) : playlist.summary
    }

    nonisolated static func summary(of entries: [PlaylistEntry]) -> String {
        JellyfinPlaylist.summary(songCount: entries.count, duration: entries.totalDuration)
    }

    /// The queue was started from this playlist.
    var isThisPlaylistPlaying: Bool {
        guard let player, player.currentTrack != nil else { return false }
        return player.queueSourceId == playlist.id
    }

    /// The row that's playing — by position, so only one of two rows of
    /// the same song is marked.
    func isCurrent(_ entry: PlaylistEntry) -> Bool {
        guard let player, isThisPlaylistPlaying else { return false }
        return Self.isCurrent(entry, playingPosition: player.currentSourcePosition,
                              playingTrackId: player.currentTrack?.id)
    }

    nonisolated static func isCurrent(_ entry: PlaylistEntry, playingPosition: Int?,
                                      playingTrackId: String?) -> Bool {
        entry.position == playingPosition && entry.track.id == playingTrackId
    }

    var showsShuffle: Bool { !(player?.maniacMode ?? false) }

    // MARK: - Fetch

    /// Fetch the tracks, and whether the playlist may be changed. Left
    /// alone while the list is being edited or an edit is on its way: the
    /// page already shows the newer order.
    public func load() {
        guard !isEditing, saveQueue.isIdle, !isDeleting, !isDeleted else { return }
        fetch()
    }

    private func fetch() {
        isOffline = !isOnline()
        guard !isOffline else {
            showDownload()
            return
        }
        isLoading = !hasLoaded
        errorMessage = nil
        fetchGeneration += 1
        let asked = fetchGeneration
        let id = playlist.id
        server.tracks(id) { [weak self] result in
            DispatchQueue.main.async {
                guard let self, self.fetchGeneration == asked else { return }
                self.isLoading = false
                switch result {
                case .success(let entries):
                    self.entries = entries
                    self.hasLoaded = true
                    PlaylistArt.remember(firstEntry: entries.first, of: self.playlist)
                    // A downloaded playlist follows the server.
                    self.downloads?.sync(playlistID: id, entries: entries)
                case .failure(let error):
                    self.errorMessage = error.errorDescription ?? "\(error)"
                    // The server can't be reached: the download still opens.
                    if !self.hasLoaded { self.showDownload() }
                }
            }
        }
        server.canEdit(id) { [weak self] canEdit in
            DispatchQueue.main.async {
                guard let self else { return }
                self.canEdit = canEdit
                if !canEdit { self.isEditing = false }
            }
        }
    }

    /// Offline: a downloaded playlist's name and rows come from its manifest.
    private func showDownload() {
        guard let manifest = downloads?.manifests[playlist.id] else { return }
        if manifest.name != playlist.name { setName(manifest.name) }
        entries = manifest.playlistEntries
        hasLoaded = true
        isLoading = false
    }

    // MARK: - Download

    /// The download while it isn't complete (downloading, paused, failed).
    public var downloadState: PlaylistDownloadState? { downloads?.state(for: playlist.id) }

    /// Every song of the playlist is on this device.
    public var isDownloaded: Bool { downloads?.isDownloaded(playlist.id) ?? false }

    /// The Download button shows.
    public var showsDownload: Bool { downloads != nil }

    /// A download can start: the rows are here, there are some, and the
    /// server can be reached.
    public var canDownload: Bool {
        downloads != nil && hasLoaded && !entries.isEmpty && !isOffline
            && !isDownloaded && downloadState == nil && !isDeleting
    }

    public func startDownload() {
        guard canDownload else { return }
        downloads?.download(playlist: playlist, entries: entries)
    }

    public func removeDownload() {
        downloads?.remove(playlist.id)
    }

    /// The button under the progress bar: Retry, Resume or Pause.
    public func toggleDownload() {
        guard let state = downloadState else { return }
        switch state.status {
        case .failed: downloads?.retry(playlist.id)
        case .paused: downloads?.resume(playlist.id)
        default:      downloads?.pause(playlist.id)
        }
    }

    /// A row's picture: the saved one when the playlist is downloaded.
    public func artRequest(for entry: PlaylistEntry) -> URLRequest? {
        if let downloads, downloads.hasDownload(playlist.id) {
            _ = downloads.artVersion
            if let file = PlaylistDownloadPaths.savedArt(playlistID: playlist.id, itemID: entry.artItemId) {
                return URLRequest(url: file)
            }
        }
        return api.imageURLRequest(for: entry.artItemId, size: 100)
    }

    // MARK: - Editing

    /// The Edit button shows: the playlist is the user's to change, its
    /// tracks are here, and the server can be reached.
    public var showsEdit: Bool { canEdit && hasLoaded && !isOffline }

    public func beginEditing() {
        guard showsEdit else { return }
        editErrorMessage = nil
        isEditing = true
    }

    public func endEditing() {
        isEditing = false
    }

    /// Drag a row (or several) to a new place.
    public func move(fromOffsets source: IndexSet, toOffset destination: Int) {
        var changed = entries
        changed.move(fromOffsets: source, toOffset: destination)
        setOrder(changed)
    }

    /// Remove rows by their position.
    public func remove(atOffsets offsets: IndexSet) {
        var changed = entries
        changed.remove(atOffsets: offsets)
        setOrder(changed)
    }

    /// Show the new order at once and send the whole of it.
    private func setOrder(_ changed: [PlaylistEntry]) {
        guard canEdit, !isDeleting else { return }
        editErrorMessage = nil
        fetchGeneration += 1
        isLoading = false
        entries = Self.renumbered(changed)
        PlaylistArt.remember(firstEntry: entries.first, of: playlist)
        saveQueue.submit(.order(entries.map(\.track.id)))
    }

    /// The same rows with their positions counted again from 0, so a row
    /// is still known by where it is.
    nonisolated static func renumbered(_ entries: [PlaylistEntry]) -> [PlaylistEntry] {
        entries.enumerated().map { index, entry in
            PlaylistEntry(position: index, entryId: entry.entryId, track: entry.track,
                          albumName: entry.albumName, albumId: entry.albumId, albumArtist: entry.albumArtist)
        }
    }

    /// Rename (spaces at the ends removed; an empty name is ignored).
    public func rename(to raw: String) {
        guard canEdit, !isDeleting, let name = PlaylistName.cleaned(raw), name != playlist.name else { return }
        editErrorMessage = nil
        setName(name)
        saveQueue.submit(.name(name))
    }

    private func setName(_ name: String) {
        playlist = JellyfinPlaylist(id: playlist.id, name: name, songCount: playlist.songCount,
                                    runTimeTicks: playlist.runTimeTicks, dateCreated: playlist.dateCreated,
                                    hasArt: playlist.hasArt)
    }

    /// Delete the playlist from the server; `isDeleted` then closes the page.
    public func delete() {
        guard canEdit, !isDeleting else { return }
        isDeleting = true
        editErrorMessage = nil
        saveQueue.cancelAll()
        server.delete(playlist.id) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isDeleting = false
                switch result {
                case .success:
                    self.isEditing = false
                    self.isDeleted = true
                    // Its download goes with it.
                    self.downloads?.remove(self.playlist.id)
                    self.events.playlistsChanged()
                case .failure(let error):
                    self.editErrorMessage = PlaylistWording.deleteFailed(error: error.errorDescription ?? "\(error)")
                    // An edit may have been dropped for the delete: show what the server has.
                    self.fetch()
                }
            }
        }
    }

    private func makeSaveQueue() -> PlaylistSaveQueue {
        let id = playlist.id
        let server = server
        let queue = PlaylistSaveQueue { change, done in
            let finish: (Result<Void, JellyfinError>) -> Void = { result in
                DispatchQueue.main.async {
                    switch result {
                    case .success: done(nil)
                    case .failure(let error): done(error)
                    }
                }
            }
            switch change {
            case .order(let ids): server.setItems(id, ids, finish)
            case .name(let name): server.rename(id, name, finish)
            }
        }
        queue.onSaved = { [weak self] change in
            guard let self else { return }
            // A downloaded playlist follows the change.
            switch change {
            case .name(let name):
                self.savedName = name
                self.downloads?.renamed(playlistID: id, to: name)
            case .order:
                self.downloads?.sync(playlistID: id, entries: self.entries)
            }
            self.events.playlistsChanged()
        }
        queue.onFailure = { [weak self] error in
            guard let self else { return }
            // Back to what the server has.
            self.editErrorMessage = PlaylistWording.saveFailed(error: error.errorDescription ?? "\(error)")
            self.setName(self.savedName)
            self.fetch()
        }
        return queue
    }

    // MARK: - Actions

    /// Play the playlist from its first song ("Now playing" just opens
    /// the sheet).
    public func play() {
        guard let player, !entries.isEmpty else { return }
        if !isThisPlaylistPlaying, refused(player.play(playlist: playlist, tracks: entries, online: isOnline())) {
            return
        }
        player.isNowPlayingPresented = true
    }

    /// Offline, with the song (or every song) not on this device:
    /// nothing plays and the usual notice says why.
    private func refused(_ refusal: PlaylistPlayRefusal?) -> Bool {
        guard let refusal else { return false }
        events.show(refusal.message, isError: true)
        return true
    }

    /// Turn shuffle on and play the playlist in a new random order.
    public func shuffle() {
        guard let player, !entries.isEmpty else { return }
        let before = player.shuffle
        player.setShuffle(true)
        if refused(player.play(playlist: playlist, tracks: entries, online: isOnline())) {
            player.setShuffle(before)
            return
        }
        player.isNowPlayingPresented = true
    }

    /// Play the playlist from the tapped row.
    public func play(from entry: PlaylistEntry) {
        guard let player else { return }
        _ = refused(player.play(playlist: playlist, tracks: entries, startingAt: entry.position,
                                online: isOnline()))
    }

    /// Fetch the row's album and open its page.
    public func goToAlbum(of entry: PlaylistEntry) {
        guard let albumId = entry.albumId else { return }
        albumErrorMessage = nil
        api.album(albumId) { [weak self] result in
            DispatchQueue.main.async {
                switch result {
                // A lone disc opens as its whole album, as from Search.
                case .success(let album): self?.albumToOpen = DiscGrouping.merge([album])[0]
                case .failure(let error): self?.albumErrorMessage = error.errorDescription ?? "\(error)"
                }
            }
        }
    }
}

// MARK: - View

public struct PlaylistDetailView: View {

    // MARK: Inputs

    @State public var viewModel: PlaylistDetailViewModel
    private let player: PlayerManager?
    private let downloadManager: DownloadManager?
    @State private var isRenaming = false
    @State private var newName = ""
    @State private var isConfirmingDelete = false
    @State private var isConfirmingRemoveDownload = false
    #if os(macOS)
    @State private var selection: Set<Int> = []
    #endif
    @Environment(\.dismiss) private var dismiss

    // MARK: Init

    public init(api: JellyfinAPI, playlist: JellyfinPlaylist, player: PlayerManager? = nil,
                downloadManager: DownloadManager? = nil) {
        _viewModel = State(initialValue: PlaylistDetailViewModel(
            api: api, playlist: playlist, player: player, downloads: downloadManager?.playlists))
        self.player = player
        self.downloadManager = downloadManager
    }

    // MARK: Body

    public var body: some View {
        // A ZStack, not a Group: the load below must not start again each
        // time the page goes in or out of edit mode.
        ZStack {
            if viewModel.isEditing {
                editList
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16.scaled) {
                        headerSection
                        actionsSection
                        statusSection
                        tracksSection
                    }
                    .padding()
                    .padding(.bottom, 64.scaled)  // space for now-playing bar
                }
            }
        }
        .navigationTitle(viewModel.playlist.name)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationDestination(item: Binding(get: { viewModel.albumToOpen },
                                             set: { viewModel.albumToOpen = $0 })) { album in
            AlbumDetailView(api: viewModel.api, album: album, player: player, downloadManager: downloadManager)
                .appTheme()
        }
        .toolbar {
            if viewModel.showsEdit || viewModel.isEditing {
                ToolbarItem(placement: .primaryAction) {
                    Button(viewModel.isEditing ? "Done" : "Edit") {
                        if viewModel.isEditing { viewModel.endEditing() } else { viewModel.beginEditing() }
                    }
                    .accessibilityIdentifier("PlaylistDetailView.editButton")
                }
            }
        }
        .renamePlaylistPrompt(isPresented: $isRenaming, name: $newName) { viewModel.rename(to: $0) }
        .confirmationDialog(PlaylistWording.deleteTitle(playlist: viewModel.playlist.name),
                            isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { viewModel.delete() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(PlaylistWording.deleteMessage)
        }
        .onChange(of: viewModel.isDeleted) { _, deleted in
            if deleted { dismiss() }
        }
        // Again when the connection comes back.
        .task(id: NetworkMonitor.shared.isConnected) {
            viewModel.load()
        }
        // And when songs were added to a playlist (it may be this one).
        .onChange(of: PlaylistEvents.shared.version) { _, _ in
            viewModel.load()
        }
    }

    // MARK: - Edit mode

    /// The rows as a list to drag and remove from, with Rename above and
    /// Delete Playlist below.
    private var editList: some View {
        List(selection: editSelection) {
            if let error = viewModel.editErrorMessage {
                StatusBanner(systemImage: "exclamationmark.triangle", message: error)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }

            Section {
                Button {
                    newName = viewModel.playlist.name
                    isRenaming = true
                } label: {
                    HStack {
                        Text(viewModel.playlist.name)
                            .font(.plex(.body))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Spacer()
                        Text("Rename…")
                            .font(.plex(.subheadline))
                            .foregroundStyle(Color.appPink)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("PlaylistDetailView.renameButton")
                .listRowBackground(Color.appGroupBackground)
            }

            Section {
                ForEach(viewModel.entries) { entry in
                    editRow(entry)
                        .listRowBackground(Color.appGroupBackground)
                }
                .onMove { viewModel.move(fromOffsets: $0, toOffset: $1) }
                .onDelete { viewModel.remove(atOffsets: $0) }
            }

            Section {
                Button(role: .destructive) {
                    isConfirmingDelete = true
                } label: {
                    Label("Delete Playlist", systemImage: "trash")
                        .font(.plex(.body))
                }
                .accessibilityIdentifier("PlaylistDetailView.deleteButton")
                .listRowBackground(Color.appGroupBackground)
            }
        }
        .disabled(viewModel.isDeleting)
        #if os(iOS)
        .environment(\.editMode, .constant(.active))
        .contentMargins(.bottom, 64, for: .scrollContent)  // space for now-playing bar
        #else
        // The Delete key removes the selected rows.
        .onDeleteCommand {
            viewModel.remove(atOffsets: IndexSet(selection))
            selection = []
        }
        .padding(.bottom, 64.scaled)  // space for now-playing bar
        #endif
    }

    private var editSelection: Binding<Set<Int>>? {
        #if os(macOS)
        $selection
        #else
        nil
        #endif
    }

    private func editRow(_ entry: PlaylistEntry) -> some View {
        HStack(spacing: 10) {
            #if os(macOS)
            Button {
                selection = []
                viewModel.remove(atOffsets: IndexSet(integer: entry.position))
            } label: {
                Image(systemName: "minus.circle.fill")
                    .foregroundStyle(.red)
            }
            .buttonStyle(.plain)
            .help("Remove from the playlist")
            #endif

            AlbumArtView(request: viewModel.artRequest(for: entry), size: 40.scaled, cornerRadius: 4.scaled)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.track.displayName)
                    .font(.plex(.body))
                    .lineLimit(1)
                if !entry.detailLine.isEmpty {
                    Text(entry.detailLine)
                        .font(.plex(.caption2))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
        }
        .accessibilityIdentifier("PlaylistEditRow.\(entry.position)")
    }

    // MARK: - Sub-views

    private var headerSection: some View {
        HStack(alignment: .bottom, spacing: 16.scaled) {
            PlaylistArtView(api: viewModel.api, playlist: viewModel.playlist,
                            size: 200.scaled, cornerRadius: 12.scaled, requestSize: 500,
                            firstEntry: viewModel.entries.first)

            VStack(alignment: .leading, spacing: 6.scaled) {
                Text(viewModel.playlist.name)
                    .font(.plex(.title2).bold())
                Text(viewModel.summary)
                    .font(.plex(.headline))
                    .foregroundStyle(.secondary)
                Spacer()
            }
        }
    }

    private var actionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            actionButtons
            downloadProgressSection
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            playButtons
                .disabled(viewModel.entries.isEmpty)
            if viewModel.showsDownload {
                downloadButton
            }
        }
    }

    /// "Download", "Downloading" (with the progress below), or
    /// "Downloaded", which offers to remove the download.
    private var downloadButton: some View {
        let isDownloaded = viewModel.isDownloaded
        let isDownloading = viewModel.downloadState != nil
        return Button {
            if isDownloaded {
                isConfirmingRemoveDownload = true
            } else {
                viewModel.startDownload()
            }
        } label: {
            HStack {
                Image(systemName: isDownloaded ? "checkmark.circle.fill"
                      : isDownloading ? "arrow.down.circle.dotted" : "arrow.down.circle")
                Text(isDownloaded ? "Downloaded" : isDownloading ? "Downloading" : "Download")
            }
            .font(.plex(.subheadline))
            .foregroundStyle(isDownloaded ? .green : .primary)
        }
        .buttonStyle(.bordered)
        .disabled(!isDownloaded && !viewModel.canDownload)
        .accessibilityIdentifier("PlaylistDetailView.downloadButton")
        .help(isDownloaded ? "Remove this download" : isDownloading ? "Downloading…" : "Download this playlist")
        .confirmationDialog("Remove this download?", isPresented: $isConfirmingRemoveDownload,
                            titleVisibility: .visible) {
            Button("Remove", role: .destructive) { viewModel.removeDownload() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It'll be deleted from this device. You can download it again from the playlist page.")
        }
    }

    /// The bar, "3/12 songs" and Pause / Resume / Retry while the
    /// download isn't complete.
    @ViewBuilder
    private var downloadProgressSection: some View {
        if let state = viewModel.downloadState {
            let paused = state.status == .paused
            let failed = state.status == .failed
            PulsingProgressBar(progress: state.progress, isPaused: paused || failed)
            let waiting = DownloadNetworkRule.isWaitingForWiFi(
                status: state.status, allowCellular: PlaybackSettings.downloadOverCellular,
                isExpensive: NetworkMonitor.shared.isExpensive)
            HStack {
                Text(DownloadNetworkRule.statusText(failed: failed, paused: paused, waitingForWiFi: waiting))
                    .font(.plex(.caption))
                Text("\(state.completedTracks)/\(state.expectedTrackCount) songs")
                    .font(.plex(.caption2))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(failed ? "Retry" : paused ? "Resume" : "Pause") {
                    viewModel.toggleDownload()
                }
                .font(.plex(.caption))
                .buttonStyle(.bordered)
                .accessibilityIdentifier("PlaylistDetailView.pauseDownload")
            }
            if let err = state.errorMessage, !err.isEmpty {
                Text(err)
                    .font(.plex(.caption2))
                    .foregroundStyle(.red)
            }
        }
    }

    private var playButtons: some View {
        Group {
            Button {
                viewModel.play()
            } label: {
                HStack {
                    Image(systemName: viewModel.isThisPlaylistPlaying ? "waveform" : "play.fill")
                        .imageScale(.large)
                    Text(viewModel.isThisPlaylistPlaying ? "Now playing" : "Play")
                }
                .font(.plex(.subheadline))
            }
            .buttonStyle(.borderedProminent)
            .tint(viewModel.isThisPlaylistPlaying ? Color.appPink : nil)
            .accessibilityIdentifier("PlaylistDetailView.playButton")
            .help("Play the whole playlist")

            // Maniac Mode plays in order, so there's no Shuffle.
            if viewModel.showsShuffle {
                Button {
                    viewModel.shuffle()
                } label: {
                    HStack {
                        Image(systemName: "shuffle")
                        Text("Shuffle")
                    }
                    .font(.plex(.subheadline))
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("PlaylistDetailView.shuffleButton")
                .help("Play the playlist in a random order")
            }
        }
    }

    /// Offline, a failed load (with Retry), a failed edit, or a failed
    /// "Go to Album".
    @ViewBuilder
    private var statusSection: some View {
        Group {
            if viewModel.isOffline, !viewModel.hasLoaded {
                StatusBanner.offline
            } else if let error = viewModel.errorMessage {
                StatusBanner(systemImage: "exclamationmark.triangle",
                             message: "Couldn't load this playlist. \(error)",
                             actionTitle: "Retry") { viewModel.load() }
            } else if let error = viewModel.editErrorMessage {
                StatusBanner(systemImage: "exclamationmark.triangle", message: error)
            } else if let error = viewModel.albumErrorMessage {
                StatusBanner(systemImage: "exclamationmark.triangle",
                             message: "Couldn't open the album. \(error)")
            }
        }
        // The banner brings its own side padding; the page already has it.
        .padding(.horizontal, -16)
    }

    @ViewBuilder
    private var tracksSection: some View {
        if viewModel.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding()
        } else if viewModel.hasLoaded, viewModel.entries.isEmpty {
            Text("This playlist is empty.")
                .font(.plex(.footnote))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding()
        } else {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(viewModel.entries) { entry in
                    PlaylistTrackRow(entry: entry,
                                     artRequest: viewModel.artRequest(for: entry),
                                     isPlaying: viewModel.isCurrent(entry)) {
                        viewModel.play(from: entry)
                    }
                    // Long-press on iOS, right-click on the Mac.
                    .contextMenu {
                        // Offline, playlists can't be changed.
                        if !viewModel.isOffline {
                            Button {
                                PlaylistEvents.shared.add(trackIds: [entry.track.id])
                            } label: {
                                Label("Add to Playlist…", systemImage: "text.badge.plus")
                            }
                        }
                        if entry.albumId != nil {
                            Button {
                                viewModel.goToAlbum(of: entry)
                            } label: {
                                Label("Go to Album", systemImage: "square.stack")
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - PlaylistTrackRow

/// A row of the playlist: small album art, title, "artist · album".
/// Tapping it plays the playlist from that row.
struct PlaylistTrackRow: View {
    let entry: PlaylistEntry
    let artRequest: URLRequest?
    let isPlaying: Bool
    let onTap: () -> Void

    var body: some View {
        Button {
            onTap()
        } label: {
            HStack(spacing: 10.scaled) {
                AlbumArtView(request: artRequest, size: 40.scaled, cornerRadius: 4.scaled)

                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.track.displayName)
                        .font(.plex(.body))
                        .fontWeight(isPlaying ? .semibold : .regular)
                        .foregroundStyle(isPlaying ? .white : .primary)
                        .lineLimit(1)
                    if !entry.detailLine.isEmpty {
                        Text(entry.detailLine)
                            .font(.plex(.caption2))
                            .foregroundStyle(isPlaying ? Color.white.opacity(0.85) : .secondary)
                            .lineLimit(1)
                    }
                }

                Spacer()

                Text(TrackRow.formatDuration(entry.track.duration))
                    .font(.plex(.caption))
                    .foregroundStyle(isPlaying ? Color.white.opacity(0.85) : .secondary)
            }
            .padding(.vertical, 6.scaled)
            .padding(.horizontal, 8.scaled)
            // Playing row: solid pink with white text, legible over the blur.
            .background(isPlaying ? Color.appPink : Color.gray.opacity(0.05))
            .cornerRadius(6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("PlaylistTrackRow.\(entry.position)")
    }
}
