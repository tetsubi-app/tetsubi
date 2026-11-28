// MARK: - PlayerManager — AVQueuePlayer wrapper with now-playing integration

import Foundation
import Observation
import AVFoundation
import MediaPlayer
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Drives audio playback via `AVQueuePlayer` and integrates with
/// `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter`. The manager is
/// `@Observable` so SwiftUI views can read / update its state.
///
/// What plays is the listening queue (`playQueue`, kept by a
/// `PlayQueueController`): albums, playlists and tracks queued one after
/// another. The AVQueuePlayer holds the current entry and everything after
/// it in play order, so tracks join without gaps, also from one album to
/// the next. Each player item is mapped to its queue entry's id.
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

    /// The queue's tracks in play order (played ones too), derived from
    /// `playQueue`. The widget shows the queue's length and finish time,
    /// so edits are passed on too.
    public private(set) var queue: [PlaybackTrack] = [] {
        didSet { widgetChanged() }
    }
    /// The entry id of each of `queue`'s tracks.
    public private(set) var queueEntryIds: [String] = []
    /// The current track's place in `queue`.
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
            updateStarting()
        }
    }
    /// The playing track is loading after Play, a skip or a seek and no
    /// sound has come yet (AVPlayer `.waitingToPlayAtSpecifiedRate`, or a
    /// restored track waiting to reach its point). The Play/Pause buttons
    /// show a spinner then, and `togglePlayPause` does nothing, so a press
    /// while waiting can't pause what's about to start.
    public private(set) var isStarting: Bool = false
    /// When the user last asked for sound: Play, a skip or a seek.
    private var startedAt = Date.distantPast
    /// The clock for `ignoresPlayPause` (tests move it on).
    var now: () -> Date = Date.init
    /// How long into a track's start Play/Pause presses are ignored.
    nonisolated static let ignorePressesSeconds: TimeInterval = 10
    /// Whether a Play/Pause press is ignored now: only in the first
    /// `ignorePressesSeconds` of a start, and not once the start has been
    /// retried (`watchStart`), so an impatient press can't pause what's
    /// about to play while a later one still stops a start going nowhere.
    public var ignoresPlayPause: Bool {
        isStarting && startRetries == 0 && now().timeIntervalSince(startedAt) < Self.ignorePressesSeconds
    }
    public private(set) var position: TimeInterval = 0
    public private(set) var duration: TimeInterval = 0
    /// The listening queue: entries, play order, shuffle and repeat.
    public var playQueue: PlayQueue { controller.queue }
    public var repeatMode: RepeatMode { controller.queue.repeatMode }
    /// Shuffle across the whole queue.
    public var shuffle: Bool { controller.queue.shuffle }
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
    /// Keeps the queue and decides what the AVQueuePlayer holds.
    private let controller = PlayQueueController()
    /// Play Next / Add to Queue apply in the order they were asked for,
    /// even when a later one's tracks arrive first.
    private let addOrder = QueueAddOrder()

    private var player: AVQueuePlayer = AVQueuePlayer()
    /// The underlying player, for the Mac's AirPlay picker.
    var outputPlayer: AVPlayer { player }
    /// The player items handed to the AVQueuePlayer, in order (played ones
    /// stay until the queue moves past them), and each one's entry id.
    private var enqueuedItems: [AVPlayerItem] = []
    private var enqueuedEntryIds: [String] = []
    /// End-of-track observer per queued item, removed when the item leaves
    /// the queue (otherwise NotificationCenter keeps one per track ever queued).
    private var itemEndObservers: [ObjectIdentifier: NSObjectProtocol] = [:]
    /// Per queued item: its failed-to-play notification and `status` observation.
    private var itemFailureObservers: [ObjectIdentifier: (NSObjectProtocol, NSKeyValueObservation)] = [:]
    /// A restored queue starts partway into its track: the item and where,
    /// applied once the item is ready (an item can't seek before that).
    /// `play`: the item starts playing once it's there (a restored queue's
    /// first Play), rather than from 0:00 for a moment.
    private var pendingStartSeek: (item: ObjectIdentifier, seconds: Double, play: Bool)? {
        didSet { updateStarting() }
    }
    /// A paused load held back: the entries and the point, with no player
    /// item made and no connection opened until Play (`resume`). A restored
    /// queue is held so at launch, when the route to the server may not be
    /// up yet; so is any queue loaded paused.
    private var deferredLoad: (entryIds: [String], position: Double)?
    /// Watches a converted stream's start (see `watchStart`).
    private var startWatch: Task<Void, Never>?
    /// Tries made by `watchStart` for the playing item so far (internal for tests).
    var startRetries = 0
    /// AVPlayer's waiting state, for `isStarting`.
    private var timeControlObservation: NSKeyValueObservation?
    /// Track with an open server playback report (streamed tracks only).
    private var reportedTrack: PlaybackTrack?
    /// What each queue entry plays and shows, by entry id: the track's
    /// metadata as Now Playing shows it, and its file when it has one.
    private var media: [String: PlaybackTrack] = [:]
    /// Each entry's place in the album or playlist it was queued from
    /// (a place, not a track ID: a playlist can hold the same song twice).
    private var sourcePositions: [String: Int] = [:]
    private var timeObserverToken: Any?
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var lastPeriodicSave = Date.distantPast
    private var isConfigured = false

    /// The album or playlist the current entry was queued from (the
    /// album's ID, the merged entry's for a multi-disc album, or the
    /// playlist's; the first track's for single tracks). Nil when nothing
    /// is queued. "Is this album playing" checks use it.
    public var queueSourceId: String? { controller.queue.currentEntry?.group.id }

    /// The playing track's place in the album or playlist it was queued
    /// from, whatever order the queue plays in.
    public var currentSourcePosition: Int? {
        guard let entry = controller.queue.currentEntry else { return nil }
        if let place = sourcePositions[entry.id] { return place }
        return controller.queue.entries.filter { $0.batchId == entry.batchId }.firstIndex { $0.id == entry.id }
    }

    /// `queueSaver` saves the queue across launches (the app's player);
    /// nil saves nothing (tests, previews).
    public init(api: JellyfinAPI, queueSaver: PlayQueueSaver? = nil) {
        self.api = api
        self.audioSession = AudioSession()
        self.nowPlaying = NowPlayingController()
        self.stream = StreamURLBuilder(api: api, userId: api.userId ?? "")
        controller.saver = queueSaver
        controller.serverId = api.baseURL.absoluteString
        controller.userId = api.userId
        controller.maniacMode = maniacMode
        controller.engine = self
        controller.position = { [weak self] in self?.position ?? 0 }
        controller.isPlayable = { [weak self] entry in self?.isPlayable(entry) ?? false }
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
        installLifecycleObservers()
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.updateStarting() }
        }
        // `installItemEndObserver` is called per item as it's queued — no
        // global observer needed here.
        isConfigured = true
    }

    /// Stops and empties the queue. The saved queue keeps the latest state
    /// unless `forgetQueue` (signing out), which removes it.
    public func stop(forgetQueue: Bool = false) {
        reportStopped(atEnd: false)
        controller.clear(forget: forgetQueue)
        player.pause()
        clearState()
    }

    private func clearState() {
        media = [:]
        sourcePositions = [:]
        queue = []
        queueEntryIds = []
        currentIndex = 0
        currentTrack = nil
        isPlaying = false
        position = 0
        duration = 0
        nowPlaying.clear()
    }

    /// The saved queue is written when the app goes to the background or
    /// quits (the Mac quits when its window closes).
    private func installLifecycleObservers() {
        #if canImport(UIKit)
        let name = UIApplication.didEnterBackgroundNotification
        #elseif canImport(AppKit)
        let name = NSApplication.willTerminateNotification
        #endif
        lifecycleObservers.append(NotificationCenter.default.addObserver(
            forName: name, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.controller.flushSave() }
        })
    }

    // MARK: - Saved queue

    /// Brings back the queue saved for this server and user, paused at the
    /// track and position it was at (Play carries on). Nothing is reported
    /// to the server until it plays. Does nothing when something is queued.
    public func restoreSavedQueue() {
        guard controller.queue.isEmpty,
              let snapshot = PlayQueueStore.load(serverId: controller.serverId, userId: controller.userId)
        else { return }
        start()
        guard let at = controller.restore(snapshot, register: { entries in
            for entry in entries { media[entry.id] = restoredTrack(entry) }
        }) else {
            // Nothing in it can play now: nothing is kept.
            media = [:]
            return
        }
        syncFromQueue()
        duration = currentTrack?.duration ?? 0
        position = at
        isPlaying = false
        isLoading = false
        lastError = nil
        updateNowPlayingForCurrent()
        nowPlaying.updateState(isPlaying: false, elapsedTime: at)
        loadLocalDetails(entryIds: controller.queue.entries.map(\.id))
    }

    /// Whether an entry can play now: its file is here, or it's a server
    /// track and there's a connection.
    private func isPlayable(_ entry: QueueEntry) -> Bool {
        if let file = media[entry.id]?.localURL, FileManager.default.fileExists(atPath: file.path) { return true }
        return !Self.isLocalOnly(entry.track.id) && NetworkMonitor.shared.isConnected
    }

    /// Tracks played from a downloaded album's folder have ids made from the
    /// album and file ("albumID/01 - Song.flac"), not the server's: they
    /// can't be streamed.
    nonisolated static func isLocalOnly(_ trackId: String) -> Bool { trackId.contains("/") }

    /// A saved entry as the player shows and plays it: its file when it's on
    /// this device (a downloaded album's or playlist's), else the stream.
    private func restoredTrack(_ entry: QueueEntry) -> PlaybackTrack {
        let t = entry.track
        var localURL: URL?
        var localArt: URL?
        if Self.isLocalOnly(t.id), let slash = t.id.firstIndex(of: "/") {
            let albumID = String(t.id[..<slash])
            let name = String(t.id[t.id.index(after: slash)...])
            let file = DownloadPaths.albumDir(albumID).appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: file.path) { localURL = file }
            localArt = TrackCovers.existing(albumID: albumID, fileName: name)
        } else {
            let fileNumber = (t.discNumber ?? 1) > 1 ? (t.discNumber ?? 1) * 100 + t.trackNumber : t.trackNumber
            if entry.group.kind == .playlist {
                let files = PlaylistFiles(playlistID: entry.group.id)
                localURL = files.file(trackID: t.id, albumID: t.albumId, title: t.title, fileNumber: fileNumber)
                localArt = PlaylistDownloadPaths.savedArt(playlistID: entry.group.id, itemID: t.artItemId)
            } else {
                localURL = PlaylistFiles(playlistID: "").albumFile(albumID: t.albumId, title: t.title,
                                                                   fileNumber: fileNumber)
            }
        }
        var pt = PlaybackTrack(id: t.id, trackNumber: t.trackNumber, title: t.title, artist: t.artist,
                               album: t.album, albumId: t.albumId, duration: t.durationSeconds,
                               albumArtURL: api.albumArtURL(for: t.artItemId, size: 600), localURL: localURL)
        pt.artItemId = t.artItemId
        pt.albumArtistId = t.artistId
        pt.localArtURL = localArt
        pt.normalizationGain = t.normalizationGain
        pt.codec = t.codec
        pt.sampleRate = t.sampleRate
        pt.bitDepth = t.bitDepth
        pt.qualityLabel = PlaybackTrack.qualityLabel(format: AudioFormat.source(codec: t.codec, container: t.container),
                                                     bitrate: t.bitrate)
        if localURL == nil {
            pt.describeStream(codec: t.codec, container: t.container,
                              mobileCap: NetworkMonitor.shared.isExpensive ? PlaybackSettings.mobileDataBitrate : nil)
        }
        return pt
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
        let items = localItems(albumIDs: albumIDs, albumTitle: albumTitle, artistName: artistName)
        guard !items.isEmpty else {
            print("[PlayerManager] No local tracks for \(albumIDs)")
            return
        }
        let group = QueueGroup(id: sourceId, kind: .album, title: albumTitle,
                               artist: artistName.isEmpty ? nil : artistName, artItemId: albumIDs.first)
        addOrder.finish(addOrder.replace()) { [self] in place(items, group: group, how: .replace(startIndex: nil)) }
    }

    /// A downloaded album's tracks from its files (several discs back to back).
    private func localItems(albumIDs: [String], albumTitle: String, artistName: String) -> [QueueItem] {
        albumIDs.flatMap { albumID -> [QueueItem] in
            let artURL = api.albumArtURL(for: albumID, size: 600)
            let local = DownloadPaths.localTracks(albumID: albumID)
            // Saved titles are by number; downloads of untagged tracks made
            // before numbering by position share one number, so theirs are
            // matched by file name instead.
            let clashing = Self.numbersClash(local.map(\.trackNumber))
            let titles = clashing ? [:] : DownloadedTitles.titles(for: albumID)
            let byFile = clashing ? DownloadedTitles.titlesByFile(for: albumID) : [:]
            return local.enumerated().map { i, entry in
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
                // Format and bitrate come from the file itself (loadLocalDetails).
                return QueueItem(queueTrack: QueueTrack(playbackTrack: track), playback: track, sourcePosition: i)
            }
        }
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
                    self.patchMedia { track in
                        guard track.albumId == albumID, let name = track.localURL?.lastPathComponent,
                              let art = TrackCovers.existing(albumID: albumID, fileName: name) else { return nil }
                        var copy = track
                        copy.localArtURL = art
                        return copy
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
                    self?.patchMedia { track in
                        guard track.albumId == albumID, track.localURL != nil,
                              let title = titles[track.trackNumber] else { return nil }
                        return track.withTitle(title)
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
                self?.patchMedia { track in
                    guard track.albumId == albumID, let url = track.localURL,
                          let title = byFile[DownloadedTitles.fileTitle(url.lastPathComponent)] else { return nil }
                    return track.withTitle(title)
                }
            }
        }
    }

    /// Changes queued tracks (`change` returns the new track, or nil to
    /// leave it) and shows the result.
    private func patchMedia(_ change: (PlaybackTrack) -> PlaybackTrack?) {
        var changed = false
        for (id, track) in media {
            if let new = change(track) {
                media[id] = new
                changed = true
            }
        }
        guard changed else { return }
        syncFromQueue()
        updateNowPlayingForCurrent()
    }

    /// Downloaded entries: their files carry no server runtime, so read
    /// each file's length, format and quality from disk (drives the seek
    /// bar and the queue's length).
    private func loadLocalDetails(entryIds: [String]) {
        let local = entryIds.compactMap { id in media[id].flatMap { $0.localURL == nil ? nil : (id, $0) } }
        guard !local.isEmpty else { return }
        Task { @MainActor [weak self] in
            for (id, track) in local {
                guard let url = track.localURL else { continue }
                let asset = AVURLAsset(url: url)
                guard let time = try? await asset.load(.duration) else { continue }
                let secs = time.seconds
                let quality = await Self.localQuality(of: asset, url: url, seconds: secs)
                guard let self, secs.isFinite, secs > 0, let now = self.media[id] else { continue }
                // Only a downloaded album's own files say more than the server.
                let updated = Self.isLocalOnly(now.id) || now.duration <= 0
                    ? now.withDuration(secs).withQuality(quality) : now.withDuration(secs)
                self.media[id] = updated
                self.syncFromQueue()
                if id == self.controller.queue.currentEntryId { self.duration = secs }
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

    // MARK: - Times

    /// Total length of the whole queue, in seconds (the album's, when only
    /// one album is queued).
    public var queueLength: TimeInterval {
        queue.reduce(0) { $0 + $1.duration }
    }

    /// Time left in the whole queue: the rest of this track plus every
    /// track after it in play order. "Ends:" in Now Playing and the widget.
    public var queueRemaining: TimeInterval {
        let rest = queue.indices.contains(currentIndex)
            ? queue[(currentIndex + 1)...].reduce(0) { $0 + $1.duration } : 0
        return max(0, duration - position) + rest
    }

    /// Time left in the current album or playlist: the rest of this track
    /// plus the tracks after it queued with it (its display group).
    public var currentGroupRemaining: TimeInterval {
        let order = controller.queue.entriesInPlayOrder
        guard order.count == queue.count, order.indices.contains(currentIndex) else { return 0 }
        let batch = order[currentIndex].batchId
        var rest = 0.0
        var i = currentIndex + 1
        while i < order.count, order[i].batchId == batch {
            rest += queue[i].duration
            i += 1
        }
        return max(0, duration - position) + rest
    }

    /// The album IDs of the current entry's album, in queue order (each
    /// disc of a multi-disc album), for the widget's Play.
    var currentBatchAlbumIds: [String] {
        guard let current = controller.queue.currentEntry else { return [] }
        return controller.queue.entries.filter { $0.batchId == current.batchId }.map(\.track.albumId)
    }

    /// Pressing Play on an album or playlist should ask Replace queue /
    /// Play Next / Add to Queue (`PlayQueue.needsPlayPrompt`).
    public var needsPlayPrompt: Bool { controller.queue.needsPlayPrompt }

    /// Now Playing's end times: "Album ends 13:02 · Queue ends 15:40" (widest
    /// first; see `QueueEndTimes`), from `now`.
    public func endTimesVariants(now: Date, format: (Date) -> String) -> [String] {
        let queue = controller.queue
        let kind = queue.currentEntry?.group.kind ?? .album
        return QueueEndTimes.variants(
            groupLabel: QueueEndTimes.groupLabel(kind: kind, count: queue.currentGroupCount),
            groupEnd: format(now.addingTimeInterval(currentGroupRemaining)),
            queueEnd: format(now.addingTimeInterval(queueRemaining)),
            moreQueued: queue.hasMoreThanCurrentGroup)
    }

    /// The track shown for a queue entry (Now Playing's queue).
    public func track(forEntry entry: QueueEntry) -> PlaybackTrack {
        media[entry.id] ?? restoredTrack(entry)
    }

    /// Whether the current entry was queued from an album (not a playlist
    /// or single tracks).
    var currentGroupIsAlbum: Bool { controller.queue.currentEntry?.group.kind == .album }

    // MARK: - Playback control

    /// Play an album from the given index, replacing the queue. Streams
    /// don't use `format`: what is streamed follows each track's source
    /// (`StreamURLBuilder.plan`); it only reaches the downloaded-album path.
    ///
    /// If the album is fully downloaded locally, it plays from disk
    /// automatically. Otherwise it fetches the track list and streams
    /// from Jellyfin.
    ///
    /// With shuffle on, the queue is shuffled; a given `startIndex` (e.g.
    /// the user tapped a specific track) plays first.
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
        // Pending Play Next / Add to Queue are dropped; adds from now on wait for this.
        let turn = addOrder.replace()
        // Maniac Mode always starts at track 1.
        let startIndex = maniacMode ? 0 : startIndex
        api.tracksForAlbum(album.id) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(let tracks):
                    self.addOrder.finish(turn) {
                        self.startQueue(tracks: tracks, album: album,
                                        forcedStartIndex: startIndex > 0 ? startIndex : nil, format: format)
                    }
                case .failure(let err):
                    print("[PlayerManager] tracksForAlbum failed: \(err.errorDescription ?? "\(err)")")
                    if self.addOrder.finish(turn, nil) { self.onFail(.unknown) }
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
                let turn = addOrder.replace()
                api.tracksForAlbum(only.id) { [weak self] result in
                    DispatchQueue.main.async {
                        // Not when another Play came first.
                        guard self?.addOrder.isPending(turn) == true,
                              case .success(let tracks) = result,
                              let track = tracks.first(where: { $0.id == trackID }) else { return }
                        self?.play(track, in: only, format: format)
                    }
                }
            } else {
                play(album: only, format: format)
            }
            return
        }

        if Self.allDownloaded(discs) && trackID == nil {
            playLocal(albumIDs: discs.map(\.id), sourceId: album.id, format: format,
                      albumTitle: album.displayName, artistName: album.artistName)
            return
        }

        isLoading = true
        lastError = nil
        let turn = addOrder.replace()
        fetchTracks(discs: discs) { [weak self] tracks in
            guard let self else { return }
            guard let tracks, !tracks.isEmpty else {
                print("[PlayerManager] No tracks for discs \(discs.map(\.id))")
                if self.addOrder.finish(turn, nil) { self.onFail(.unknown) }
                return
            }
            let start = trackID.flatMap { id in tracks.firstIndex { $0.id == id } }
            self.addOrder.finish(turn) {
                self.startQueue(tracks: tracks, album: album, forcedStartIndex: start, format: format)
            }
        }
    }

    /// Play a single track by its `JellyfinTrack` — fetches the rest
    /// of the album's tracks and starts with the tapped track first.
    ///
    /// If `self.shuffle` is on, the tapped track plays first, then
    /// the rest of the queue is randomized after it.
    public func play(
        _ track: JellyfinTrack,
        in album: JellyfinAlbum,
        format: AudioFormat = .alac
    ) {
        isLoading = true
        lastError = nil
        let turn = addOrder.replace()
        api.tracksForAlbum(album.id) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(let tracks):
                    // Maniac Mode always starts at track 1.
                    let start = self.maniacMode ? nil : tracks.firstIndex { $0.id == track.id }
                    self.addOrder.finish(turn) {
                        self.startQueue(tracks: tracks, album: album, forcedStartIndex: start, format: format)
                    }
                case .failure(let err):
                    print("[PlayerManager] tracksForAlbum(\(track.id)) failed: \(err.errorDescription ?? "\(err)")")
                    if self.addOrder.finish(turn, nil) { self.onFail(.unknown) }
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
        switch playlistItems(playlist, tracks: tracks, startingAt: start, format: format, online: online) {
        case .failure(let refusal):
            return refusal
        case .success(let (items, first)):
            lastError = nil
            addOrder.finish(addOrder.replace()) { [self] in
                place(items, group: Self.group(for: playlist), how: .replace(startIndex: first))
            }
            return nil
        }
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

    /// A playlist's rows as queue items (offline, only those on this
    /// device), and the place among them to start at.
    private func playlistItems(
        _ playlist: JellyfinPlaylist, tracks: [PlaylistEntry], startingAt start: Int?,
        format: AudioFormat, online: Bool?
    ) -> Result<([QueueItem], Int?), PlaylistPlayRefusal> {
        let files = PlaylistFiles(playlistID: playlist.id)
        let sources = tracks.map { entry in
            QueueSource(track: entry.track, album: entry.album,
                        localURL: files.file(for: entry),
                        localArtURL: PlaylistDownloadPaths.savedArt(playlistID: playlist.id,
                                                                    itemID: entry.artItemId))
        }
        switch Self.playlistQueue(onDevice: sources.map { $0.localURL != nil }, startingAt: start,
                                  online: online ?? NetworkMonitor.shared.isConnected) {
        case .failure(let refusal):
            return .failure(refusal)
        case .success(let plan):
            // Each item keeps its row's place, whichever songs were left out.
            return .success((items(sources: sources, order: plan.places), plan.start))
        }
    }

    // MARK: - Play Next / Add to Queue

    /// Maniac Mode refuses Play Next, silently, as it does the other queue
    /// edits (`PlayQueue.canEdit`); Add to Queue and Play stay allowed.
    /// The menus hide it, and the queue controller refuses it too.
    public var refusesPlayNext: Bool { !PlayQueue.canEdit(.playNext, maniacMode: maniacMode) }

    /// Queues an album (every disc, or `discs`) right after the current
    /// album or playlist. Downloaded albums are queued from disk; offline
    /// only those are. `completion` says whether anything was queued (the
    /// caller shows the confirmation). Into an empty queue it plays.
    public func playNext(album: JellyfinAlbum, discs: [JellyfinAlbum]? = nil, format: AudioFormat = .alac,
                         completion: ((Bool) -> Void)? = nil) {
        guard !refusesPlayNext else { return }
        queueAlbum(album, discs: discs ?? album.allDiscs, format: format, how: .playNext, completion: completion)
    }

    /// Queues an album (every disc, or `discs`) at the end of the queue.
    public func addToQueue(album: JellyfinAlbum, discs: [JellyfinAlbum]? = nil, format: AudioFormat = .alac,
                           completion: ((Bool) -> Void)? = nil) {
        queueAlbum(album, discs: discs ?? album.allDiscs, format: format, how: .addToQueue, completion: completion)
    }

    /// Queues a playlist's rows after the current album or playlist.
    /// Offline only the songs on this device; nil when queued, else why not.
    @discardableResult
    public func playNext(playlist: JellyfinPlaylist, tracks: [PlaylistEntry], format: AudioFormat = .alac,
                         online: Bool? = nil) -> PlaylistPlayRefusal? {
        guard !refusesPlayNext else { return nil }
        return queuePlaylist(playlist, tracks: tracks, format: format, online: online, how: .playNext)
    }

    /// Queues a playlist's rows at the end of the queue.
    @discardableResult
    public func addToQueue(playlist: JellyfinPlaylist, tracks: [PlaylistEntry], format: AudioFormat = .alac,
                           online: Bool? = nil) -> PlaylistPlayRefusal? {
        queuePlaylist(playlist, tracks: tracks, format: format, online: online, how: .addToQueue)
    }

    /// Queues single tracks of `album` after the current album or playlist.
    /// Offline only tracks on this device. Returns whether any were queued.
    @discardableResult
    public func playNext(tracks: [JellyfinTrack], in album: JellyfinAlbum, format: AudioFormat = .alac) -> Bool {
        guard !refusesPlayNext else { return false }
        return queueTracks(tracks, in: album, format: format, how: .playNext)
    }

    /// Queues single tracks of `album` at the end of the queue.
    @discardableResult
    public func addToQueue(tracks: [JellyfinTrack], in album: JellyfinAlbum, format: AudioFormat = .alac) -> Bool {
        queueTracks(tracks, in: album, format: format, how: .addToQueue)
    }

    private func queueAlbum(_ album: JellyfinAlbum, discs: [JellyfinAlbum], format: AudioFormat,
                            how: Placement, completion: ((Bool) -> Void)?) {
        let discs = discs.isEmpty ? [album] : discs
        let group = Self.group(for: album)
        // Its turn is taken now, at the tap; it's placed once earlier adds are.
        let turn = addOrder.reserve()
        if Self.allDownloaded(discs) {
            let items = localItems(albumIDs: discs.map(\.id), albumTitle: album.displayName,
                                   artistName: album.artistName)
            addOrder.finish(turn) { [self] in completion?(place(items, group: group, how: how)) }
            return
        }
        guard NetworkMonitor.shared.isConnected else {
            addOrder.finish(turn, nil)
            completion?(false)
            return
        }
        fetchTracks(discs: discs) { [weak self] tracks in
            guard let self else { return }
            guard let tracks, !tracks.isEmpty else {
                // A failed fetch gives up its turn without holding up later
                // adds (no notice if a Play cancelled it meanwhile).
                if self.addOrder.finish(turn, nil) { completion?(false) }
                return
            }
            self.addOrder.finish(turn) {
                let sources = tracks.map { QueueSource(track: $0, album: album) }
                completion?(self.place(self.items(sources: sources, order: Array(sources.indices)),
                                       group: group, how: how))
            }
        }
    }

    private func queuePlaylist(_ playlist: JellyfinPlaylist, tracks: [PlaylistEntry], format: AudioFormat,
                               online: Bool?, how: Placement) -> PlaylistPlayRefusal? {
        guard !tracks.isEmpty else { return nil }
        switch playlistItems(playlist, tracks: tracks, startingAt: nil, format: format, online: online) {
        case .failure(let refusal):
            return refusal
        case .success(let (items, _)):
            guard !items.isEmpty else { return nil }
            addOrder.finish(addOrder.reserve()) { [self] in
                    place(items, group: Self.group(for: playlist), how: how)
            }
            return nil
        }
    }

    private func queueTracks(_ tracks: [JellyfinTrack], in album: JellyfinAlbum, format: AudioFormat,
                             how: Placement) -> Bool {
        guard let first = tracks.first else { return false }
        let online = NetworkMonitor.shared.isConnected
        // Rows built from a download (ids "<album>/<file>") play their files;
        // the rest stream, so offline they're left out.
        var localByAlbum: [String: [QueueItem]] = [:]
        var queued: [QueueItem] = []
        for track in tracks {
            if Self.isLocalOnly(track.id) {
                let albumID = track.albumId ?? String(track.id.prefix { $0 != "/" })
                if localByAlbum[albumID] == nil {
                    localByAlbum[albumID] = localItems(albumIDs: [albumID], albumTitle: track.album ?? album.displayName,
                                                       artistName: album.artistName)
                }
                if let item = localByAlbum[albumID]?.first(where: { $0.playback.id == track.id }) {
                    queued.append(item)
                }
            } else if online {
                let source = QueueSource(track: track, album: album)
                queued.append(contentsOf: items(sources: [source], order: [0]))
            }
        }
        let group = QueueGroup(id: first.id, kind: .tracks, title: first.displayName,
                               artist: album.artistName.isEmpty ? nil : album.artistName,
                               artItemId: Self.isLocalOnly(first.id) ? first.albumId ?? album.id : album.id)
        guard !queued.isEmpty else { return false }
        addOrder.finish(addOrder.reserve()) { [self] in place(queued, group: group, how: how) }
        return true
    }

    /// Queues downloaded albums (every disc, in order) from disk after the
    /// current album or playlist; `sourceId` is the merged entry's id.
    @discardableResult
    public func playNext(downloadedAlbumIDs albumIDs: [String], sourceId: String,
                         albumTitle: String = "", artistName: String = "") -> Bool {
        guard !refusesPlayNext else { return false }
        return queueDownloaded(albumIDs, sourceId: sourceId, albumTitle: albumTitle, artistName: artistName, how: .playNext)
    }

    /// Queues downloaded albums from disk at the end of the queue.
    @discardableResult
    public func addToQueue(downloadedAlbumIDs albumIDs: [String], sourceId: String,
                           albumTitle: String = "", artistName: String = "") -> Bool {
        queueDownloaded(albumIDs, sourceId: sourceId, albumTitle: albumTitle, artistName: artistName, how: .addToQueue)
    }

    private func queueDownloaded(_ albumIDs: [String], sourceId: String, albumTitle: String, artistName: String,
                                 how: Placement) -> Bool {
        let items = localItems(albumIDs: albumIDs, albumTitle: albumTitle, artistName: artistName)
        let group = QueueGroup(id: sourceId, kind: .album, title: albumTitle,
                               artist: artistName.isEmpty ? nil : artistName, artItemId: albumIDs.first)
        guard !items.isEmpty else { return false }
        addOrder.finish(addOrder.reserve()) { [self] in place(items, group: group, how: how) }
        return true
    }

    // MARK: - Artist

    /// Plays an artist's `albums` (as their page shows them): oldest release
    /// first, each in disc and track order, up to `ArtistTracks.cap` tracks,
    /// replacing the queue. `shuffled` turns shuffle on first (the Shuffle
    /// button; Maniac Mode never shuffles and plays in order); otherwise
    /// shuffle stays as it is, as for an album. Downloaded albums play from
    /// their files; offline, only those do. `completion` says how it ended.
    public func play(artist: JellyfinArtist, albums: [JellyfinAlbum], shuffled: Bool = false,
                     completion: ((ArtistTracks.Outcome) -> Void)? = nil) {
        isLoading = true
        lastError = nil
        let turn = addOrder.replace()
        artistItems(albums) { [weak self] result in
            guard let self else { return }
            let finish: (ArtistTracks.Outcome) -> Void = { outcome in
                // Not when another Play came first.
                if self.addOrder.finish(turn, nil) {
                    self.isLoading = false
                    completion?(outcome)
                }
            }
            guard let (items, capped) = result else { return finish(.notLoaded) }
            guard !items.isEmpty else { return finish(ArtistTracks.emptyOutcome(online: NetworkMonitor.shared.isConnected)) }
            self.addOrder.finish(turn) {
                if shuffled, !self.maniacMode { self.setShuffle(true) }
                self.place(items, group: ArtistTracks.group(id: artist.id, name: artist.name), how: .replace(startIndex: nil))
                completion?(.done(capped: capped))
            }
        }
    }

    /// Queues an artist's tracks (as `play(artist:)`) after the current album or playlist.
    public func playNext(artist: JellyfinArtist, albums: [JellyfinAlbum],
                         completion: ((ArtistTracks.Outcome) -> Void)? = nil) {
        guard !refusesPlayNext else { return }
        queueArtist(artist, albums: albums, how: .playNext, completion: completion)
    }

    /// Queues an artist's tracks at the end of the queue.
    public func addToQueue(artist: JellyfinArtist, albums: [JellyfinAlbum],
                           completion: ((ArtistTracks.Outcome) -> Void)? = nil) {
        queueArtist(artist, albums: albums, how: .addToQueue, completion: completion)
    }

    private func queueArtist(_ artist: JellyfinArtist, albums: [JellyfinAlbum], how: Placement,
                             completion: ((ArtistTracks.Outcome) -> Void)?) {
        let turn = addOrder.reserve()
        artistItems(albums) { [weak self] result in
            guard let self else { return }
            guard let (items, capped) = result else {
                if self.addOrder.finish(turn, nil) { completion?(.notLoaded) }
                return
            }
            guard !items.isEmpty else {
                if self.addOrder.finish(turn, nil) {
                    completion?(ArtistTracks.emptyOutcome(online: NetworkMonitor.shared.isConnected))
                }
                return
            }
            self.addOrder.finish(turn) {
                self.place(items, group: ArtistTracks.group(id: artist.id, name: artist.name), how: how)
                completion?(.done(capped: capped))
            }
        }
    }

    /// An artist's albums as queue items, in `ArtistTracks.albumOrder`: from the
    /// files for a downloaded album (offline, only those), else streamed; at most
    /// `ArtistTracks.cap` of them (the flag says some were left out). Albums are
    /// fetched one at a time and no more once the cap is passed. Nil when a
    /// fetch failed.
    private func artistItems(_ albums: [JellyfinAlbum],
                             completion: @escaping (([QueueItem], capped: Bool)?) -> Void) {
        let online = NetworkMonitor.shared.isConnected
        func next(_ rest: ArraySlice<JellyfinAlbum>, _ items: [QueueItem]) {
            guard let album = rest.first, ArtistTracks.needsMore(trackCount: items.count) else {
                let (kept, capped) = ArtistTracks.limit(items)
                // Each entry's place is its position in this queue (Maniac Mode's order).
                completion((kept.enumerated().map { i, item in
                    var item = item
                    item.sourcePosition = i
                    return item
                }, capped))
                return
            }
            let more = rest.dropFirst()
            let discs = album.allDiscs
            if Self.allDownloaded(discs) {
                next(more, items + localItems(albumIDs: discs.map(\.id), albumTitle: album.displayName,
                                              artistName: album.artistName))
            } else if !online {
                let have = discs.filter { DownloadPaths.exists(albumID: $0.id) && DownloadPaths.fileCount(albumID: $0.id) > 0 }
                next(more, have.isEmpty ? items : items + localItems(albumIDs: have.map(\.id), albumTitle: album.displayName,
                                                                     artistName: album.artistName))
            } else {
                fetchTracks(discs: discs) { [weak self] tracks in
                    guard let self, let tracks else { return completion(nil) }
                    let sources = tracks.map { QueueSource(track: $0, album: album) }
                    next(more, items + self.items(sources: sources, order: Array(sources.indices)))
                }
            }
        }
        next(ArraySlice(ArtistTracks.albumOrder(albums)), [])
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

    /// A track ready to queue: what the saved queue keeps, what the player
    /// shows and plays, and its place in its album or playlist.
    struct QueueItem {
        var queueTrack: QueueTrack
        var playback: PlaybackTrack
        var sourcePosition: Int
    }

    /// Where `place` puts things.
    enum Placement {
        case replace(startIndex: Int?)
        case playNext
        case addToQueue
    }

    static func group(for album: JellyfinAlbum) -> QueueGroup {
        QueueGroup(id: album.id, kind: .album, title: album.displayName,
                   artist: album.artistName.isEmpty ? nil : album.artistName, artItemId: album.id)
    }

    static func group(for playlist: JellyfinPlaylist) -> QueueGroup {
        QueueGroup(id: playlist.id, kind: .playlist, title: playlist.name, artItemId: playlist.id)
    }

    nonisolated static func allDownloaded(_ discs: [JellyfinAlbum]) -> Bool {
        !discs.isEmpty && discs.allSatisfy {
            DownloadPaths.exists(albumID: $0.id) && DownloadPaths.fileCount(albumID: $0.id) > 0
        }
    }

    /// The tracks of every disc, in disc order (nil when none could be fetched).
    private func fetchTracks(discs: [JellyfinAlbum], completion: @escaping ([JellyfinTrack]?) -> Void) {
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
        group.notify(queue: .main) {
            let found = perDisc.compactMap { $0 }
            completion(found.isEmpty ? nil : found.flatMap { $0 })
        }
    }

    /// Replaces the queue with an album's tracks and plays (see `place`).
    private func startQueue(
        tracks: [JellyfinTrack],
        album: JellyfinAlbum,
        forcedStartIndex: Int?,
        format: AudioFormat
    ) {
        let sources = tracks.map { QueueSource(track: $0, album: album) }
        place(items(sources: sources, order: Array(sources.indices)), group: Self.group(for: album),
              how: .replace(startIndex: forcedStartIndex))
    }

    /// The queue's tracks for `sources` played in `order` (places in
    /// `sources`): each with its own album's name, ID and art.
    func playbackTracks(sources: [QueueSource], order: [Int]) -> [PlaybackTrack] {
        items(sources: sources, order: order).map(\.playback)
    }

    /// Queue items for `sources` taken in `order` (places in `sources`).
    func items(sources: [QueueSource], order: [Int]) -> [QueueItem] {
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
            var qt = QueueTrack(track: source.track, album: source.album)
            qt.trackNumber = pt.trackNumber
            // On mobile data the stream is AAC at the cap, whatever the file is;
            // a source AVPlayer can't play is converted.
            if source.localURL == nil {
                pt.describeStream(codec: qt.codec, container: qt.container, mobileCap: cap)
            }
            return QueueItem(queueTrack: qt, playback: pt, sourcePosition: place)
        }
    }

    /// Puts `items` (from `group`) in the queue and has the AVQueuePlayer
    /// follow: a replace plays from its start; Play Next and Add to Queue
    /// only change what follows the playing track (or play, into an empty
    /// queue). Returns whether anything was queued.
    @discardableResult
    private func place(_ items: [QueueItem], group: QueueGroup, how: Placement) -> Bool {
        start() // idempotent
        var added: [String] = []
        let register: ([QueueEntry]) -> Void = { [self] entries in
            added = entries.map(\.id)
            for (entry, item) in zip(entries, items) {
                media[entry.id] = item.playback
                sourcePositions[entry.id] = item.sourcePosition
            }
        }
        let tracks = items.map(\.queueTrack)
        let transition: QueueTransition
        switch how {
        case .replace(let startIndex):
            reportStopped(atEnd: false)
            transition = controller.replace(group: group, tracks: tracks, startIndex: startIndex,
                                            play: true, register: register)
        case .playNext:
            guard !items.isEmpty, !refusesPlayNext else { return false }
            transition = controller.playNext(group: group, tracks: tracks, register: register)
        case .addToQueue:
            guard !items.isEmpty else { return false }
            transition = controller.addToQueue(group: group, tracks: tracks, register: register)
        }
        apply(transition)
        // Downloaded tracks show the format and length of their files.
        loadLocalDetails(entryIds: added)
        let localAlbums = Array(Set(items.compactMap { item -> String? in
            Self.isLocalOnly(item.playback.id) ? item.playback.albumId : nil
        }))
        if !localAlbums.isEmpty {
            loadMissingLocalTitles(albumIDs: localAlbums)
            loadMissingTrackCovers(albumIDs: localAlbums)
        }
        return !items.isEmpty
    }

    // MARK: - Following the queue

    /// Brings the published state in line with the queue and the player
    /// after a queue operation.
    private func apply(_ transition: QueueTransition) {
        switch transition {
        case .none:
            return
        case .upcomingChanged:
            syncFromQueue()
        case .started(let play):
            reportStopped(atEnd: false)
            pendingStartSeek = nil
            syncFromQueue()
            duration = currentTrack?.duration ?? 0
            position = 0
            lastError = nil
            isLoading = false
            updateNowPlayingForCurrent()
            if play {
                reportStarted()
                startedAt = now()
                isPlaying = true
            } else {
                isPlaying = false
                nowPlaying.updateState(isPlaying: false, elapsedTime: 0)
            }
        case .advanced:
            syncFromQueue()
            duration = currentTrack?.duration ?? 0
            position = 0
            updateNowPlayingForCurrent()
            reportStarted()
            startRetries = 0
            watchStart()
        case .ended:
            reportStopped(atEnd: false)
            syncFromQueue()
            duration = currentTrack?.duration ?? 0
            position = 0
            isPlaying = false
            updateNowPlayingForCurrent()
            nowPlaying.updateState(isPlaying: false, elapsedTime: 0)
        case .emptied:
            reportStopped(atEnd: false)
            player.pause()
            clearState()
            isLoading = false
        }
    }

    /// Re-derives `queue`, `currentIndex` and `currentTrack` from the queue.
    private func syncFromQueue() {
        let order = controller.queue.entriesInPlayOrder
        let ids = Set(order.map(\.id))
        media = media.filter { ids.contains($0.key) }
        sourcePositions = sourcePositions.filter { ids.contains($0.key) }
        let tracks = order.map { media[$0.id] ?? restoredTrack($0) }
        if tracks != queue { queue = tracks }
        queueEntryIds = order.map(\.id)
        currentIndex = controller.queue.currentPlayIndex ?? 0
        let current = tracks.indices.contains(currentIndex) ? tracks[currentIndex] : nil
        if current != currentTrack { currentTrack = current }
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

    /// The tracks that will play after the current one, in order, with
    /// their entry ids. Wraps to the start when Repeat All is on.
    public var upNext: [(entryId: String, track: PlaybackTrack)] {
        controller.queue.upcomingEntries.map { ($0.id, media[$0.id] ?? restoredTrack($0)) }
    }

    // MARK: - Queue editing

    /// Moves entries (one, or a whole album's) to `toIndex` in the queue as
    /// shown (see `PlayQueue.move`). Refused in Maniac Mode.
    public func moveQueue(entryIds: [String], toIndex: Int) {
        apply(controller.move(entryIds: entryIds, toIndex: toIndex))
    }

    /// Removes entries. Removing the playing one plays the next (or stops
    /// at the end). Refused in Maniac Mode.
    public func removeFromQueue(entryIds: [String]) {
        apply(controller.remove(entryIds: entryIds, playing: isPlaying))
    }

    /// Removes everything but the playing track. Refused in Maniac Mode.
    public func clearUpcoming() {
        apply(controller.clearUpcoming())
    }

    /// Plays the queue entry `entryId` (e.g. tapped in "Up next"). Refused
    /// in Maniac Mode.
    public func skipTo(entryId: String) {
        apply(controller.skipTo(entryId: entryId))
    }

    /// Sets the player volume from the current track's `NormalizationGain`
    /// when "Normalise volume" is on (full volume otherwise, or when the
    /// track has no gain — e.g. downloaded files). Call after changing the setting.
    public func applyNormalization() {
        let gain = PlaybackSettings.normalizeVolume ? currentTrack?.normalizationGain : nil
        player.volume = PlaybackSettings.volume(forGain: gain)
    }

    /// The player item for a queue entry: its file when it's here, else the
    /// server's stream (nil for a downloaded album's track whose file has gone).
    private func makePlayerItem(entryId: String) -> AVPlayerItem? {
        guard let entry = controller.queue.entry(id: entryId) else { return nil }
        // A file that has gone since the queue was built is streamed.
        if let file = media[entryId]?.localURL, FileManager.default.fileExists(atPath: file.path) {
            return AVPlayerItem(url: file)
        }
        guard !Self.isLocalOnly(entry.track.id) else { return nil }
        // On cellular / a hotspot, stream AAC at the chosen bitrate instead
        // of the original file.
        let cap = NetworkMonitor.shared.isExpensive ? PlaybackSettings.mobileDataBitrate : nil
        guard let url = stream.url(trackId: entry.track.id, container: entry.track.container,
                                   codec: entry.track.codec, maxBitrate: cap) else {
            print("[PlayerManager] StreamURLBuilder returned nil for track \(entry.track.id)")
            return nil
        }
        // Gapless: AVQueuePlayer natively pre-loads the next item; the
        // periodic time observer additionally asks the next item's asset
        // to start loading ~1s early (see `preWarmNextItem`).
        return AVPlayerItem(url: url)
    }

    public func pause() {
        startWatch?.cancel()
        player.pause()
        isPlaying = false
        nowPlaying.updateState(isPlaying: false, elapsedTime: position)
        controller.scheduleSave()
    }

    public func resume() {
        guard currentTrack != nil else { return }
        // After a failed track, Play tries that track again.
        if lastError == .trackFailed {
            lastError = nil
            apply(controller.reloadCurrent(play: true))
            return
        }
        // After the album finished and went back to the start (or a
        // restored queue), this play is a new one.
        if reportedTrack == nil { reportStarted() }
        startedAt = now()
        if let deferred = deferredLoad {
            // Held paused (a restored queue): its items are made now, at the point.
            startLoad(entryIds: deferred.entryIds, position: deferred.position, play: true)
        } else {
            // An item still on its way to its point starts once it's there (`itemReady`).
            if pendingStartSeek?.play != true { player.play() }
            watchStart()
        }
        isPlaying = true
        nowPlaying.updateState(isPlaying: true, elapsedTime: position)
    }

    /// Play or Pause. Nothing in the first seconds of a track's start
    /// (`ignoresPlayPause`); later in a start, or once it has been retried,
    /// it pauses (ending the retries; Play then carries on from the point).
    public func togglePlayPause() {
        if ignoresPlayPause { return }
        if isPlaying { pause() } else { resume() }
    }

    public func skipToNext() {
        guard !maniacMode else { return }
        apply(controller.next())
    }

    public func skipToPrevious() {
        guard !maniacMode, !queue.isEmpty else { return }
        // Skip to start of current track if we've already played > 3s,
        // otherwise step back.
        if position > 3 {
            if deferredLoad != nil { deferredLoad?.position = 0 } else { player.seek(to: .zero) { _ in } }
            position = 0
            widgetChanged()
            return
        }
        apply(controller.previous())
    }

    public func seek(toTime t: TimeInterval) {
        guard !maniacMode else { return }
        let clamped = max(0, min(t, duration > 0 ? duration : t))
        startedAt = now()
        if deferredLoad != nil {
            // Nothing opened yet: Play opens it there.
            deferredLoad?.position = clamped
        } else {
            guard let item = player.currentItem else { return }
            if item.status == .readyToPlay {
                item.seek(to: CMTime(seconds: clamped, preferredTimescale: CMTimeScale(1000))) { _ in }
            } else {
                // An item can't seek until it's ready; it does once it is (and starts then, if it was to).
                let id = ObjectIdentifier(item)
                let play = pendingStartSeek?.item == id && pendingStartSeek?.play == true
                pendingStartSeek = (id, clamped, play)
            }
        }
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
        if deferredLoad != nil {
            deferredLoad?.position = 0
            position = 0
            resume()
            return
        }
        player.seek(to: .zero) { _ in }
        position = 0
        player.play()
        widgetChanged()
    }

    public func setRepeatMode(_ mode: RepeatMode) {
        apply(controller.setRepeatMode(mode))
    }

    /// Cycle through the three repeat modes on every tap. UI helper.
    public func cycleRepeatMode() {
        guard !maniacMode else { return }
        switch repeatMode {
        case .off: setRepeatMode(.all)
        case .all: setRepeatMode(.one)
        case .one: setRepeatMode(.off)
        }
    }

    public func toggleShuffle() {
        setShuffle(!shuffle)
    }

    /// Shuffle across the whole queue (the playing track carries on and the
    /// rest is shuffled after it); off puts the queue back in order. A
    /// queue started afterwards plays shuffled too. Only off in Maniac Mode.
    public func setShuffle(_ newValue: Bool) {
        apply(controller.setShuffle(newValue))
    }

    // MARK: - Maniac Mode

    /// Turns Maniac Mode on or off and saves it. Turning it on switches
    /// shuffle and repeat off and puts the rest of a shuffled queue back in
    /// order (the playing track carries on).
    public func setManiacMode(_ on: Bool) {
        PlaybackSettings.maniacMode = on
        guard on != maniacMode else { return }
        maniacMode = on
        controller.setManiacMode(on)
        syncFromQueue()
        nowPlaying.setSkipControlsEnabled(!on)
        NotificationCenter.default.post(name: Self.maniacModeDidChange, object: self)
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
                // A restored track waits for Play or for its seek; keep its position shown.
                guard self.pendingStartSeek == nil, self.deferredLoad == nil else { return }
                let secs = time.seconds
                self.position = secs
                if self.duration <= 0,
                   let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 {
                    self.duration = d
                }
                // Save the position now and then while playing.
                if self.isPlaying, Date().timeIntervalSince(self.lastPeriodicSave) >= 10 {
                    self.lastPeriodicSave = Date()
                    self.controller.scheduleSave()
                }
                // Gapless optimisation: ~1s before this item ends, start
                // loading the next item's asset (see `preWarmNextItem`).
                if shouldPreloadNext(position: secs, duration: self.duration) {
                    self.preWarmNextItem()
                }
            }
        }
    }

    /// Where the playing entry's item is among `enqueuedItems`.
    private var currentEnqueuedIndex: Int? {
        guard let current = controller.queue.currentEntryId else { return nil }
        return enqueuedEntryIds.firstIndex(of: current)
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
        guard let current = currentEnqueuedIndex else { return }
        let nextItemIndex = current + 1
        guard nextItemIndex < enqueuedItems.count else { return }
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

// MARK: - The AVQueuePlayer as the queue's engine

extension PlayerManager: QueueEngine {

    /// Replaces the AVQueuePlayer's items with `entryIds`' (the first plays
    /// now), from `position` into the first.
    ///
    /// Paused, nothing is made yet (`deferredLoad`): no player item, so no
    /// connection to the server, until Play. A restored queue at launch is
    /// loaded this way, as the route to the server may not be up yet.
    public func load(entryIds: [String], position: Double, play: Bool) {
        startRetries = 0
        startLoad(entryIds: entryIds, position: position, play: play)
    }

    private func startLoad(entryIds: [String], position: Double, play: Bool) {
        player.pause()
        removeAllItems()
        pendingStartSeek = nil
        startWatch?.cancel()
        deferredLoad = nil
        guard play else {
            if !entryIds.isEmpty { deferredLoad = (entryIds, position) }
            return
        }
        append(entryIds, after: nil)
        if position > 0, let first = enqueuedItems.first {
            // Starts once it's at the point (an item can't seek before it's ready).
            pendingStartSeek = (ObjectIdentifier(first), position, true)
        } else {
            player.play()
        }
        watchStart()
    }

    /// `isStarting` from the player's state.
    private func updateStarting() {
        let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        let starting = isPlaying && (waiting || pendingStartSeek?.play == true)
        if starting != isStarting { isStarting = starting }
    }

    /// The first response a stream gets before it's asked for again: a
    /// stream the server converts (HLS from `/universal`) only. AVPlayer
    /// itself waits for the system's request timeout (about a minute) and
    /// then fails the item; just after launch the route to the server may
    /// not carry the very first request. Direct streams and files: nil.
    nonisolated static func firstResponseLimit(for url: URL?) -> TimeInterval? {
        guard let url, !url.isFileURL, url.path.hasSuffix("/universal") else { return nil }
        return firstResponseSeconds
    }
    nonisolated static let firstResponseSeconds: TimeInterval = 8
    /// After this many tries AVPlayer is left to it (a slow server, not a dead request).
    nonisolated static let maxStartRetries = 3

    /// A converted stream that has had no answer at all `firstResponseLimit`
    /// after Play is made again from the same point, as Android's reconnect
    /// does.
    private func watchStart() {
        startWatch?.cancel()
        guard let item = player.currentItem, item.status != .readyToPlay,
              let entryId = playQueue.currentEntryId,
              let limit = Self.firstResponseLimit(for: (item.asset as? AVURLAsset)?.url) else { return }
        startWatch = Task { @MainActor [weak self, weak item] in
            try? await Task.sleep(nanoseconds: UInt64(limit * 1_000_000_000))
            guard !Task.isCancelled, let self, let item else { return }
            self.startTimedOut(item, entryId: entryId)
        }
    }

    private func startTimedOut(_ item: AVPlayerItem, entryId: String) {
        // Ready, answered (it's only slow), paused, moved on, or tried enough: left alone.
        let answered = !(item.accessLog()?.events.isEmpty ?? true)
        guard player.currentItem === item, item.status != .readyToPlay, !answered, isPlaying,
              playQueue.currentEntryId == entryId, startRetries < Self.maxStartRetries,
              let index = currentEnqueuedIndex else { return }
        startRetries += 1
        print("[PlayerManager] no answer from the converted stream in \(Int(Self.firstResponseSeconds)) s: trying again")
        let at = pendingStartSeek.flatMap { $0.item == ObjectIdentifier(item) ? $0.seconds : nil } ?? position
        startLoad(entryIds: Array(enqueuedEntryIds[index...]), position: at, play: true)
    }

    /// Keeps the playing item (and any items after it that stay the same,
    /// so the next track's preloading isn't lost) and replaces the rest.
    public func replaceAfterCurrent(_ entryIds: [String]) {
        if let deferred = deferredLoad, let first = deferred.entryIds.first {
            deferredLoad = ([first] + entryIds, deferred.position)
            return
        }
        guard let current = currentEnqueuedIndex else { return }
        let tail = Array(enqueuedEntryIds[(current + 1)...])
        var keep = 0
        while keep < tail.count, keep < entryIds.count, tail[keep] == entryIds[keep] { keep += 1 }
        let cut = current + 1 + keep
        if cut < enqueuedItems.count {
            for item in enqueuedItems[cut...] {
                player.remove(item)
                removeItemEndObserver(for: item)
            }
            enqueuedItems.removeSubrange(cut...)
            enqueuedEntryIds.removeSubrange(cut...)
        }
        append(Array(entryIds[keep...]), after: enqueuedItems.last)
    }

    /// Adds items for `entryIds` to the end of the AVQueuePlayer's queue.
    /// `AVQueuePlayer.insert(_:after:)` is the cross-platform API (iOS /
    /// macOS); the `AVPlayerItemInsertionPoint` variant is iOS-only.
    private func append(_ entryIds: [String], after last: AVPlayerItem?) {
        var lastAdded = last
        for id in entryIds {
            guard let item = makePlayerItem(entryId: id) else { continue }
            AudioEffects.shared.attach(to: item)  // equaliser + visualiser
            installItemEndObserver(for: item)
            player.insert(item, after: lastAdded)
            enqueuedItems.append(item)
            enqueuedEntryIds.append(id)
            lastAdded = item
        }
    }

    private func removeAllItems() {
        for item in enqueuedItems {
            player.remove(item)
            removeItemEndObserver(for: item)
        }
        enqueuedItems.removeAll(keepingCapacity: true)
        enqueuedEntryIds.removeAll(keepingCapacity: true)
    }

    /// Forgets items the AVQueuePlayer has finished with (up to, not
    /// including, `index`).
    fileprivate func dropPlayedItems(before index: Int) {
        guard index > 0, index <= enqueuedItems.count else { return }
        for item in enqueuedItems[..<index] { removeItemEndObserver(for: item) }
        enqueuedItems.removeSubrange(..<index)
        enqueuedEntryIds.removeSubrange(..<index)
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
        ) { [weak self, weak item] _ in
            Task { @MainActor in
                if let self, let item { self.handleItemDidPlayToEndTime(item) }
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
            switch item.status {
            case .failed:
                Task { @MainActor [weak self] in self?.handleItemFailed(item) }
            case .readyToPlay:
                Task { @MainActor [weak self] in self?.itemReady(item) }
            default:
                break
            }
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
        guard let index = currentEnqueuedIndex, enqueuedItems[index] === item else { return }
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

    /// A restored queue's track (or a seek made while loading) goes to its
    /// position once its item can seek.
    func itemReady(_ item: AVPlayerItem) {
        if player.currentItem === item {
            startWatch?.cancel()
            startRetries = 0
        }
        guard let pending = pendingStartSeek, pending.item == ObjectIdentifier(item) else { return }
        item.seek(to: CMTime(seconds: pending.seconds, preferredTimescale: CMTimeScale(1000))) { [weak self] _ in
            Task { @MainActor in
                guard let self, let now = self.pendingStartSeek, now.item == ObjectIdentifier(item) else { return }
                self.pendingStartSeek = nil
                // A restored queue's first Play starts here, at its point (unless paused meanwhile).
                if now.play, self.isPlaying, self.player.currentItem === item { self.player.play() }
            }
        }
    }

    /// The playing item played to its end. The AVQueuePlayer has already
    /// moved on to the next item it holds; the queue decides whether that's
    /// right (it is unless Repeat One, Repeat All going round, or an entry
    /// that can't play now).
    func handleItemDidPlayToEndTime(_ item: AVPlayerItem) {
        guard let index = enqueuedItems.firstIndex(where: { $0 === item }),
              enqueuedEntryIds[index] == playQueue.currentEntryId else { return }
        reportStopped(atEnd: true)
        let engineNext = enqueuedEntryIds.indices.contains(index + 1) ? enqueuedEntryIds[index + 1] : nil
        let transition = controller.trackEnded(engineNext: engineNext)
        if transition == .advanced { dropPlayedItems(before: index + 1) }
        if transition == .ended {
            finishQueue()
        } else {
            apply(transition)
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
        guard !playQueue.isEmpty else {
            pause()
            return
        }
        if playQueue.currentEntryId != playQueue.playOrder.first || enqueuedItems.isEmpty {
            _ = controller.finish()
        }
        apply(.ended)
    }
}

// MARK: - Small helpers on PlaybackTrack

extension PlayerManager {
    /// The artist Now Playing's artist line opens for `track`: its album
    /// artist, looked up by name when only that is known (online only).
    /// Nil: the line isn't tappable (`ArtistLink`).
    public func albumArtistLink(for track: PlaybackTrack) async -> JellyfinArtist? {
        let api = self.api
        return await ArtistLink.find(name: track.artist, id: track.albumArtistId,
                                     online: NetworkMonitor.shared.isConnected) { await api.albumArtists(matching: $0) }
    }
}

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
        copy.albumArtistId = albumArtistId
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
        copy.albumArtistId = albumArtistId
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
        copy.albumArtistId = albumArtistId
        copy.localArtURL = localArtURL
        return copy
    }
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