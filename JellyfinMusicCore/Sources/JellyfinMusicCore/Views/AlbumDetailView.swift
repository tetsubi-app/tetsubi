// MARK: - AlbumDetailView — album cover, track list, play/download buttons

import SwiftUI
import Observation

/// Shows an album's cover, metadata, track list, and play/download buttons.
///
/// Phase 3 wires the `Play` button to `PlayerManager.play(album:)`.
/// Phase 4 wires the `Download` button to `DownloadManager`; downloads use
/// the app-wide default format from Settings.
@MainActor
@Observable
public final class AlbumDetailViewModel {

    // MARK: Published state

    public private(set) var album: JellyfinAlbum
    public private(set) var tracks: [JellyfinTrack] = []
    /// The real albums behind this page: one, or each disc of a multi-disc set.
    public private(set) var discs: [JellyfinAlbum]
    /// Tracks grouped by disc, in disc order (one group for a normal album).
    public private(set) var tracksByDisc: [(disc: JellyfinAlbum, tracks: [JellyfinTrack])] = []
    /// One album whose tracks are tagged disc 1, disc 2… (e.g. The Fragile):
    /// tracks grouped by that tag. Nil for single-disc albums and for discs
    /// stored as separate albums (those use `tracksByDisc`).
    public private(set) var tracksByDiscNumber: [(number: Int, tracks: [JellyfinTrack])]?

    public private(set) var isLoadingTracks = false
    public private(set) var isFavorite = false
    /// Other albums by the same artist (max 12), newest first.
    public private(set) var moreByArtist: [JellyfinAlbum] = []
    /// "About <artist>" (MusicBrainz + Wikipedia); nil until loaded or if none.
    public private(set) var artistInfo: ArtistInfo?
    public private(set) var errorMessage: String?
    /// Opened offline: tracks come from downloads, and the favourite check,
    /// "More by" and "About" aren't asked for.
    public private(set) var isOffline = false
    /// Discs whose shown tracks were built from their download (offline,
    /// or the server failed); tapping those rows plays from disk.
    public private(set) var discsFromDownload: Set<String> = []

    /// Cached album-art request for this album, built once the view model
    /// is created. `nil` if the API has no userId / no token.
    public let albumArtRequest: URLRequest?

    // MARK: Dependencies

    let api: JellyfinAPI
    private let player: PlayerManager?
    let downloadManager: DownloadManager?
    private let isOnline: @MainActor () -> Bool

    // MARK: Init

    public init(api: JellyfinAPI, album: JellyfinAlbum, player: PlayerManager? = nil, downloadManager: DownloadManager? = nil,
                isOnline: @escaping @MainActor () -> Bool = { NetworkMonitor.shared.isConnected }) {
        self.api = api
        self.album = album
        self.discs = album.allDiscs
        self.player = player
        self.downloadManager = downloadManager
        self.isOnline = isOnline
        // Build the art request once up-front so the view can render
        // without waiting on the track-list fetch.
        self.albumArtRequest = api.imageURLRequest(for: album.id, size: 600)
    }

    /// Whether this page shows more than one disc.
    public var isMultiDisc: Bool { discs.count > 1 }

    /// True while the player's current track belongs to this album (any disc).
    var isThisAlbumPlaying: Bool {
        guard let player else { return false }
        return Self.isAlbumPlaying(albumIds: discs.map(\.id) + [album.id],
                                   playingAlbumId: player.currentTrack?.albumId,
                                   queueSourceId: player.queueSourceId, queue: player.queue)
    }

    /// Whether the album with `albumIds` (its own and its discs') is the
    /// one playing. Not when the queue was started from a playlist, even
    /// while one of the album's songs plays: a playlist's ID isn't the
    /// album of any queued track.
    nonisolated static func isAlbumPlaying(albumIds: [String], playingAlbumId: String?,
                                           queueSourceId: String?, queue: [PlaybackTrack]) -> Bool {
        guard let playingAlbumId, albumIds.contains(playingAlbumId) else { return false }
        guard let queueSourceId, !albumIds.contains(queueSourceId) else { return true }
        return queue.contains { $0.albumId == queueSourceId }
    }

    /// Maniac Mode: tracks can't be started individually.
    var canPlayIndividualTracks: Bool { !(player?.maniacMode ?? false) }

    func isCurrent(_ track: JellyfinTrack) -> Bool {
        isThisAlbumPlaying && player?.currentTrack?.id == track.id
    }

