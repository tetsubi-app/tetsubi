// MARK: - PlayerManager — AVQueuePlayer wrapper with now-playing integration

import Foundation
import Observation
import AVFoundation
import MediaPlayer

/// Drives audio playback via `AVQueuePlayer` and integrates with
/// `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter`. The manager is
/// `@Observable` so SwiftUI views can read / update its state.
///
/// The queue is built from the track list of one album, or from a
/// playlist's tracks (each keeping its own album); `queueSourceId` says
/// which.
///
/// Gapless playback: `AVQueuePlayer` natively pre-queues items and
/// auto-advances; on `didPlayToEndTime` we advance state. As a v1.1
/// optimisation, a 1s-before-end observer (`preWarmNextItem`) starts
/// loading the next item's asset early so an on-demand transcode has
/// time to begin.
@MainActor
@Observable
public final class PlayerManager {

    // MARK: Public state

    /// The widget shows the queue's length and finish time, so edits
    /// (Up Next) are passed on too.
    public private(set) var queue: [PlaybackTrack] = [] {
        didSet { widgetChanged() }
    }
    public private(set) var currentIndex: Int = 0
    public private(set) var currentTrack: PlaybackTrack? {
        didSet {
            applyNormalization()
            widgetChanged()
        }
    }
    /// Every change is passed on to the system's now-playing info, so the
    /// Mac's media keys, the lock screen and Control Centre always know
    /// whether Tetsubi is playing (macOS only sends media keys to an app that
    /// says it's playing).
    public private(set) var isPlaying: Bool = false {
        didSet {
            nowPlaying.updateState(isPlaying: isPlaying, elapsedTime: position)
            widgetChanged()
        }
    }
    public private(set) var position: TimeInterval = 0
    public private(set) var duration: TimeInterval = 0
    public private(set) var repeatMode: RepeatMode = .off
    public private(set) var shuffle: Bool = false
    /// Maniac Mode (Settings): no skip, shuffle, repeat or scrubbing.
    /// Change with `setManiacMode(_:)`.
    public private(set) var maniacMode: Bool = PlaybackSettings.maniacMode {
        didSet { widgetChanged() }
    }
    /// Posted when Maniac Mode changes (CarPlay rebuilds its buttons).
    public static let maniacModeDidChange = Notification.Name("PlayerManager.maniacModeDidChange")
    public private(set) var lastError: PlaybackError?
    /// Title of the track that failed (for `errorMessage`).
    private var failedTitle = ""
    /// The player's own reason for the failure (error codes, network kind),
    /// shown after the message so a report says what actually went wrong.
    private var failedDetail = ""
    /// Whether playback was running when an interruption began.
    private var resumeAfterInterruption = false

    /// What went wrong, for Now Playing and the bar; nil when all's well.
    public var errorMessage: String? {
        switch lastError {
        case nil: nil
        case .trackFailed:
            "Couldn't play \u{201C}\(failedTitle)\u{201D}. Check the connection to your server, then press Play to try again."
                + (failedDetail.isEmpty ? "" : " (\(failedDetail))")
        default:
            "Couldn't load this album from your server. Check the connection, then try again."
        }
    }
    public private(set) var isLoading: Bool = false
    /// Whether the full-screen `NowPlayingView` sheet is open. One sheet at
    /// the app root reads this, so any screen can open it (e.g. album Play).
    public var isNowPlayingPresented: Bool = false

    // MARK: Dependencies

    private let api: JellyfinAPI
    private let nowPlaying: NowPlayingController
    private let audioSession: AudioSession
    private let stream: StreamURLBuilder

    private var player: AVQueuePlayer = AVQueuePlayer()
    /// The underlying player, for the Mac's AirPlay picker.
    var outputPlayer: AVPlayer { player }
    private var enqueuedItems: [AVPlayerItem] = []
    /// End-of-track observer per queued item, removed when the item leaves
    /// the queue (otherwise NotificationCenter keeps one per track ever queued).
    private var itemEndObservers: [ObjectIdentifier: NSObjectProtocol] = [:]
    /// Per queued item: its failed-to-play notification and `status` observation.
    private var itemFailureObservers: [ObjectIdentifier: (NSObjectProtocol, NSKeyValueObservation)] = [:]
    /// Position in `enqueuedItems` of the first item we enqueued, so we
    /// can map `currentIndex` (into the full `queue`) to the next item to
    /// pre-load. Reset on each `loadQueue`.
    private var enqueuedStartIndex = 0
    /// Track with an open server playback report (streamed tracks only).
    private var reportedTrack: PlaybackTrack?
    /// Builds a player item for a queue index; used to rebuild the AVQueuePlayer queue on skip/repeat.
    private var itemFactory: ((Int) -> AVPlayerItem?)?
    private var timeObserverToken: Any?
    private var isConfigured = false
    private var pendingFormat: AudioFormat = .alac
    private var pendingAlbum: JellyfinAlbum?
    /// For each entry of a streamed queue, its place in the album or
    /// playlist it was started from (before shuffling), so Maniac Mode can
    /// put a shuffled queue back in order. By place, not track ID: a
    /// playlist can hold the same song twice.
    private(set) var queuePositions: [Int] = []

    /// What the queue was started from: the album's ID (the merged entry's
    /// for a multi-disc album) or the playlist's. Nil when nothing is queued.
    public private(set) var queueSourceId: String?

    /// The playing track's place in the album or playlist the queue was
    /// started from, whatever order the queue plays in.
    public var currentSourcePosition: Int? {
        guard queue.indices.contains(currentIndex) else { return nil }
        return queuePositions.indices.contains(currentIndex) ? queuePositions[currentIndex] : currentIndex
    }

    public init(api: JellyfinAPI) {
        self.api = api
        self.audioSession = AudioSession()
        self.nowPlaying = NowPlayingController()
        self.stream = StreamURLBuilder(api: api, userId: api.userId ?? "")
    }

    // MARK: - Lifecycle

    /// Configure audio session + register remote commands.
    /// Call once when the player is created.
    public func start() {
        if isConfigured { return }
        audioSession.onInterruptionBegan = { [weak self] in self?.interruptionBegan() }
        audioSession.onInterruptionEnded = { [weak self] resume in self?.interruptionEnded(shouldResume: resume) }
        audioSession.onOutputDeviceRemoved = { [weak self] in self?.pause() }
        audioSession.configureForPlayback()
        nowPlaying.registerRemoteCommands()
        nowPlaying.setSkipControlsEnabled(!maniacMode)
        nowPlaying.onPlay     = { [weak self] in self?.resume() }
        nowPlaying.onPause    = { [weak self] in self?.pause() }
        nowPlaying.onTogglePlayPause = { [weak self] in self?.togglePlayPause() }
        nowPlaying.onNext     = { [weak self] in self?.skipToNext() }
        nowPlaying.onPrevious = { [weak self] in self?.skipToPrevious() }
        nowPlaying.onSeekTo   = { [weak self] t in self?.seek(toTime: t) }
        nowPlaying.onStart    = { [weak self] _ in self?.restartCurrentTrack() }
        installTimeObserver()
        // `installItemEndObserver` is called per item in `loadQueue` — no
        // global observer needed here.
        isConfigured = true
    }

