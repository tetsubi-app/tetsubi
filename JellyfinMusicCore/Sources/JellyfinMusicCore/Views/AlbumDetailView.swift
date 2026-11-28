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
    /// The album artist the artist line opens; nil while looking a
    /// name-only artist up, and when the line isn't tappable (`ArtistLink`).
    public private(set) var artistLink: JellyfinArtist?
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
        self.albumArtRequest = api.imageURLRequest(for: album.id, size: 800)
        if case .artist(let artist) = ArtistLink.resolve(name: album.artistName, id: album.artistId) {
            self.artistLink = artist
        }
    }

    /// "1991 · 12 tracks · 48m 12s": whatever is known (the counts only once the tracks have loaded).
    var metaLine: String? {
        Self.metaLine(year: album.productionYear, trackCount: isLoadingTracks ? 0 : tracks.count,
                      duration: isLoadingTracks ? 0 : tracks.reduce(0) { $0 + $1.duration })
    }

    nonisolated static func metaLine(year: Int?, trackCount: Int, duration: TimeInterval) -> String? {
        let parts = [
            year.map(String.init),
            trackCount > 0 ? (trackCount == 1 ? "1 track" : "\(trackCount) tracks") : nil,
            duration > 0 ? NowPlayingView.formatAlbumDuration(duration) : nil,
        ].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
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

    /// Whether there's a player to queue into (Play Next, Add to Queue).
    var hasPlayer: Bool { player != nil }

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
        findArtistLink()
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

    /// An album that only knows its artist's name: look the artist up (online).
    private func findArtistLink() {
        guard artistLink == nil,
              case .lookup = ArtistLink.resolve(name: album.artistName, id: album.artistId) else { return }
        let name = album.artistName
        let online = !isOffline
        Task { @MainActor [weak self, api] in
            let found = await ArtistLink.find(name: name, id: nil, online: online) { await api.albumArtists(matching: $0) }
            self?.artistLink = found
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
        // "Now playing" (this album already playing) just opens the sheet.
        if isThisAlbumPlaying {
            player.isNowPlayingPresented = true
            return
        }
        // With more than the current album queued, asks Replace queue /
        // Play Next / Add to Queue first.
        QueuePrompt.shared.play(album.displayName, replace: { [self] in
            if isMultiDisc {
                player.play(discs: discs, as: album)
            } else {
                player.play(album: album, startIndex: 0)
            }
            player.isNowPlayingPresented = true
        }, place: { [self] how in
            queue(how)
        })
    }

    /// Play Next / Add to Queue for the album (every disc), with a short
    /// confirmation. Allowed in Maniac Mode.
    public func queue(_ how: QueueActions.Placement) {
        QueueActions.queue(album: album, how)
    }

    /// Play Next / Add to Queue for one song.
    public func queue(track: JellyfinTrack, _ how: QueueActions.Placement) {
        QueueActions.queue(tracks: [track], in: album, how)
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
        // With more than the current album queued, asks first; Play Next
        // and Add to Queue then queue just this song.
        QueuePrompt.shared.play(track.displayName, replace: { [self] in
            // Rows built from a download: play it from disk, from track 1,
            // as the Play button does.
            if let disc = track.albumId, discsFromDownload.contains(disc) {
                if isMultiDisc {
                    player.play(discs: discs, as: album)
                } else {
                    player.play(album: album, startIndex: 0)
                }
            } else if isMultiDisc {
                player.play(discs: discs, as: album, startingAt: track.id)
            } else {
                player.play(track, in: album)
            }
        }, place: { [self] how in
            queue(track: track, how)
        })
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
    @Environment(\.dismiss) private var dismiss

    // MARK: Init

    public init(api: JellyfinAPI, album: JellyfinAlbum, player: PlayerManager? = nil, downloadManager: DownloadManager? = nil) {
        _viewModel = State(initialValue: AlbumDetailViewModel(
            api: api, album: album, player: player, downloadManager: downloadManager
        ))
    }

    // MARK: Body

    public var body: some View {
        VStack(spacing: 0) {
            PageHeader(nil, onBack: { dismiss() }) {
                HeaderMenuButton("More", enabled: viewModel.hasPlayer) {
                    QueueMenuItems { viewModel.queue($0) }
                    addToPlaylistButton(viewModel.tracks)
                        .disabled(viewModel.playlistTrackIds(viewModel.tracks).isEmpty)
                }
                .accessibilityIdentifier("AlbumDetailView.moreMenu")
            }
            DetailPage { metrics in
                infoSection(metrics)
            } list: { _ in
                tracksSection
                moreByArtistSection
            } after: {
                aboutArtistSection
            }
        }
        .ownsItsHeader(isDetail: true)
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
            RowMenuButton("More for this disc") {
                addToPlaylistButton(tracks)
            }
        }
    }

    // MARK: - Info (art, title, actions)

    @ViewBuilder
    private func infoSection(_ metrics: DetailMetrics) -> some View {
        // Fetched with the Authorization header; a music-note placeholder while loading or on error.
        AlbumArtView(request: viewModel.albumArtRequest, size: metrics.artSize, cornerRadius: Radii.card,
                     placeholderColor: TetsubiColors.art)
        DetailTitleBlock(metrics: metrics, title: viewModel.album.displayName, meta: viewModel.metaLine) {
            // Opens the artist's albums when it can (`ArtistLink`), looking much the same either way.
            if !viewModel.album.artistName.isEmpty {
                DetailArtistLine(name: viewModel.album.artistName, metrics: metrics, artist: viewModel.artistLink)
            }
        }
        actionsSection(metrics)
    }

    private func actionsSection(_ metrics: DetailMetrics) -> some View {
        VStack(alignment: metrics.horizontal, spacing: Spacing.m) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Spacing.s) { playButton; favouriteButton; downloadButton }
                VStack(spacing: Spacing.s) {
                    HStack(spacing: Spacing.s) { playButton; favouriteButton }
                    downloadButton
                }
            }
            .frame(maxWidth: .infinity, alignment: metrics.frameAlignment)

            // A single-disc album's download under way (a set shows it per disc).
            if let state = viewModel.downloadState {
                DownloadProgressBlock(progress: state.progress, status: state.status,
                                      completed: state.completedTracks, expected: state.expectedTrackCount,
                                      unit: "tracks", error: state.errorMessage,
                                      identifier: "AlbumDetailView.pauseDownload") {
                    viewModel.togglePauseDownload()
                }
            }
        }
    }

    private var playButton: some View {
        PrimaryButton(viewModel.isThisAlbumPlaying ? "Now playing" : "Play",
                      enabled: viewModel.hasPlayer,
                      color: viewModel.isThisAlbumPlaying ? TetsubiColors.pink : TetsubiColors.blue) {
            viewModel.play()
        }
        .accessibilityIdentifier("AlbumDetailView.playButton")
        .help("Play the whole album")
    }

    private var favouriteButton: some View {
        FavouriteStar(favourite: viewModel.isFavorite) { viewModel.toggleFavorite() }
            .background(TetsubiColors.surface, in: Circle())
    }

    private var downloadButton: some View {
        let downloaded = viewModel.isDownloaded
        let downloading = viewModel.isDownloading
        return PillButton(
            title: downloaded ? "Downloaded" : downloading ? "Downloading"
                : viewModel.isMultiDisc ? "Download all discs" : "Download",
            systemImage: downloaded ? "checkmark.circle.fill"
                : downloading ? "arrow.down.circle.dotted" : "arrow.down.circle",
            // Downloaded stays green; a tap does nothing then (remove it in the Downloads tab).
            color: downloaded ? TetsubiColors.green : TetsubiColors.textPrimary) {
                if !downloaded, !downloading { viewModel.startDownload() }
            }
            .accessibilityIdentifier("AlbumDetailView.downloadButton")
            .help(downloaded ? "Already downloaded"
                  : downloading ? "Downloading…"
                  : viewModel.isMultiDisc ? "Download every disc" : "Download this album")
    }

    // MARK: - More by, About

    @ViewBuilder
    private var moreByArtistSection: some View {
        if !viewModel.isOffline, !viewModel.moreByArtist.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader("More by \(viewModel.album.artistName)")
                CardShelf(items: viewModel.moreByArtist) { album, width in
                    AlbumCardLink(api: viewModel.api, album: album, width: width,
                                  isDownloaded: viewModel.downloadManager?.isDownloaded(album.id)
                                      ?? LibraryOffline.isDownloaded(album))
                }
            }
            .padding(.top, Spacing.l)
        }
    }

    /// "About <artist>": hidden if nothing was found, and offline.
    @ViewBuilder
    private var aboutArtistSection: some View {
        if !viewModel.isOffline, let info = viewModel.artistInfo {
            ArtistAboutView(artistName: viewModel.album.artistName, info: info)
        }
    }

    // MARK: - Tracks

    @ViewBuilder
    private var tracksSection: some View {
        SectionHeader("Tracks")
        if viewModel.isLoadingTracks {
            LoadingSpinner()
        } else if let err = viewModel.errorMessage {
            DetailMessage(text: err, color: TetsubiColors.red)
        } else if viewModel.tracks.isEmpty {
            DetailMessage(text: "No tracks found.")
        } else if viewModel.isMultiDisc {
            ForEach(viewModel.tracksByDisc, id: \.disc.id) { group in
                discHeader(group.disc, tracks: group.tracks)
                ForEach(group.tracks) { track in trackRow(track) }
            }
        } else if let groups = viewModel.tracksByDiscNumber {
            // Discs inside one album: "Disc N" headings. The album downloads as a
            // whole, so no per-disc download button.
            ForEach(groups, id: \.number) { group in
                discHeading(number: group.number, subtitle: nil, tracks: group.tracks) { EmptyView() }
                ForEach(group.tracks) { track in trackRow(track) }
            }
        } else {
            ForEach(viewModel.tracks) { track in trackRow(track) }
        }
    }

    private func trackRow(_ track: JellyfinTrack) -> some View {
        // In Maniac Mode a tap does nothing (no onTap), but the menu still queues.
        ListRow(title: track.displayName,
                subtitle: !viewModel.allTracksFromThisAlbum ? track.album : nil,
                leadingNumber: String(format: "%02d", track.trackNumber > 0 ? track.trackNumber : (viewModel.tracks.firstIndex { $0.id == track.id } ?? 0) + 1),
                trailingMeta: TrackTime.format(track.duration),
                current: viewModel.isCurrent(track),
                onTap: viewModel.canPlayIndividualTracks ? { viewModel.play(track: track) } : nil) {
            if hasTrackMenu(track) {
                RowMenuButton("More for \(track.displayName)") { trackMenu(track) }
            }
        }
        .accessibilityIdentifier("TrackRow.\(track.id)")
        // Long-press on iOS, right-click on the Mac.
        .contextMenu { trackMenu(track) }
    }

    private func hasTrackMenu(_ track: JellyfinTrack) -> Bool {
        viewModel.hasPlayer || !viewModel.playlistTrackIds([track]).isEmpty
    }

    @ViewBuilder
    private func trackMenu(_ track: JellyfinTrack) -> some View {
        if viewModel.hasPlayer {
            QueueMenuItems { viewModel.queue(track: track, $0) }
        }
        if !viewModel.playlistTrackIds([track]).isEmpty {
            addToPlaylistButton([track])
        }
    }

    /// "Disc 2" heading with its subtitle, its own download control and its ⋯.
    private func discHeader(_ disc: JellyfinAlbum, tracks: [JellyfinTrack]) -> some View {
        discHeading(number: viewModel.discNumber(of: disc), subtitle: DiscGrouping.subtitle(of: disc), tracks: tracks) {
            discDownloadControl(disc)
        }
        .accessibilityIdentifier("AlbumDetailView.disc.\(viewModel.discNumber(of: disc))")
    }

    private func discHeading<Download: View>(number: Int, subtitle: String?, tracks: [JellyfinTrack],
                                             @ViewBuilder download: () -> Download) -> some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(spacing: Spacing.s) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Disc \(number)").font(TetsubiType.rowTitle.weight(.bold)).foregroundStyle(TetsubiColors.textPrimary)
                    if let subtitle {
                        Text(subtitle).font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
                download()
                discMenu(tracks)
            }
        }
        .pageMargins()
        .padding(.top, Spacing.l)
        .padding(.bottom, Spacing.xs)
    }

    @ViewBuilder
    private func discDownloadControl(_ disc: JellyfinAlbum) -> some View {
        if let state = viewModel.state(for: disc) {
            let paused = state.status == .paused
            let failed = state.status == .failed
            VStack(alignment: .trailing, spacing: Spacing.xs) {
                HStack(spacing: Spacing.s) {
                    Text(failed ? "Failed" : paused ? "Paused" : "\(state.completedTracks)/\(state.expectedTrackCount)")
                        .font(TetsubiType.caption.monospacedDigit())
                        .foregroundStyle(failed ? TetsubiColors.red : TetsubiColors.textSecondary)
                    Button {
                        viewModel.togglePauseDownload(albumID: disc.id)
                    } label: {
                        Image(systemName: failed ? "arrow.clockwise" : paused ? "play.fill" : "pause.fill")
                            .font(.system(size: 14.scaled))
                            .foregroundStyle(TetsubiColors.blue)
                            .frame(width: 32.scaled, height: 32.scaled)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(state.status == .pending)
                    .accessibilityLabel(failed ? "Retry disc" : paused ? "Resume disc" : "Pause disc")
                }
                PulsingProgressBar(progress: state.progress, isPaused: paused || failed)
                    .frame(width: 140)
                if let err = state.errorMessage, !err.isEmpty {
                    Text(err).font(TetsubiType.caption).foregroundStyle(TetsubiColors.red).lineLimit(2)
                }
            }
        } else if viewModel.isDiscDownloaded(disc) {
            Label("Downloaded", systemImage: "checkmark.circle.fill")
                .font(TetsubiType.caption)
                .foregroundStyle(TetsubiColors.green)
        } else {
            ActionChip("Download disc") { viewModel.downloadDisc(disc) }
                .accessibilityIdentifier("AlbumDetailView.downloadDisc.\(viewModel.discNumber(of: disc))")
        }
    }
}

// MARK: - Track times

/// Track lengths as `m:ss`.
enum TrackTime {
    static func format(_ duration: TimeInterval) -> String {
        let totalSeconds = Int(duration)
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