    /// "Disc 2" heading number for a disc (falls back to its position).
    public func discNumber(of disc: JellyfinAlbum) -> Int {
        DiscGrouping.discNumber(of: disc) ?? ((discs.firstIndex { $0.id == disc.id } ?? 0) + 1)
    }

    /// Loads everything the page shows. A page opened from a single disc
    /// (e.g. only disc 2 was in a Home row) first finds the other discs among
    /// the artist's albums, then loads tracks for all of them.
    /// Offline, only the tracks are loaded (from downloads).
    public func load() {
        isOffline = !isOnline()
        if isOffline {
            fetchTracks()
            return
        }
        fetchFavorite()
        fetchArtistInfo()
        let own = album.discAlbums.first ?? album
        let looksLikeDisc = DiscGrouping.parse(own.displayName) != nil
        guard let artistId = album.artistId else {
            fetchTracks()
            return
        }
        if !looksLikeDisc || isMultiDisc { fetchTracks() }
        else { isLoadingTracks = true }

        let currentIds = Set(discs.map(\.id))
        api.albums(forArtist: artistId) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                let found = (try? result.get()) ?? []
                if looksLikeDisc {
                    let siblings = DiscGrouping.siblings(of: own, among: found)
                    if siblings.count > 1, Set(siblings.map(\.id)) != currentIds {
                        let combined = DiscGrouping.combined(siblings)
                        self.album = combined
                        self.discs = combined.allDiscs
                        self.fetchTracks()
                    } else if !self.isMultiDisc {
                        self.fetchTracks()
                    }
                }
                let shown = Set(self.discs.map(\.id))
                self.moreByArtist = Array(DiscGrouping.merge(found.filter { !shown.contains($0.id) }).prefix(12))
            }
        }
    }

    /// Uses the server's MusicBrainz ID for the artist when it has one,
    /// otherwise searches MusicBrainz by name.
    private func fetchArtistInfo() {
        let name = album.artistName
        guard !name.isEmpty, artistInfo == nil else { return }
        let artistId = album.artistId
        Task { @MainActor [weak self, api] in
            let mbid: String? = if let artistId { await api.musicBrainzArtistID(artistId: artistId) } else { nil }
            let info = await ArtistInfoClient.shared.info(name: name, musicBrainzID: mbid)
            self?.artistInfo = info
        }
    }

    // MARK: - Actions

    /// Fetches the track list for every disc, keeping disc order. A
    /// downloaded disc's tracks come from its files when offline (nothing
    /// is asked of the server) or when the server fails.
    public func fetchTracks() {
        isLoadingTracks = true
        errorMessage = nil
        let discs = self.discs
        let offline = !isOnline()
        let downloaded = Set(discs.filter(isDiscDownloaded).map(\.id))
        let titles = Dictionary(discs.map { ($0.id, $0.displayName) }, uniquingKeysWith: { a, _ in a })
        var results = [[JellyfinTrack]?](repeating: nil, count: discs.count)
        var fromDownload: Set<String> = []
        var firstError: String?
        // The disc's download, if it has one with files.
        func fromDisk(_ id: String) -> [JellyfinTrack]? {
            guard downloaded.contains(id) else { return nil }
            let found = DownloadedTracks.tracks(albumID: id, albumTitle: titles[id])
            return found.isEmpty ? nil : found
        }
        let group = DispatchGroup()
        for (i, disc) in discs.enumerated() {
            if offline, let local = fromDisk(disc.id) {
                results[i] = local
                fromDownload.insert(disc.id)
                continue
            }
            group.enter()
            api.tracksForAlbum(disc.id) { result in
                DispatchQueue.main.async {
                    switch result {
                    case .success(let found): results[i] = found
                    case .failure(let err):
                        print("[AlbumDetailView] fetchTracks(\(disc.id)) failed: \(err.errorDescription ?? "\(err)")")
                        if let local = fromDisk(disc.id) {
                            results[i] = local
                            fromDownload.insert(disc.id)
                        } else {
                            firstError = firstError ?? err.errorDescription
                        }
                    }
                    group.leave()
                }
            }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self, self.discs.map(\.id) == discs.map(\.id) else { return }
            self.discsFromDownload = fromDownload
            self.show(discs: discs, results: results)
            self.errorMessage = self.tracks.isEmpty ? firstError : nil
            self.isLoadingTracks = false
            print("[AlbumDetailView] loaded \(self.tracks.count) tracks over \(discs.count) disc(s)")
            self.loadDownloadedDurations(discs: discs, results: results, titles: titles)
        }
    }

    private func show(discs: [JellyfinAlbum], results: [[JellyfinTrack]?]) {
        tracksByDisc = zip(discs, results).compactMap { disc, tracks in
            tracks.map { (disc, $0) }
        }
        tracks = tracksByDisc.flatMap(\.tracks)
        tracksByDiscNumber = discs.count == 1 ? DiscGrouping.byDiscNumber(tracks) : nil
    }

    /// Downloaded files carry no server runtime: read each one's length
    /// from disk, then show the tracks again with it.
    private func loadDownloadedDurations(discs: [JellyfinAlbum], results: [[JellyfinTrack]?],
                                         titles: [String: String]) {
        let ids = discsFromDownload
        guard !ids.isEmpty else { return }
        Task { @MainActor [weak self] in
            var results = results
            for (i, disc) in discs.enumerated() where ids.contains(disc.id) {
                let durations = await DownloadedTracks.durations(albumID: disc.id)
                guard !durations.isEmpty else { continue }
                results[i] = DownloadedTracks.tracks(albumID: disc.id, albumTitle: titles[disc.id],
                                                     durations: durations)
            }
            guard let self, self.discs.map(\.id) == discs.map(\.id),
                  self.discsFromDownload == ids else { return }
            self.show(discs: discs, results: results)
        }
    }

    /// Loads the current favourite state from the server.
    public func fetchFavorite() {
        api.isFavorite(itemId: album.id) { [weak self] result in
            if case .success(let fav) = result {
                DispatchQueue.main.async { self?.isFavorite = fav }
            }
        }
    }

    /// Optimistically toggles the favourite flag, reverting on failure.
    public func toggleFavorite() {
        let newValue = !isFavorite
        isFavorite = newValue
        api.setFavorite(itemId: album.id, isFavorite: newValue) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success: FavoritesStore.record(self.album.id, isFavorite: newValue)
                case .failure(let e):
                    print("[AlbumDetailView] setFavorite failed: \(e.errorDescription ?? "\(e)")")
                    self.isFavorite = !newValue
                }
            }
        }
    }

    /// True when every track belongs to this album, so repeating the album
    /// name under each track is redundant.
    public var allTracksFromThisAlbum: Bool {
        let ids = Set((discs.map(\.id) + [album.id]).map { $0.lowercased() })
        return !tracks.isEmpty && tracks.allSatisfy {
            if let id = $0.albumId { return ids.contains(id.lowercased()) }
            return $0.album == album.album
        }
    }

    /// Play the whole album — kicks off `PlayerManager.play(album:)`
    /// from track 0.
    public func play() {
        guard let player else {
            print("[AlbumDetailView] Player tap with no PlayerManager — skipping")
            return
        }
        Task { @MainActor in
            // "Now playing" (this album already playing) just opens the sheet.
            if !self.isThisAlbumPlaying {
                if self.isMultiDisc {
                    player.play(discs: self.discs, as: self.album)
                } else {
                    player.play(album: self.album, startIndex: 0)
                }
            }
            player.isNowPlayingPresented = true
        }
    }

    /// The server IDs of these tracks, for "Add to Playlist…". None when
    /// offline, and none for rows built from a download (their IDs
    /// aren't the server's).
    func playlistTrackIds(_ tracks: [JellyfinTrack]) -> [String] {
        guard !isOffline else { return [] }
        return tracks.filter { track in
            guard let disc = track.albumId else { return discsFromDownload.isEmpty }
            return !discsFromDownload.contains(disc)
        }.map(\.id)
    }

    /// Play a single track by its `JellyfinTrack`. Called from `TrackRow`
    /// when the user taps a specific row.
    public func play(track: JellyfinTrack) {
        guard canPlayIndividualTracks else { return }
        guard let player else {
            print("[AlbumDetailView] Play(track:) with no PlayerManager — skipping")
            return
        }
        Task { @MainActor in
            // Rows built from a download: play it from disk, from track 1,
            // as the Play button does.
            if let disc = track.albumId, self.discsFromDownload.contains(disc) {
                if self.isMultiDisc {
                    player.play(discs: self.discs, as: self.album)
                } else {
                    player.play(album: self.album, startIndex: 0)
                }
            } else if self.isMultiDisc {
                player.play(discs: self.discs, as: self.album, startingAt: track.id)
            } else {
                player.play(track, in: self.album)
            }
        }
    }

    /// Kicks off a download in the format chosen in Settings.
    /// Downloads the album — every disc not already downloaded or in progress.
    public func startDownload() {
        guard let mgr = downloadManager else { return }
        for disc in discs where !mgr.isDownloaded(disc.id) && !mgr.isDownloading(disc.id) {
            mgr.download(album: disc)
        }
    }

    /// Downloads one disc of a multi-disc album.
    public func downloadDisc(_ disc: JellyfinAlbum) {
        downloadManager?.download(album: disc)
    }

    /// Pauses the album's download, resumes it if paused, or retries it if failed.
    public func togglePauseDownload() {
        togglePauseDownload(albumID: album.id)
    }

    /// Pause / resume / retry for one disc (or a single-disc album).
    public func togglePauseDownload(albumID: String) {
        guard let mgr = downloadManager else { return }
        if mgr.isFailed(albumID) {
            mgr.retry(albumID: albumID)
        } else if mgr.isPaused(albumID) {
            mgr.resume(albumID: albumID)
        } else {
            mgr.pause(albumID: albumID)
        }
    }

    /// Whether every disc is downloaded or downloading (nothing left to start).
    public var isDownloading: Bool {
        guard let mgr = downloadManager else { return false }
        return discs.contains { mgr.isDownloading($0.id) }
            && discs.allSatisfy { mgr.isDownloading($0.id) || isDiscDownloaded($0) }
    }

    /// The active download state of a single-disc album. Multi-disc albums
    /// show progress per disc instead (`state(for:)`).
    public var downloadState: DownloadAlbumState? {
        isMultiDisc ? nil : downloadManager?.state(for: album.id)
    }

    /// The active download state of one disc.
    public func state(for disc: JellyfinAlbum) -> DownloadAlbumState? {
        downloadManager?.state(for: disc.id)
    }

    /// Whether every disc is on disk.
    public var isDownloaded: Bool {
        discs.allSatisfy(isDiscDownloaded)
    }

    public func isDiscDownloaded(_ disc: JellyfinAlbum) -> Bool {
        downloadManager?.isDownloaded(disc.id) ?? DownloadPaths.exists(albumID: disc.id)
    }
}
// MARK: - View