    public func stop() {
        reportStopped(atEnd: false)
        player.pause()
        queue = []
        queuePositions = []
        queueSourceId = nil
        currentIndex = 0
        currentTrack = nil
        isPlaying = false
        position = 0
        duration = 0
        nowPlaying.clear()
    }

    // MARK: - Offline playback

    /// Play a fully-downloaded album from local files. The track list
    /// is read from disk (sorted by track number), so no network
    /// call is needed. `albumArtURL` is `nil` when offline.
    public func playLocalDownloadedAlbum(
        albumID: String,
        format: AudioFormat = .flac,
        albumTitle: String = "",
        artistName: String = ""
    ) {
        playLocal(albumIDs: [albumID], sourceId: albumID, format: format,
                  albumTitle: albumTitle, artistName: artistName)
    }

    /// Plays downloaded albums from disk, one after another — every disc of
    /// a multi-disc album, in order (`sourceId` is the merged entry's ID).
    /// The widget's Play uses it for a downloaded album (works offline).
    public func playDownloaded(
        albumIDs: [String],
        sourceId: String,
        format: AudioFormat = .flac,
        albumTitle: String = "",
        artistName: String = ""
    ) {
        playLocal(albumIDs: albumIDs, sourceId: sourceId, format: format,
                  albumTitle: albumTitle, artistName: artistName)
    }

    /// Plays downloaded albums back to back from disk — e.g. every disc of a
    /// multi-disc album, in order. Each track keeps its own disc's id and art.
    private func playLocal(
        albumIDs: [String],
        sourceId: String,
        format: AudioFormat,
        albumTitle: String,
        artistName: String
    ) {
        start()
        let playbackTracks = albumIDs.flatMap { albumID -> [PlaybackTrack] in
            let artURL = api.albumArtURL(for: albumID, size: 600)
            let local = DownloadPaths.localTracks(albumID: albumID, format: format)
            // Saved titles are by number; downloads of untagged tracks made
            // before numbering by position share one number, so theirs are
            // matched by file name instead.
            let clashing = Self.numbersClash(local.map(\.trackNumber))
            let titles = clashing ? [:] : DownloadedTitles.titles(for: albumID)
            let byFile = clashing ? DownloadedTitles.titlesByFile(for: albumID) : [:]
            return local.map { entry in
                let fileTitle = DownloadedTitles.fileTitle(entry.name)
                var track = PlaybackTrack(
                    id: albumID + "/" + entry.url.lastPathComponent,
                    trackNumber: entry.trackNumber,
                    // The file name has lost brackets and punctuation; it's
                    // only a fallback until the real title is known.
                    title: titles[entry.trackNumber] ?? byFile[fileTitle] ?? fileTitle,
                    artist: artistName,
                    album: albumTitle,
                    albumId: albumID,
                    duration: 0,
                    albumArtURL: artURL,
                    localURL: entry.url
                )
                track.localArtURL = TrackCovers.existing(albumID: albumID, fileName: entry.name)
                // Format and bitrate come from the file itself (loadLocalDurations).
                return track
            }
        }
        guard !playbackTracks.isEmpty else {
            print("[PlayerManager] No local tracks for \(albumIDs)")
            return
        }

        queuePositions = []   // downloads always play in order
        queueSourceId = sourceId
        itemFactory = { i in
            playbackTracks.indices.contains(i)
                ? AVPlayerItem(url: playbackTracks[i].localURL ?? URL(fileURLWithPath: "")) : nil
        }
        enqueue(from: 0, count: playbackTracks.count)
        self.queue = playbackTracks
        self.currentIndex = 0
        self.currentTrack = playbackTracks.first
        self.isPlaying = true
        self.isLoading = false
        self.lastError = nil
        updateNowPlayingForCurrent()
        player.play()
        loadLocalDurations(of: playbackTracks)
        loadMissingLocalTitles(albumIDs: albumIDs)
        loadMissingTrackCovers(albumIDs: albumIDs)
    }

    /// Downloads made before per-track pictures were saved: once per album,
    /// when online, save the pictures of tracks that have their own and
    /// patch the queue.
    private func loadMissingTrackCovers(albumIDs: [String]) {
        for albumID in albumIDs where !TrackCovers.checked(albumID: albumID) {
            api.tracksForAlbum(albumID) { [weak self] result in
                guard case .success(let tracks) = result else { return }
                let files = DownloadPaths.localTracks(albumID: albumID).map(\.name)
                let pairs = TrackCovers.match(tracks: tracks, files: files)
                Task { @MainActor in
                    guard let self else { return }
                    await TrackCovers.save(pairs, albumID: albumID, api: self.api)
                    TrackCovers.markChecked(albumID: albumID)
                    for i in self.queue.indices where self.queue[i].albumId == albumID {
                        guard let name = self.queue[i].localURL?.lastPathComponent,
                              let art = TrackCovers.existing(albumID: albumID, fileName: name) else { continue }
                        self.queue[i].localArtURL = art
                    }
                    if self.queue.indices.contains(self.currentIndex) {
                        self.currentTrack = self.queue[self.currentIndex]
                        self.updateNowPlayingForCurrent()
                    }
                }
            }
        }
    }

    static func numbersClash(_ numbers: [Int]) -> Bool { Set(numbers).count != numbers.count }

    /// Albums downloaded before titles were saved: fetch the real titles
    /// from the server once (when online), save them, and patch the queue.
    private func loadMissingLocalTitles(albumIDs: [String]) {
        for albumID in albumIDs {
            let numbers = DownloadPaths.localTracks(albumID: albumID).map(\.trackNumber)
            if Self.numbersClash(numbers) {
                if DownloadedTitles.titlesByFile(for: albumID).isEmpty { loadTitlesByFile(albumID: albumID) }
                continue
            }
            guard DownloadedTitles.titles(for: albumID).isEmpty else { continue }
            api.tracksForAlbum(albumID) { [weak self] result in
                guard case .success(let tracks) = result else { return }
                let titles = Dictionary(zip(JellyfinTrack.fileNumbers(for: tracks), tracks.map(\.displayName)),
                                        uniquingKeysWith: { a, _ in a })
                Task { @MainActor in
                    DownloadedTitles.save(titles, for: albumID)
                    guard let self else { return }
                    for i in self.queue.indices where self.queue[i].albumId == albumID
                        && self.queue[i].localURL != nil {
                        guard let title = titles[self.queue[i].trackNumber] else { continue }
                        self.queue[i] = self.queue[i].withTitle(title)
                    }
                    if self.queue.indices.contains(self.currentIndex) {
                        self.currentTrack = self.queue[self.currentIndex]
                        self.updateNowPlayingForCurrent()
                    }
                }
            }
        }
    }

    /// Downloads whose files share a number ("00 - Im In Love.flac"): fetch
    /// the server's titles once, match them to files by name, save them
    /// (so they're right offline too) and patch the queue.
    private func loadTitlesByFile(albumID: String) {
        api.tracksForAlbum(albumID) { [weak self] result in
            guard case .success(let tracks) = result else { return }
            let byFile = DownloadedTitles.byFileName(tracks.map(\.displayName))
            Task { @MainActor in
                DownloadedTitles.saveByFile(byFile, for: albumID)
                guard let self else { return }
                for i in self.queue.indices where self.queue[i].albumId == albumID {
                    guard let url = self.queue[i].localURL,
                          let title = byFile[DownloadedTitles.fileTitle(url.lastPathComponent)] else { continue }
                    self.queue[i] = self.queue[i].withTitle(title)
                }
                if self.queue.indices.contains(self.currentIndex) {
                    self.currentTrack = self.queue[self.currentIndex]
                    self.updateNowPlayingForCurrent()
                }
            }
        }
    }

    /// Local files carry no server runtime, so read each file's duration from
    /// disk and patch the queue (drives the seek bar and album length).
    private func loadLocalDurations(of tracks: [PlaybackTrack]) {
        Task { @MainActor [weak self] in
            for (i, track) in tracks.enumerated() {
                guard let url = track.localURL else { continue }
                let asset = AVURLAsset(url: url)
                guard let time = try? await asset.load(.duration) else { continue }
                let secs = time.seconds
                let quality = await Self.localQuality(of: asset, url: url, seconds: secs)
                guard let self, secs.isFinite, secs > 0,
                      self.queue.indices.contains(i), self.queue[i].id == track.id else { continue }
                self.queue[i] = self.queue[i].withDuration(secs).withQuality(quality)
                if i == self.currentIndex {
                    self.currentTrack = self.queue[i]
                    self.duration = secs
                }
            }
        }
    }

    /// What a downloaded file is, read from the file itself.
    struct LocalQuality {
        var label: String?
        var sampleRate: Int?
        var bitDepth: Int?
    }