public struct AlbumDetailView: View {

    // MARK: Inputs

    @State public var viewModel: AlbumDetailViewModel
    @State private var showFullBio = false

    // MARK: Init

    public init(api: JellyfinAPI, album: JellyfinAlbum, player: PlayerManager? = nil, downloadManager: DownloadManager? = nil) {
        _viewModel = State(initialValue: AlbumDetailViewModel(
            api: api, album: album, player: player, downloadManager: downloadManager
        ))
    }

    // MARK: Body

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16.scaled) {
                headerSection
                actionsSection
                tracksSection
                moreByArtistSection
                aboutArtistSection
            }
            .padding()
            .padding(.bottom, 64.scaled)  // space for now-playing bar
        }
        .navigationTitle(viewModel.album.displayName)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    addToPlaylistButton(viewModel.tracks)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .disabled(viewModel.playlistTrackIds(viewModel.tracks).isEmpty)
                .accessibilityLabel("More")
                .accessibilityIdentifier("AlbumDetailView.moreMenu")
            }
        }
        .task {
            viewModel.load()
        }
    }

    // MARK: - Add to Playlist

    /// "Add to Playlist…" for these tracks: the whole album, a disc, a row.
    private func addToPlaylistButton(_ tracks: [JellyfinTrack]) -> some View {
        Button {
            PlaylistEvents.shared.add(trackIds: viewModel.playlistTrackIds(tracks))
        } label: {
            Label("Add to Playlist…", systemImage: "text.badge.plus")
        }
    }

    /// The small ⋯ on a disc heading.
    @ViewBuilder
    private func discMenu(_ tracks: [JellyfinTrack]) -> some View {
        if !viewModel.playlistTrackIds(tracks).isEmpty {
            Menu {
                addToPlaylistButton(tracks)
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(Color.appBlue)
                    .frame(width: 28.scaled, height: 24.scaled)
                    .contentShape(Rectangle())
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .accessibilityLabel("More for this disc")
        }
    }

    // MARK: - Sub-views

    private var headerSection: some View {
        HStack(alignment: .bottom, spacing: 16.scaled) {
            // Album art — fetched via AuthenticatedImage with the
            // Authorization header. Falls back to a music-note
            // placeholder while loading or on error.
            albumArtView

            VStack(alignment: .leading, spacing: 6.scaled) {
                Text(viewModel.album.displayName)
                    .font(.plex(.title2).bold())
                if !viewModel.album.artistName.isEmpty {
                    Text(viewModel.album.artistName)
                        .font(.plex(.headline))
                        .foregroundStyle(.secondary)
                }
                if let year = viewModel.album.productionYear {
                    Text(String(year))
                        .font(.plex(.caption))
                        .foregroundStyle(.tertiary)
                }
                Spacer()
            }
        }
    }

    private var albumArtView: some View {
        AlbumArtView(request: viewModel.albumArtRequest, size: 200.scaled, cornerRadius: 12.scaled)
    }

    private var actionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Button {
                    viewModel.play()
                } label: {
                    HStack {
                        Image(systemName: viewModel.isThisAlbumPlaying ? "waveform" : "play.fill")
                            .imageScale(.large)
                        Text(viewModel.isThisAlbumPlaying ? "Now playing" : "Play")
                    }
                    .font(.plex(.subheadline))
                }
                .buttonStyle(.borderedProminent)
                .tint(viewModel.isThisAlbumPlaying ? Color.appPink : nil)
                .accessibilityIdentifier("AlbumDetailView.playButton")
                .help("Play the whole album")

                Button {
                    viewModel.toggleFavorite()
                } label: {
                    Image(systemName: viewModel.isFavorite ? "heart.fill" : "heart")
                        .foregroundStyle(viewModel.isFavorite ? Color.appPink : .white)
                        .font(.plex(.subheadline))
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(viewModel.isFavorite ? "Remove from favourites" : "Add to favourites")

                Button {
                    viewModel.startDownload()
                } label: {
                    HStack {
                        Image(systemName: viewModel.isDownloaded ? "checkmark.circle.fill"
                              : viewModel.isDownloading ? "arrow.down.circle.dotted" : "arrow.down.circle")
                        Text(viewModel.isDownloaded ? "Downloaded"
                             : viewModel.isDownloading ? "Downloading"
                             : viewModel.isMultiDisc ? "Download all discs" : "Download")
                    }
                    .foregroundStyle(viewModel.isDownloaded ? .green : .primary)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isDownloading || viewModel.isDownloaded)
                .accessibilityIdentifier("AlbumDetailView.downloadButton")
                .help(viewModel.isDownloaded
                    ? "Already downloaded"
                    : viewModel.isDownloading ? "Downloading…"
                    : viewModel.isMultiDisc ? "Download every disc" : "Download this album")
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // Download progress indicator.
            if let state = viewModel.downloadState {
                let paused = state.status == .paused
                let failed = state.status == .failed
                PulsingProgressBar(progress: state.progress, isPaused: paused || failed)
                HStack {
                    Text(failed ? "Download failed" : paused ? "Paused" : "Downloading")
                        .font(.plex(.caption))
                    Text("\(state.completedTracks)/\(state.expectedTrackCount) tracks")
                        .font(.plex(.caption2))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(failed ? "Retry" : paused ? "Resume" : "Pause") {
                        viewModel.togglePauseDownload()
                    }
                    .font(.plex(.caption))
                    .buttonStyle(.bordered)
                    .disabled(state.status == .pending)
                    .accessibilityIdentifier("AlbumDetailView.pauseDownload")
                }
                if let err = state.errorMessage, !err.isEmpty {
                    Text(err)
                        .font(.plex(.caption2))
                        .foregroundStyle(.red)
                }
            }
        }
    }

    @ViewBuilder
    private var moreByArtistSection: some View {
        if !viewModel.isOffline, !viewModel.moreByArtist.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("More by \(viewModel.album.artistName)")
                    .font(.plex(.headline))
                // Bleed to the screen edges; the row applies its own padding.
                HomeSectionRow(
                    api: viewModel.api,
                    title: "More by \(viewModel.album.artistName)",
                    albums: viewModel.moreByArtist,
                    isLoading: false,
                    emptyMessage: "",
                    downloadManager: viewModel.downloadManager
                )
                .padding(.horizontal, -16)
            }
            .padding(.top, 16)
        }
    }

    /// "About <artist>": MusicBrainz facts and genres, then the Wikipedia
    /// introduction, with links to both. Hidden if nothing was found, and offline.
    @ViewBuilder
    private var aboutArtistSection: some View {
        if !viewModel.isOffline, let info = viewModel.artistInfo {
            VStack(alignment: .leading, spacing: 8) {
                Text("About \(viewModel.album.artistName)")
                    .font(.plex(.headline))
                if let facts = info.facts {
                    Text(facts)
                        .font(.plex(.subheadline))
                        .foregroundStyle(.secondary)
                }
                if !info.genres.isEmpty {
                    Text(info.genres.joined(separator: " · "))
                        .font(.plex(.caption))
                        .foregroundStyle(Color.appBlue)
                }
                if let summary = info.summary {
                    Text(summary)
                        .font(.plex(.callout))
                        .lineLimit(showFullBio ? nil : 6)
                    if summary.count > 300 {
                        Button(showFullBio ? "Less" : "More") { showFullBio.toggle() }
                            .font(.plex(.caption).bold())
                            .buttonStyle(.plain)
                            .foregroundStyle(Color.appBlue)
                    }
                }
                HStack(spacing: 4) {
                    Link("MusicBrainz", destination: info.musicBrainzURL)
                    if let wiki = info.wikipediaURL {
                        Text("·")
                        Link("Wikipedia", destination: wiki)
                        Text("(CC BY-SA)")
                    }
                }
                .font(.plex(.caption))
                .foregroundStyle(.secondary)
                .tint(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 16)
        }
    }

    /// Every track's time added up (all discs).
    private var albumDuration: TimeInterval {
        viewModel.tracks.reduce(0) { $0 + $1.duration }
    }

    @ViewBuilder
    private var tracksSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("Tracks")
                    .font(.plex(.headline))
                Spacer()
                // The album's playing time, above the tracks' own times.
                if !viewModel.isLoadingTracks, albumDuration > 0 {
                    Text(NowPlayingView.formatAlbumDuration(albumDuration))
                        .font(.plex(.caption).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .padding(.trailing, 8)
                        .accessibilityLabel("Playing time \(NowPlayingView.formatAlbumDuration(albumDuration))")
                        .accessibilityIdentifier("AlbumDetailView.duration")
                }
            }
            .padding(.bottom, 4)

            if viewModel.isLoadingTracks {
                HStack {
                    ProgressView()
                    Text("Loading tracks…")
                        .font(.plex(.caption))
                        .foregroundStyle(.secondary)
                }
            } else if let err = viewModel.errorMessage {
                Text(err)
                    .font(.plex(.footnote))
                    .foregroundStyle(.red)
            } else if viewModel.tracks.isEmpty {
                Text("No tracks found.")
                    .font(.plex(.footnote))
                    .foregroundStyle(.secondary)
            } else if viewModel.isMultiDisc {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(viewModel.tracksByDisc, id: \.disc.id) { group in
                        discHeader(group.disc, tracks: group.tracks)
                        ForEach(group.tracks) { track in
                            trackRow(track)
                        }
                    }
                }
            } else if let groups = viewModel.tracksByDiscNumber {
                // Discs inside one album: "Disc N" headings. The album
                // downloads as a whole, so no per-disc download button.
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(groups, id: \.number) { group in
                        HStack(alignment: .center, spacing: 8) {
                            Text("Disc \(group.number)")
                                .font(.plex(.subheadline).bold())
                                .accessibilityAddTraits(.isHeader)
                            Spacer(minLength: 8)
                            discMenu(group.tracks)
                        }
                        .padding(.top, 14)
                        .padding(.bottom, 6)
                        ForEach(group.tracks) { track in
                            trackRow(track)
                        }
                    }
                }
            } else {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(viewModel.tracks) { track in
                        trackRow(track)
                    }
                }
            }
        }
    }

    private func trackRow(_ track: JellyfinTrack) -> some View {
        TrackRow(track: track, showAlbum: !viewModel.allTracksFromThisAlbum,
                 isPlaying: viewModel.isCurrent(track)) {
            viewModel.play(track: track)
        }
        .allowsHitTesting(viewModel.canPlayIndividualTracks)
        // Long-press on iOS, right-click on the Mac.
        .contextMenu {
            if !viewModel.playlistTrackIds([track]).isEmpty {
                addToPlaylistButton([track])
            }
        }
    }

    /// "Disc 2" heading with its own download control on the right.
    private func discHeader(_ disc: JellyfinAlbum, tracks: [JellyfinTrack]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Disc \(viewModel.discNumber(of: disc))")
                        .font(.plex(.subheadline).bold())
                    if let subtitle = DiscGrouping.subtitle(of: disc) {
                        Text(subtitle)
                            .font(.plex(.caption))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                discDownloadControl(disc)
                discMenu(tracks)
            }
            if let state = viewModel.state(for: disc) {
                PulsingProgressBar(progress: state.progress,
                                   isPaused: state.status == .paused || state.status == .failed)
                if let err = state.errorMessage, !err.isEmpty {
                    Text(err).font(.plex(.caption2)).foregroundStyle(.red)
                }
            }
        }
        .padding(.top, 14)
        .padding(.bottom, 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("AlbumDetailView.disc.\(viewModel.discNumber(of: disc))")
    }

    @ViewBuilder
    private func discDownloadControl(_ disc: JellyfinAlbum) -> some View {
        if let state = viewModel.state(for: disc) {
            let paused = state.status == .paused
            let failed = state.status == .failed
            HStack(spacing: 8) {
                Text(failed ? "Failed" : paused ? "Paused"
                     : "\(state.completedTracks)/\(state.expectedTrackCount)")
                    .font(.plex(.caption).monospacedDigit())
                    .foregroundStyle(failed ? .red : .secondary)
                Button {
                    viewModel.togglePauseDownload(albumID: disc.id)
                } label: {
                    Image(systemName: failed ? "arrow.clockwise" : paused ? "play.fill" : "pause.fill")
                        .foregroundStyle(Color.appBlue)
                        .frame(width: 20.scaled)
                }
                .buttonStyle(.plain)
                .disabled(state.status == .pending)
                .accessibilityLabel(failed ? "Retry disc" : paused ? "Resume disc" : "Pause disc")
            }
        } else if viewModel.isDiscDownloaded(disc) {
            Label("Downloaded", systemImage: "checkmark.circle.fill")
                .font(.plex(.caption))
                .foregroundStyle(.green)
        } else {
            Button("Download disc") {
                viewModel.downloadDisc(disc)
            }
            .font(.plex(.caption))
            .buttonStyle(.bordered)
            .accessibilityIdentifier("AlbumDetailView.downloadDisc.\(viewModel.discNumber(of: disc))")
        }
    }
}