    /// "ALAC 1057 kbps" for a downloaded file, from the file itself: the
    /// codec from its audio format, the bitrate from the track's data rate
    /// (else file size ÷ length); plus its sample rate and bit depth (the
    /// download saves neither, so the file is the only place they're kept).
    private static func localQuality(of asset: AVURLAsset, url: URL, seconds: Double) async -> LocalQuality? {
        guard let audio = try? await asset.loadTracks(withMediaType: .audio).first else { return nil }
        let descriptions = (try? await audio.load(.formatDescriptions)) ?? []
        let codecID = descriptions.first.map { CMFormatDescriptionGetMediaSubType($0) }
        let format = codecID.flatMap(AudioFormat.fromCodecID)
        var bitrate = Int((try? await audio.load(.estimatedDataRate)) ?? 0)
        if bitrate <= 0, seconds > 0,
           let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize {
            bitrate = Int(Double(bytes) * 8 / seconds)
        }
        var quality = LocalQuality(label: PlaybackTrack.qualityLabel(format: format, bitrate: bitrate))
        if let desc = descriptions.first,
           let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc)?.pointee {
            quality.sampleRate = asbd.mSampleRate > 0 ? Int(asbd.mSampleRate.rounded()) : nil
            quality.bitDepth = AudioFormat.bitDepth(codecID: asbd.mFormatID, formatFlags: asbd.mFormatFlags,
                                                    bitsPerChannel: asbd.mBitsPerChannel)
        }
        return quality
    }

    /// Total length of the queued album, in seconds.
    public var albumLength: TimeInterval {
        queue.reduce(0) { $0 + $1.duration }
    }

    /// Time left in the queue: the rest of this track plus every track after it.
    public var albumRemaining: TimeInterval {
        let rest = queue.indices.contains(currentIndex)
            ? queue[(currentIndex + 1)...].reduce(0) { $0 + $1.duration } : 0
        return max(0, duration - position) + rest
    }

    // MARK: - Playback control

    /// Play an album from the given index. Default `.alac` because it
    /// is universally decodable by AVURLAsset on Apple platforms.
    ///
    /// If the album is fully downloaded locally, it plays from disk
    /// automatically. Otherwise it fetches the track list and streams
    /// from Jellyfin.
    ///
    /// If `self.shuffle` is `.true`, the fetched track list is
    /// randomized before queuing. When a `startIndex` is given (e.g.
    /// the user tapped a specific track), that track is moved to the
    /// head of the randomized queue so it plays first; the rest is
    /// shuffled.
    public func play(
        album: JellyfinAlbum,
        startIndex: Int = 0,
        format: AudioFormat = .alac
    ) {
        // Local-first: if the album is on disk, play from there.
        if DownloadPaths.exists(albumID: album.id), DownloadPaths.fileCount(albumID: album.id) > 0 {
            playLocalDownloadedAlbum(albumID: album.id, format: format,
                                     albumTitle: album.displayName, artistName: album.artistName)
            return
        }
        isLoading = true
        lastError = nil
        pendingFormat = format
        pendingAlbum = album
        // Maniac Mode always starts at track 1.
        let startIndex = maniacMode ? 0 : startIndex
        api.tracksForAlbum(album.id) { [weak self] result in
            DispatchQueue.main.async {
                switch result {
                case .success(let tracks):
                    self?.startQueue(tracks: tracks, album: album,
                                     forcedStartIndex: startIndex > 0 ? startIndex : nil, format: format)
                case .failure(let err):
                    print("[PlayerManager] tracksForAlbum failed: \(err.errorDescription ?? "\(err)")")
                    self?.onFail(.unknown)
                }
            }
        }
    }

    /// Play every disc of a multi-disc album as one queue, in disc order,
    /// optionally starting at `trackID`. `album` is the merged entry (its
    /// name and art are shown). All discs downloaded → plays from disk.
    public func play(
        discs: [JellyfinAlbum],
        as album: JellyfinAlbum,
        startingAt trackID: String? = nil,
        format: AudioFormat = .alac
    ) {
        // Maniac Mode always starts at track 1.
        let trackID = maniacMode ? nil : trackID
        if discs.count <= 1 {
            let only = discs.first ?? album
            if let trackID {
                api.tracksForAlbum(only.id) { [weak self] result in
                    DispatchQueue.main.async {
                        guard case .success(let tracks) = result,
                              let track = tracks.first(where: { $0.id == trackID }) else { return }
                        self?.play(track, in: only, format: format)
                    }
                }
            } else {
                play(album: only, format: format)
            }
            return
        }

        let allLocal = discs.allSatisfy {
            DownloadPaths.exists(albumID: $0.id) && DownloadPaths.fileCount(albumID: $0.id) > 0
        }
        if allLocal && trackID == nil {
            playLocal(albumIDs: discs.map(\.id), sourceId: album.id, format: format,
                      albumTitle: album.displayName, artistName: album.artistName)
            return
        }

        isLoading = true
        lastError = nil
        pendingFormat = format
        pendingAlbum = album
        var perDisc = [[JellyfinTrack]?](repeating: nil, count: discs.count)
        let group = DispatchGroup()
        for (i, disc) in discs.enumerated() {
            group.enter()
            api.tracksForAlbum(disc.id) { result in
                DispatchQueue.main.async {
                    if case .success(let tracks) = result { perDisc[i] = tracks }
                    group.leave()
                }
            }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            let tracks = perDisc.compactMap { $0 }.flatMap { $0 }
            guard !tracks.isEmpty else {
                print("[PlayerManager] No tracks for discs \(discs.map(\.id))")
                self.onFail(.unknown)
                return
            }
            let start = trackID.flatMap { id in tracks.firstIndex { $0.id == id } }
            self.startQueue(tracks: tracks, album: album, forcedStartIndex: start, format: format)
        }
    }

    /// Play a single track by its `JellyfinTrack` — fetches the rest
    /// of the album's tracks and starts with the tapped track first.
    ///
    /// If `self.shuffle` is on, the tapped track plays first, then
    /// the rest of the album is randomized after it.
    public func play(
        _ track: JellyfinTrack,
        in album: JellyfinAlbum,
        format: AudioFormat = .alac
    ) {
        isLoading = true
        lastError = nil
        pendingFormat = format
        pendingAlbum = album
        api.tracksForAlbum(album.id) { [weak self] result in
            DispatchQueue.main.async {
                switch result {
                case .success(let tracks):
                    // Maniac Mode always starts at track 1.
                    let start = (self?.maniacMode ?? false) ? nil : tracks.firstIndex { $0.id == track.id }
                    self?.startQueue(tracks: tracks, album: album, forcedStartIndex: start, format: format)
                case .failure(let err):
                    print("[PlayerManager] tracksForAlbum(\(track.id)) failed: \(err.errorDescription ?? "\(err)")")
                    self?.onFail(.unknown)
                }
            }
        }
    }

    /// Play a playlist's loaded tracks, optionally starting at a
    /// `position` in it (a place, not a track ID: a song may be there
    /// twice). Each track keeps its own album name, ID and art. Shuffle,
    /// repeat and Maniac Mode work as they do for albums: with shuffle on,
    /// the track at `position` plays first and the rest are shuffled.
    ///
    /// Each track plays from the playlist's own download when it's there,
    /// else from a downloaded album that has it, else it's streamed.
    /// With no connection the songs that aren't on this device are left
    /// out of the queue; when that leaves nothing to play (or the tapped
    /// song is one of them) nothing changes and the reason is returned.
    @discardableResult
    public func play(
        playlist: JellyfinPlaylist,
        tracks: [PlaylistEntry],
        startingAt position: Int? = nil,
        format: AudioFormat = .alac,
        online: Bool? = nil
    ) -> PlaylistPlayRefusal? {
        guard !tracks.isEmpty else { return nil }
        // Maniac Mode always starts at the first song.
        let start = maniacMode ? nil : position.flatMap { tracks.indices.contains($0) ? $0 : nil }
        let files = PlaylistFiles(playlistID: playlist.id)
        let sources = tracks.map { entry in
            QueueSource(track: entry.track, album: entry.album,
                        localURL: files.file(for: entry),
                        localArtURL: PlaylistDownloadPaths.savedArt(playlistID: playlist.id,
                                                                    itemID: entry.artItemId))
        }
        let places: [Int]
        let forced: Int?
        switch Self.playlistQueue(onDevice: sources.map { $0.localURL != nil }, startingAt: start,
                                   online: online ?? NetworkMonitor.shared.isConnected) {
        case .failure(let refusal): return refusal
        case .success(let plan): (places, forced) = plan
        }
        lastError = nil
        // The queue holds places in `tracks`, so each row keeps its page
        // position whichever songs were left out.
        let planned = planQueue(tracks: places, forcedStartIndex: forced, shuffleOn: shuffle)
        loadQueue(sources: sources, order: planned.tracks, startIndex: planned.startIndex,
                  format: format, sourceId: playlist.id)
        return nil
    }

    /// Which rows of a playlist are queued (their positions, in playlist
    /// order) and which of those is played first (`nil`: the first, or
    /// any when shuffling). `onDevice` says, row by row, whether the song
    /// has a file here; online every row is queued.
    nonisolated static func playlistQueue(
        onDevice: [Bool], startingAt start: Int?, online: Bool
    ) -> Result<(places: [Int], start: Int?), PlaylistPlayRefusal> {
        let places = online ? Array(onDevice.indices) : onDevice.indices.filter { onDevice[$0] }
        guard !places.isEmpty else { return .failure(.nothingDownloaded) }
        guard let start else { return .success((places, nil)) }
        guard let first = places.firstIndex(of: start) else { return .failure(.songNotDownloaded) }
        return .success((places, first))
    }

    // MARK: - Image requests

    /// Returns a `URLRequest` for an album's primary image, with the
    /// `Authorization` header set. Used by the now-playing views
    /// (which can't reach `JellyfinAPI` directly).
    public func imageRequest(forAlbumId albumID: String, size: Int = 500) -> URLRequest? {
        api.imageURLRequest(for: albumID, size: size)
    }

    /// The picture for a queued track: its own when it has one (on disk
    /// for downloads), else the album's.
    public func artRequest(for track: PlaybackTrack, size: Int = 500) -> URLRequest? {
        if let file = track.localArtURL { return URLRequest(url: file) }
        return api.imageURLRequest(for: track.artItemId, size: size)
    }

    // MARK: - Queue building

    /// One queued track's source: the server's track and the album it's
    /// shown with (its own, in a playlist).
    struct QueueSource {
        let track: JellyfinTrack
        let album: JellyfinAlbum
        /// The track's file on this device, when it has one (a downloaded
        /// playlist's, or a downloaded album's); nil means stream it.
        var localURL: URL? = nil
        /// The track's saved picture, for offline.
        var localArtURL: URL? = nil
    }

    /// Queues an album's tracks (see `startQueue(sources:…)`).
    private func startQueue(
        tracks: [JellyfinTrack],
        album: JellyfinAlbum,
        forcedStartIndex: Int?,
        format: AudioFormat
    ) {
        startQueue(sources: tracks.map { QueueSource(track: $0, album: album) },
                   forcedStartIndex: forcedStartIndex, format: format, sourceId: album.id)
    }

    /// Decides the order and start (`planQueue`) for `sources`, given in
    /// album or playlist order, then builds the queue and plays.
    private func startQueue(
        sources: [QueueSource],
        forcedStartIndex: Int?,
        format: AudioFormat,
        sourceId: String
    ) {
        let planned = planQueue(tracks: Array(sources.indices),
                                forcedStartIndex: forcedStartIndex, shuffleOn: shuffle)
        loadQueue(sources: sources, order: planned.tracks,
                  startIndex: planned.startIndex, format: format, sourceId: sourceId)
    }

    /// The queue's tracks for `sources` played in `order` (places in
    /// `sources`): each with its own album's name, ID and art.
    func playbackTracks(sources: [QueueSource], order: [Int]) -> [PlaybackTrack] {
        let cap = NetworkMonitor.shared.isExpensive ? PlaybackSettings.mobileDataBitrate : nil
        return order.enumerated().map { i, place in
            let source = sources[place]
            let artId = source.track.hasOwnArt ? source.track.id : source.album.id
            var pt = PlaybackTrack(
                track: source.track,
                album: source.album,
                albumArtURL: api.albumArtURL(for: artId, size: 600),
                localURL: source.localURL
            )
            pt.localArtURL = source.localArtURL
            // Some Jellyfin responses omit the IndexNumber; fill from the
            // position in the fetch.
            if pt.trackNumber == 0 { pt = pt.withTrackNumber(i + 1) }
            // On mobile data the stream is AAC at the cap, whatever the file is.
            // Lossy, so no bit depth; the sample rate stays the file's.
            if let cap, source.localURL == nil {
                pt.qualityLabel = "AAC \(cap / 1000) kbps"
                pt.bitDepth = nil
            }
            return pt
        }
    }

    /// Build an `AVPlayerItem` queue from the given tracks and start
    /// playing. Called by the `play` functions once the track list is
    /// known. The caller picks the order and start index via `planQueue`,
    /// so this only builds and enqueues.
    private func loadQueue(
        sources: [QueueSource],
        order: [Int],
        startIndex: Int,
        format: AudioFormat,
        sourceId: String
    ) {
        start() // idempotent

        // 1. Build PlaybackTracks.
        let playbackTracks = playbackTracks(sources: sources, order: order)

        // 2. Build AVPlayerItems and enqueue from startIndex to end.
        // `AVQueuePlayer.insert(_:after:)` is the cross-platform API
        // (iOS / macOS); `AVQueuePlayer.insert(_:at:)` (the
        // AVPlayerItemInsertionPoint variant) is iOS-only, so we
        // track the last-added item and append after it.
        // Items are built from each queue entry's place in `sources`, so the
        // queue can be reordered later (Maniac Mode un-shuffling it) and
        // can hold the same track twice.
        itemFactory = { [weak self] i in
            guard let self, self.queuePositions.indices.contains(i),
                  sources.indices.contains(self.queuePositions[i]) else { return nil }
            let source = sources[self.queuePositions[i]]
            // A file that has gone since the queue was built is streamed.
            if let file = source.localURL, FileManager.default.fileExists(atPath: file.path) {
                return AVPlayerItem(url: file)
            }
            return self.makePlayerItem(for: source.track, format: format)
        }
        reportStopped(atEnd: false)
        self.queue = playbackTracks
        self.queuePositions = order
        self.queueSourceId = sourceId
        enqueue(from: startIndex, count: playbackTracks.count)

        // 3. Publish state.
        self.currentIndex = startIndex < playbackTracks.count ? startIndex : 0
        self.currentTrack = playbackTracks.indices.contains(self.currentIndex)
            ? playbackTracks[self.currentIndex] : nil
        self.duration = self.currentTrack?.duration ?? 0
        self.position = 0
        updateNowPlayingForCurrent()
        reportStarted()
        isPlaying = true
        isLoading = false

        // 4. Start the show.
        player.play()
        // Downloaded tracks show the format and length of their files.
        if playbackTracks.contains(where: { $0.localURL != nil }) { loadLocalDurations(of: playbackTracks) }
    }

    // MARK: - Playback reporting

    /// Starts a server-side playback report for the current (streamed) track.
    private func reportStarted() {
        guard let track = currentTrack, track.localURL == nil else { return }
        reportedTrack = track
        api.reportPlayback(itemId: track.id, positionSeconds: 0, stopped: false)
    }

    /// Ends the open report. `atEnd` reports the full duration so the server
    /// counts the play; otherwise it reports where playback got to.
    private func reportStopped(atEnd: Bool) {
        guard let track = reportedTrack else { return }
        reportedTrack = nil
        let seconds = atEnd ? max(track.duration, position) : position
        api.reportPlayback(itemId: track.id, positionSeconds: seconds, stopped: true)
    }

    /// Replace the AVQueuePlayer queue with items `start..<count`.
    private func enqueue(from start: Int, count: Int) {
        player.pause()
        removeAllItems()
        // `preWarmNextItem` maps `currentIndex` to the queued item via this.
        enqueuedStartIndex = start
        var lastAdded: AVPlayerItem?
        for i in start..<count {
            guard let item = itemFactory?(i) else { continue }
            AudioEffects.shared.attach(to: item)  // equaliser + visualiser
            installItemEndObserver(for: item)
            player.insert(item, after: lastAdded)
            enqueuedItems.append(item)
            lastAdded = item
        }
    }

    /// The tracks that will play after the current one, in order, with
    /// their queue index. Wraps to the start when Repeat All is on.
    public var upNext: [(index: Int, track: PlaybackTrack)] {
        upNextIndices(count: queue.count, current: currentIndex, repeatMode: repeatMode)
            .map { ($0, queue[$0]) }
    }

    /// Play the queue entry at `index` (e.g. tapped in "Up next").
    public func skip(toQueueIndex index: Int) {
        guard !maniacMode else { return }
        jump(to: index)
    }

    /// Jump playback to `index`, rebuilding the queue so audio follows state.
    private func jump(to index: Int) {
        guard index >= 0, index < queue.count else { return }
        enqueue(from: index, count: queue.count)
        advanceTo(newIndex: index, startOfTrack: true)
        isPlaying = true
        player.play()
    }

    private func removeAllItems() {
        for item in enqueuedItems {
            player.remove(item)
            removeItemEndObserver(for: item)
        }
        enqueuedItems.removeAll(keepingCapacity: true)
    }

    /// Sets the player volume from the current track's `NormalizationGain`
    /// when "Normalise volume" is on (full volume otherwise, or when the
    /// track has no gain — e.g. downloaded files). Call after changing the setting.
    public func applyNormalization() {
        let gain = PlaybackSettings.normalizeVolume ? currentTrack?.normalizationGain : nil
        player.volume = PlaybackSettings.volume(forGain: gain)
    }

    private func makePlayerItem(for track: JellyfinTrack, format: AudioFormat) -> AVPlayerItem? {
        // On cellular / a hotspot, stream AAC at the chosen bitrate instead
        // of the original file.
        let cap = NetworkMonitor.shared.isExpensive ? PlaybackSettings.mobileDataBitrate : nil
        guard let url = stream.url(for: track, format: format, maxBitrate: cap) else {
            print("[PlayerManager] StreamURLBuilder returned nil for track \(track.id)")
            return nil
        }
        // Gapless: AVQueuePlayer natively pre-loads the next item; the
        // periodic time observer additionally asks the next item's asset
        // to start loading ~1s early (see `preWarmNextItem`).
        return AVPlayerItem(url: url)
    }

    public func pause() {
        player.pause()
        isPlaying = false
        nowPlaying.updateState(isPlaying: false, elapsedTime: position)
    }

    public func resume() {
        guard currentTrack != nil else { return }
        // After a failed track, Play tries that track again.
        if lastError == .trackFailed {
            lastError = nil
            jump(to: currentIndex)
            return
        }
        // After the album finished and went back to the start, this play is a new one.
        if reportedTrack == nil { reportStarted() }
        player.play()
        isPlaying = true
        nowPlaying.updateState(isPlaying: true, elapsedTime: position)
    }

    public func togglePlayPause() {
        if isPlaying { pause() } else { resume() }
    }

    public func skipToNext() {
        guard !maniacMode, !queue.isEmpty else { return }
        if currentIndex + 1 < queue.count {
            jump(to: currentIndex + 1)
        } else if repeatMode == .all {
            jump(to: 0)
        }
    }

    public func skipToPrevious() {
        guard !maniacMode, !queue.isEmpty else { return }
        // Skip to start of current track if we've already played > 3s,
        // otherwise step back.
        if position > 3 {
            player.seek(to: .zero) { _ in }
            position = 0
            widgetChanged()
            return
        }
        if currentIndex > 0 {
            jump(to: currentIndex - 1)
        }
    }

    /// Update the now-playing state for the new index. Used by both
    /// skip-forward and skip-backward. The AVQueuePlayer only *plays*
    /// from the head of the queue, so for "skip to N" we rebuild
    /// the queue from N.
    private func advanceTo(newIndex: Int, startOfTrack: Bool) {
        guard newIndex >= 0 && newIndex < queue.count else { return }
        reportStopped(atEnd: false)
        currentIndex = newIndex
        currentTrack = queue[newIndex]
        reportStarted()
        duration = currentTrack?.duration ?? 0
        position = 0
        updateNowPlayingForCurrent()
    }

    public func seek(toTime t: TimeInterval) {
        guard !maniacMode, let item = player.currentItem else { return }
        let clamped = max(0, min(t, duration > 0 ? duration : t))
        // `seek(to:completionHandler:)` is the modern API; `seek(to:)`
        // is deprecated but still works on all our target OSes.
        item.seek(to: CMTime(seconds: clamped, preferredTimescale: CMTimeScale(1000))) { _ in }
        position = clamped
        nowPlaying.updateState(isPlaying: isPlaying, elapsedTime: clamped)
        // The widget works out when the track ends from the position.
        widgetChanged()
    }

    /// Seek by `0 ... 1` fraction of the current track's duration.
    public func seek(toProgress p: Double) {
        guard duration > 0 else { return }
        seek(toTime: p * duration)
    }

    public func restartCurrentTrack() {
        guard !maniacMode else { return }
        player.seek(to: .zero) { _ in }
        position = 0
        player.play()
        widgetChanged()
    }

    public func setRepeatMode(_ mode: RepeatMode) {
        guard !maniacMode || mode == .off else { return }
        repeatMode = mode
    }

    /// Cycle through the three repeat modes on every tap. UI helper.
    public func cycleRepeatMode() {
        guard !maniacMode else { return }
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    public func toggleShuffle() {
        setShuffle(!shuffle)
    }

    /// Set the shuffle flag without rebuilding the queue. The flag is
    /// read at the *next* `play(album:)` / `play(_:in:)` call.
    public func setShuffle(_ newValue: Bool) {
        guard !maniacMode || !newValue else { return }
        shuffle = newValue
    }

    // MARK: - Maniac Mode

    /// Turns Maniac Mode on or off and saves it. Turning it on switches
    /// shuffle and repeat off and puts the rest of a shuffled album back in
    /// album order (the playing track carries on).
    public func setManiacMode(_ on: Bool) {
        PlaybackSettings.maniacMode = on
        guard on != maniacMode else { return }
        maniacMode = on
        if on {
            repeatMode = .off
            if shuffle {
                shuffle = false
                restoreAlbumOrder()
            }
        }
        nowPlaying.setSkipControlsEnabled(!on)
        NotificationCenter.default.post(name: Self.maniacModeDidChange, object: self)
    }

    /// Reorders the tracks after the current one into album order and
    /// requeues them, without interrupting the current track.
    private func restoreAlbumOrder() {
        guard queuePositions.count == queue.count, queue.indices.contains(currentIndex) else { return }
        let order = orderAfterCurrent(positions: queuePositions, current: currentIndex)
        guard order != Array(queue.indices) else { return }
        queue = order.map { queue[$0] }
        queuePositions = order.map { queuePositions[$0] }
        requeueAfterCurrent()
    }

    /// Replaces the player items after the current one with `queue`'s.
    private func requeueAfterCurrent() {
        let current = currentIndex - enqueuedStartIndex
        guard enqueuedItems.indices.contains(current) else { return }
        for item in enqueuedItems[(current + 1)...] {
            player.remove(item)
            removeItemEndObserver(for: item)
        }
        enqueuedItems.removeSubrange((current + 1)...)
        var last = enqueuedItems[current]
        guard currentIndex + 1 < queue.count else { return }
        for i in (currentIndex + 1)..<queue.count {
            guard let item = itemFactory?(i) else { continue }
            AudioEffects.shared.attach(to: item)
            installItemEndObserver(for: item)
            player.insert(item, after: last)
            enqueuedItems.append(item)
            last = item
        }
    }

    // MARK: - Home-screen widget

    /// Tells the widget's snapshot writer something it shows has changed
    /// (it throttles the writes). iOS only: the Mac has no widget.
    private func widgetChanged() {
        #if os(iOS)
        WidgetSnapshotWriter.shared.playerChanged(self)
        #endif
    }

    // MARK: - Now-playing centre

    private func updateNowPlayingForCurrent() {
        nowPlaying.update(
            track: currentTrack,
            artworkRequest: currentTrack.flatMap { artRequest(for: $0, size: 600) },
            savedArtwork: currentTrack.flatMap {
                $0.localArtURL ?? ($0.artItemId == $0.albumId ? DownloadPaths.savedCover(albumID: $0.albumId) : nil)
            })
    }

    private func onFail(_ err: PlaybackError) {
        lastError = err
        isLoading = false
        isPlaying = false
        nowPlaying.updateState(isPlaying: false, elapsedTime: 0)
    }

    // MARK: - Time observation

    private func installTimeObserver() {
        // Periodic time observer — fires at a sensible UI cadence.
        // `interval: 1.0` is the standard value for a slider.
        let interval: CMTime = CMTime(seconds: 1.0, preferredTimescale: 1000)
        timeObserverToken = player.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self] time in
            guard let self else { return }
            // The closure is `@Sendable`, but we're `@MainActor`, so
            // hop back to main explicitly.
            Task { @MainActor in
                let secs = time.seconds
                self.position = secs
                if self.duration <= 0,
                   let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 {
                    self.duration = d
                }
                // Gapless optimisation: ~1s before this item ends, start
                // loading the next item's asset (see `preWarmNextItem`).
                if shouldPreloadNext(position: secs, duration: self.duration) {
                    self.preWarmNextItem()
                }
            }
        }
    }

    /// Pre-warm the *next* queued item's audio asset ~1s before the
    /// current one ends, so an on-demand transcode has time to start.
    /// `AVQueuePlayer` already auto-advances and pre-queues, so this is a
    /// small optimisation — it moves nothing and starts no playback, it
    /// only asks the upcoming item to begin loading early.
    ///
    /// Additive and guarded: no next item (last track / empty queue) is a
    /// no-op.
    private func preWarmNextItem() {
        // `enqueuedStartIndex` is where the AVQueuePlayer queue begins, so
        // the next item to load is one past the currently-playing one.
        let nextItemIndex = (currentIndex - enqueuedStartIndex) + 1
        guard nextItemIndex >= 0, nextItemIndex < enqueuedItems.count else { return }
        // `AVAsset.loadTracks(withMediaType:)` is cross-platform
        // (iOS 16.4+ / macOS 13.0+, above our iOS 17 / macOS 14 targets)
        // and kicks off an early fetch of the item's audio media. `AVURLAsset`
        // also has a dedicated `preload()` on iOS, but it isn't on macOS, so
        // this is the one call that works for both targets. `AVPlayerItem.asset`
        // is non-optional, so no chaining here.
        enqueuedItems[nextItemIndex].asset
            .loadTracks(withMediaType: .audio) { _, _ in }
    }
}

// MARK: - Per-item end-of-track observers

private extension PlayerManager {
    /// Install `AVPlayerItemDidPlayToEndTime` notification on an item so
    /// the manager can advance to the next track / restart on `.one`.
    ///
    /// Uses the closure-based `addObserver(forName:object:queue:using:)`
    /// form (iOS 10 / macOS 10.12+) so we don't need `@objc` selectors or
    /// an `NSObject` superclass — which `PlayerManager` is not.
    func installItemEndObserver(for item: AVPlayerItem) {
        removeItemEndObserver(for: item)
        itemEndObservers[ObjectIdentifier(item)] = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.handleItemDidPlayToEndTime()
            }
        }
        // A track that stops partway (stream dropped, audio output failed) or
        // never loads (server error): stop and say so, rather than sitting
        // there "playing" nothing.
        let failed = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self, weak item] _ in
            Task { @MainActor in
                if let self, let item { self.handleItemFailed(item) }
            }
        }
        let status = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor [weak self] in self?.handleItemFailed(item) }
        }
        itemFailureObservers[ObjectIdentifier(item)] = (failed, status)
    }

    func removeItemEndObserver(for item: AVPlayerItem) {
        if let token = itemEndObservers.removeValue(forKey: ObjectIdentifier(item)) {
            NotificationCenter.default.removeObserver(token)
        }
        if let (failed, status) = itemFailureObservers.removeValue(forKey: ObjectIdentifier(item)) {
            NotificationCenter.default.removeObserver(failed)
            status.invalidate()
        }
    }

    /// Only the playing track's failure stops playback; a later track that
    /// fails to load early is dealt with when (if) it comes up.
    func handleItemFailed(_ item: AVPlayerItem) {
        let index = currentIndex - enqueuedStartIndex
        guard enqueuedItems.indices.contains(index), enqueuedItems[index] === item else { return }
        failedDetail = Self.failureDetail(of: item, expensive: NetworkMonitor.shared.isExpensive)
        print("[PlayerManager] track failed: \(failedDetail)")
        trackFailed()
    }

    /// "AVFoundation -11850 / -12939, HTTP 403, HLS, mobile data": the item's
    /// error and its cause, the last entry of its error log, the kind of
    /// stream and the kind of network.
    static func failureDetail(of item: AVPlayerItem, expensive: Bool) -> String {
        var parts: [String] = []
        if let error = item.error as NSError? {
            var text = "\(shortDomain(error.domain)) \(error.code)"
            if let cause = error.userInfo[NSUnderlyingErrorKey] as? NSError {
                text += " / \(cause.code)"
            }
            parts.append(text)
        }
        if let event = item.errorLog()?.events.last {
            let status = event.errorStatusCode
            if (400...599).contains(status) {
                parts.append("HTTP \(status)")
            } else if item.error == nil {
                parts.append("\(shortDomain(event.errorDomain)) \(status)")
            }
        }
        if let url = (item.asset as? AVURLAsset)?.url {
            if url.isFileURL {
                parts.append("file")
            } else if url.path.hasSuffix("/universal") {
                let hls = url.query?.contains("TranscodingProtocol=hls") == true
                parts.append(hls ? "converted, HLS" : "converted")
            } else {
                parts.append("original")
            }
        }
        parts.append(expensive ? "mobile data" : "Wi-Fi")
        return parts.joined(separator: ", ")
    }

    private static func shortDomain(_ domain: String) -> String {
        switch domain {
        case AVFoundationErrorDomain: "AVFoundation"
        case NSURLErrorDomain: "URL"
        case "CoreMediaErrorDomain": "CoreMedia"
        default: domain
        }
    }

    private func handleItemDidPlayToEndTime() {
        reportStopped(atEnd: true)
        if repeatMode == .one {
            jump(to: currentIndex)
        } else if currentIndex + 1 < queue.count {
            // AVQueuePlayer has already moved on; just sync our state.
            advanceTo(newIndex: currentIndex + 1, startOfTrack: true)
        } else if repeatMode == .all {
            jump(to: 0)
        } else {
            finishQueue()
        }
    }
}