// MARK: - TrackRow

/// A single row in the track list. Tapping the row starts playback
/// from that track, via the parent `AlbumDetailViewModel.play(track:)`.
struct TrackRow: View {
    public let track: JellyfinTrack
    /// Whether to show the album name under the track title.
    public let showAlbum: Bool
    /// Called when the user taps the row. Defaults to no-op.
    public let onTap: () -> Void
    /// Highlights the row when it is the track currently playing.
    public let isPlaying: Bool

    public init(track: JellyfinTrack, showAlbum: Bool = true, isPlaying: Bool = false,
                onTap: @escaping () -> Void = { }) {
        self.track = track
        self.showAlbum = showAlbum
        self.isPlaying = isPlaying
        self.onTap = onTap
    }

    public var body: some View {
        Button {
            onTap()
        } label: {
            HStack {
                Text(String(format: "%02d", track.trackNumber))
                    .font(.plex(.caption))
                    .foregroundStyle(.secondary)
                    .frame(width: 28.scaled, alignment: .trailing)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.displayName)
                        .font(.plex(.body))
                        .fontWeight(isPlaying ? .semibold : .regular)
                        .foregroundStyle(isPlaying ? .white : .primary)
                        .lineLimit(1)
                    if showAlbum, let album = track.album, !album.isEmpty {
                        Text(album)
                            .font(.plex(.caption2))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer()

                Text(Self.formatDuration(track.duration))
                    .font(.plex(.caption))
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 6.scaled)
            .padding(.horizontal, 8.scaled)
            // Playing row: solid pink with white text, legible over the blur.
            .background(isPlaying ? Color.appPink : Color.gray.opacity(0.05))
            .cornerRadius(6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("TrackRow.\(track.id)")
    }

    static func formatDuration(_ duration: TimeInterval) -> String {
        let totalSeconds = Int(duration)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Helpers

private extension JellyfinAlbum {
    var releaseYear: Int? { productionYear }
}