extension PlayerManager {
    /// A call, Siri or another app took the audio (iOS has paused it): say
    /// so, so the app and the lock screen show "paused" rather than playing.
    func interruptionBegan() {
        resumeAfterInterruption = isPlaying
        if isPlaying { pause() }
    }

    /// The interruption ended: carry on if we were playing and iOS says to
    /// (after a call or Siri; not when another app's music took over).
    func interruptionEnded(shouldResume: Bool) {
        defer { resumeAfterInterruption = false }
        if shouldResume && resumeAfterInterruption { resume() }
    }

    /// The playing track failed: stop, end its server report and show why.
    /// Play (`resume`) tries the track again.
    func trackFailed() {
        reportStopped(atEnd: false)
        player.pause()
        failedTitle = currentTrack?.title ?? ""
        lastError = .trackFailed
        isLoading = false
        isPlaying = false
        nowPlaying.updateState(isPlaying: false, elapsedTime: position)
    }

    /// The last track ended (repeat off): close Now Playing and go back to
    /// the queue's first track at 0:00, paused, so the bar's progress line
    /// is at zero and Play starts the album again. Nothing is reported to the
    /// server until Play (`resume` starts the report).
    func finishQueue() {
        isNowPlayingPresented = false
        guard !queue.isEmpty else {
            pause()
            return
        }
        enqueue(from: 0, count: queue.count) // pauses the player
        currentIndex = 0
        currentTrack = queue[0]
        duration = queue[0].duration
        position = 0
        isPlaying = false
        updateNowPlayingForCurrent()
        nowPlaying.updateState(isPlaying: false, elapsedTime: 0)
    }
}

// MARK: - Small helpers on PlaybackTrack

private extension PlaybackTrack {
    /// What the file itself says replaces what the server said (a
    /// download may have been converted).
    func withQuality(_ quality: PlayerManager.LocalQuality?) -> PlaybackTrack {
        var copy = self
        copy.qualityLabel = quality?.label
        copy.sampleRate = quality?.sampleRate
        copy.bitDepth = quality?.bitDepth
        return copy
    }

    func withTitle(_ t: String) -> PlaybackTrack {
        var copy = PlaybackTrack(
            id: id,
            trackNumber: trackNumber,
            title: t,
            artist: artist,
            album: album,
            albumId: albumId,
            duration: duration,
            albumArtURL: albumArtURL,
            localURL: localURL
        )
        copy.normalizationGain = normalizationGain
        copy.qualityLabel = qualityLabel
        copy.sampleRate = sampleRate
        copy.bitDepth = bitDepth
        copy.artItemId = artItemId
        copy.localArtURL = localArtURL
        return copy
    }

    func withDuration(_ d: TimeInterval) -> PlaybackTrack {
        var copy = PlaybackTrack(
            id: id,
            trackNumber: trackNumber,
            title: title,
            artist: artist,
            album: album,
            albumId: albumId,
            duration: d,
            albumArtURL: albumArtURL,
            localURL: localURL
        )
        copy.normalizationGain = normalizationGain
        copy.qualityLabel = qualityLabel
        copy.sampleRate = sampleRate
        copy.bitDepth = bitDepth
        copy.artItemId = artItemId
        copy.localArtURL = localArtURL
        return copy
    }

    func withTrackNumber(_ n: Int) -> PlaybackTrack {
        // Re-init from components.
        var copy = PlaybackTrack(
            id: id,
            trackNumber: n,
            title: title,
            artist: artist,
            album: album,
            albumId: albumId,
            duration: duration,
            albumArtURL: albumArtURL,
            localURL: localURL
        )
        copy.normalizationGain = normalizationGain
        copy.qualityLabel = qualityLabel
        copy.sampleRate = sampleRate
        copy.bitDepth = bitDepth
        copy.artItemId = artItemId
        copy.localArtURL = localArtURL
        return copy
    }
}

// MARK: - Standalone queue planning

/// Queue indices that play after `current`. With Repeat All the queue
/// wraps, so tracks before `current` follow; otherwise it stops at the end.
/// Repeat One still lists what follows, for when the user skips.
func upNextIndices(count: Int, current: Int, repeatMode: RepeatMode) -> [Int] {
    guard count > 0, current >= 0, current < count else { return [] }
    let after = Array((current + 1)..<count)
    return repeatMode == .all ? after + Array(0..<current) : after
}

/// Queue order for Maniac Mode: tracks up to and including `current`
/// stay put (already played or playing); the rest go back in album or
/// playlist order. `positions` is each queue entry's place in that order
/// (a place, not a track ID: a playlist can hold a song twice). Returns
/// indices into `positions`.
func orderAfterCurrent(positions: [Int], current: Int) -> [Int] {
    guard current >= 0, current < positions.count else { return Array(positions.indices) }
    let rest = (current + 1..<positions.count).sorted {
        (positions[$0], $0) < (positions[$1], $1)
    }
    return Array(0...current) + rest
}

/// Decides the queue order and start index for a "play" call, given
/// the fetched track list, an optional index to play first, and the
/// shuffle flag. Defined as a top-level function so it can be
/// unit-tested without instantiating a `PlayerManager` (which is
/// `@MainActor`).
///
///   - Shuffle off: preserves the fetched order and starts at
///     `forcedStartIndex` (0 when `nil`) — so "tap track N" plays N.
///   - Shuffle on + a valid `forcedStartIndex`: that track is forced
///     to the head and the *remaining* tracks are randomized,
///     starting at 0.
///   - Shuffle on + `nil`/out-of-range `forcedStartIndex`: the whole
///     list is randomized, starting at 0.
func planQueue<Track>(
    tracks: [Track],
    forcedStartIndex: Int?,
    shuffleOn: Bool
) -> (tracks: [Track], startIndex: Int) {
    guard shuffleOn else {
        return (tracks, forcedStartIndex ?? 0)
    }
    if let forcedStartIndex, forcedStartIndex < tracks.count, forcedStartIndex >= 0 {
        var arr = tracks
        let first = arr.remove(at: forcedStartIndex)
        arr.shuffle()
        return ([first] + arr, 0)
    }
    var arr = tracks
    arr.shuffle()
    return (arr, 0)
}

/// Pure shuffle-with-forced-first helper — the `tracks` half of
/// `planQueue`. Kept top-level for the existing `applyShuffle`
/// unit tests.
func applyShuffle(
    on tracks: [JellyfinTrack],
    forcedFirst: Int?,
    shuffleOn: Bool
) -> [JellyfinTrack] {
    planQueue(tracks: tracks, forcedStartIndex: forcedFirst, shuffleOn: shuffleOn).tracks
}

// MARK: - Gapless pre-load

/// Decides whether we're within `leadTime` of the end of `duration` — the
/// moment to start pre-loading the next track's asset. Pure, so it can be
/// unit-tested without a live `AVQueuePlayer`.
func shouldPreloadNext(
    position: TimeInterval,
    duration: TimeInterval,
    leadTime: TimeInterval = 1.0
) -> Bool {
    guard duration > 0 else { return false }
    return position + leadTime >= duration
}