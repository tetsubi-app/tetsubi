// MARK: - PlaylistDownloads — downloaded playlists: files, manifest, keeping in step

import Foundation
import Observation

// MARK: - Manifest

/// `playlist.json`: everything the playlist page needs offline. The order
/// is the playlist's, with a song that's there twice listed twice; its
/// file is stored once.
public struct PlaylistManifest: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    /// The server has its own picture for the playlist.
    public var hasArt: Bool
    public var dateCreated: Date?
    public var entries: [Entry]
    /// The download was paused by the user.
    public var paused: Bool?
    /// Why the download failed, until it's retried.
    public var failure: String?
    /// When every song was first on this device. From then on the
    /// playlist is listed offline, also while songs are fetched again.
    /// Optional so manifests written before this field still read.
    public var downloadedAt: Date?

    public struct Entry: Codable, Equatable, Sendable {
        public var trackId: String
        public var title: String
        public var artist: String
        public var album: String
        public var albumId: String?
        public var durationTicks: Int64
        /// The tag's number; 0 when the song has none.
        public var trackNumber: Int
        public var discNumber: Int?
        /// The item whose picture the row shows (the track or its album).
        public var artItemId: String
        /// The song's file name in the playlist's folder: "<track ID>.<ext>".
        /// It isn't there when a downloaded album's file is used instead.
        public var file: String
        public var normalizationGain: Double?

        public init(trackId: String, title: String, artist: String, album: String, albumId: String?,
                    durationTicks: Int64, trackNumber: Int, discNumber: Int?, artItemId: String,
                    file: String, normalizationGain: Double? = nil) {
            self.trackId = trackId
            self.title = title
            self.artist = artist
            self.album = album
            self.albumId = albumId
            self.durationTicks = durationTicks
            self.trackNumber = trackNumber
            self.discNumber = discNumber
            self.artItemId = artItemId
            self.file = file
            self.normalizationGain = normalizationGain
        }

        /// A playlist row as it's saved; `format` decides the file's extension.
        public init(_ entry: PlaylistEntry, format: AudioFormat) {
            self.init(trackId: entry.track.id, title: entry.track.displayName, artist: entry.albumArtist,
                      album: entry.albumName, albumId: entry.albumId,
                      durationTicks: entry.track.runTimeTicks ?? 0,
                      trackNumber: entry.track.trackNumber, discNumber: entry.track.discNumber,
                      artItemId: entry.artItemId,
                      file: PlaylistDownloadPaths.fileName(trackID: entry.track.id, format: format),
                      normalizationGain: entry.track.normalizationGain)
        }

        /// The number the song's file has in its album's download.
        var fileNumber: Int {
            guard let disc = discNumber, disc > 1 else { return trackNumber }
            return disc * 100 + trackNumber
        }

        /// The row again, for the page and the queue.
        public func playlistEntry(position: Int) -> PlaylistEntry {
            var track = JellyfinTrack(
                id: trackId, name: title, serverId: nil,
                indexNumber: trackNumber > 0 ? trackNumber : nil,
                runTimeTicks: durationTicks, album: album, albumId: albumId,
                albumArtists: artist.isEmpty ? nil : [JellyfinPerson(id: nil, name: artist, serverId: nil)],
                imageTags: nil, mediaSource: nil)
            track.discNumber = discNumber
            track.normalizationGain = normalizationGain
            track.hasOwnArt = albumId != nil && artItemId == trackId
            return PlaylistEntry(position: position, entryId: trackId, track: track,
                                 albumName: album, albumId: albumId, albumArtist: artist)
        }
    }

    public init(id: String, name: String, hasArt: Bool = false, dateCreated: Date? = nil,
                entries: [Entry], paused: Bool? = nil, failure: String? = nil, downloadedAt: Date? = nil) {
        self.id = id
        self.name = name
        self.hasArt = hasArt
        self.dateCreated = dateCreated
        self.entries = entries
        self.paused = paused
        self.failure = failure
        self.downloadedAt = downloadedAt
    }

    /// The playlist's length in seconds, counted from the rows.
    public var durationSeconds: Double {
        Double(entries.reduce(0) { $0 + $1.durationTicks }) / 10_000_000
    }

    /// The server's listing no longer matches what's saved: another
    /// name, song count, or a length more than 2 seconds apart.
    public func differs(from listed: JellyfinPlaylist) -> Bool {
        listed.name != name || listed.songCount != entries.count
            || abs(Double(listed.runTimeTicks) / 10_000_000 - durationSeconds) > 2
    }

    /// The playlist as lists show it, counted from the manifest.
    public var playlist: JellyfinPlaylist {
        JellyfinPlaylist(id: id, name: name, songCount: entries.count,
                         runTimeTicks: entries.reduce(0) { $0 + $1.durationTicks },
                         dateCreated: dateCreated, hasArt: hasArt)
    }

    /// The rows, by position.
    public var playlistEntries: [PlaylistEntry] {
        entries.enumerated().map { $1.playlistEntry(position: $0) }
    }

    /// Each song once, in playlist order.
    public var distinctEntries: [Entry] {
        var seen = Set<String>()
        return entries.filter { seen.insert($0.trackId).inserted }
    }
}

// MARK: - Paths

/// `Downloads/playlists/<playlist ID>/`: `playlist.json`, the songs as
/// `<track ID>.<ext>`, `cover.jpg` (the playlist's picture) and
/// `covers/<item ID>.jpg` (the rows' pictures).
public enum PlaylistDownloadPaths {
    /// The folder beside the album folders. Album IDs are never this.
    static let folderName = "playlists"
    static let manifestName = "playlist.json"
    static let coverName = "cover.jpg"
    static let coversName = "covers"

    static var rootDir: URL {
        DownloadPaths.rootDir.appendingPathComponent(folderName, isDirectory: true)
    }

    public static func dir(_ playlistID: String) -> URL {
        rootDir.appendingPathComponent(DownloadPaths.sanitize(playlistID), isDirectory: true)
    }

    static func manifestURL(_ playlistID: String) -> URL {
        dir(playlistID).appendingPathComponent(manifestName)
    }

    static func fileName(trackID: String, format: AudioFormat) -> String {
        "\(DownloadPaths.sanitize(trackID)).\(format.fileExtension)"
    }

    public static func trackURL(playlistID: String, trackID: String, format: AudioFormat) -> URL {
        dir(playlistID).appendingPathComponent(fileName(trackID: trackID, format: format))
    }

    // MARK: Pictures

    static func coverURL(_ playlistID: String) -> URL {
        dir(playlistID).appendingPathComponent(coverName)
    }

    /// The playlist's saved picture, if there is one.
    public static func savedCover(playlistID: String) -> URL? {
        let url = coverURL(playlistID)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    static func coversDir(_ playlistID: String) -> URL {
        dir(playlistID).appendingPathComponent(coversName, isDirectory: true)
    }

    static func artURL(playlistID: String, itemID: String) -> URL {
        coversDir(playlistID).appendingPathComponent("\(DownloadPaths.sanitize(itemID)).jpg")
    }

    /// A row's saved picture, if there is one.
    public static func savedArt(playlistID: String, itemID: String) -> URL? {
        let url = artURL(playlistID: playlistID, itemID: itemID)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: Manifest

    /// IDs of the playlists that have a manifest.
    public static func downloadedIDs() -> [String] {
        let dirs = (try? FileManager.default.contentsOfDirectory(
            at: rootDir, includingPropertiesForKeys: nil, options: .skipsHiddenFiles)) ?? []
        return dirs.compactMap { loadManifest(at: $0.appendingPathComponent(manifestName))?.id }
    }

    /// The playlists listed offline (each finished downloading at least
    /// once), read from disk: for places that can't wait for the
    /// download manager (Search, CarPlay, Siri).
    public static func offlinePlaylists() -> [JellyfinPlaylist] {
        downloadedIDs().compactMap { loadManifest($0) }.filter { $0.downloadedAt != nil }.map(\.playlist)
    }

    public static func loadManifest(_ playlistID: String) -> PlaylistManifest? {
        loadManifest(at: manifestURL(playlistID))
    }

    private static func loadManifest(at url: URL) -> PlaylistManifest? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(PlaylistManifest.self, from: data)
    }

    public static func save(_ manifest: PlaylistManifest) throws {
        try FileManager.default.createDirectory(at: dir(manifest.id), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(manifest).write(to: manifestURL(manifest.id), options: .atomic)
    }

    // MARK: Files

    /// The songs in the playlist's own folder, by track ID.
    public static func trackFiles(_ playlistID: String) -> [String: URL] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: dir(playlistID), includingPropertiesForKeys: [.isDirectoryKey],
            options: .skipsHiddenFiles)) ?? []
        var out: [String: URL] = [:]
        for url in files {
            let name = url.lastPathComponent
            guard name != manifestName, name != coverName,
                  (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) != true else { continue }
            out[url.deletingPathExtension().lastPathComponent] = url
        }
        return out
    }

    /// Bytes in the playlist's own folder. Songs played from an album's
    /// download are counted with that album, not here.
    public static func size(_ playlistID: String) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: dir(playlistID), includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }

    public static func delete(_ playlistID: String) {
        try? FileManager.default.removeItem(at: dir(playlistID))
    }
}

// MARK: - Where a song's file is

/// Finds a playlist's songs on this device: in the playlist's own folder
/// first, then in a downloaded album that has the song. Folder listings
/// are read once, so make a new one after files change.
public final class PlaylistFiles {
    private let playlistID: String
    private lazy var own: [String: URL] = PlaylistDownloadPaths.trackFiles(playlistID)
    private var albums: [String: [URL]] = [:]

    public init(playlistID: String) {
        self.playlistID = playlistID
    }

    /// The song's file in the playlist's own folder.
    public func ownFile(trackID: String) -> URL? {
        own[DownloadPaths.sanitize(trackID)]
    }

    /// The song's file in its album's download: the file with the song's
    /// title, and, when the album has that title more than once, the one
    /// with the song's download number. Songs without numbers are found
    /// by title alone.
    public func albumFile(albumID: String?, title: String, fileNumber: Int) -> URL? {
        guard let albumID, !albumID.isEmpty else { return nil }
        let files = albumFiles(albumID)
        guard !files.isEmpty else { return nil }
        let wanted = DownloadPaths.sanitize(title)
        let named = files.filter { DownloadedTitles.fileTitle($0.lastPathComponent) == wanted }
        if named.count == 1 { return named[0] }
        return named.first { DownloadPaths.parseTrackNumber(from: $0.lastPathComponent) == fileNumber }
    }

    private func albumFiles(_ albumID: String) -> [URL] {
        if let known = albums[albumID] { return known }
        let found = ((try? FileManager.default.contentsOfDirectory(
            at: DownloadPaths.albumDir(albumID), includingPropertiesForKeys: [.isDirectoryKey],
            options: .skipsHiddenFiles)) ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) != true }
        albums[albumID] = found
        return found
    }

    /// Own folder, then a downloaded album; nil means the song is streamed.
    public func file(trackID: String, albumID: String?, title: String, fileNumber: Int) -> URL? {
        ownFile(trackID: trackID) ?? albumFile(albumID: albumID, title: title, fileNumber: fileNumber)
    }

    public func file(for entry: PlaylistManifest.Entry) -> URL? {
        file(trackID: entry.trackId, albumID: entry.albumId, title: entry.title, fileNumber: entry.fileNumber)
    }

    public func file(for entry: PlaylistEntry) -> URL? {
        file(trackID: entry.track.id, albumID: entry.albumId,
             title: entry.track.displayName, fileNumber: entry.track.fileNumber)
    }
}

// MARK: - State

/// A playlist whose download isn't complete, for the page and the
/// Downloads tab.
public struct PlaylistDownloadState: Sendable, Equatable {
    public let playlistID: String
    public var name: String
    /// Different songs in the playlist.
    public var expectedTrackCount: Int
    /// Of those, the ones with a file on this device.
    public var completedTracks: Int
    /// `.downloading`, `.paused` or `.failed`.
    public var status: DownloadStatus
    public var errorMessage: String?

    public var progress: Double {
        expectedTrackCount > 0 ? min(1, Double(completedTracks) / Double(expectedTrackCount)) : 0
    }
}

/// A running download of one song; the session's task, or a stand-in in tests.
protocol PlaylistDownloadTask: AnyObject {
    func cancel()
}

extension URLSessionDownloadTask: PlaylistDownloadTask {}

// MARK: - Engine

/// Downloads playlists and keeps them in step with the server. Owned by
/// `DownloadManager`, which passes on the session's events for tasks that
/// carry a playlist ID.
@MainActor
@Observable
public final class PlaylistDownloads {

    /// What the engine asks of the outside; tests supply their own.
    struct Backend {
        var isOnline: @MainActor () -> Bool
        var playlists: (@escaping (Result<[JellyfinPlaylist], JellyfinError>) -> Void) -> Void
        var entries: (String, @escaping (Result<[PlaylistEntry], JellyfinError>) -> Void) -> Void
        /// The format a song is downloaded in.
        var format: (JellyfinTrack) -> AudioFormat
        var url: (JellyfinTrack, AudioFormat) -> URL?
        var start: (DownloadTaskInfo, URL) -> PlaylistDownloadTask
        /// A picture's bytes (item ID, size in px); nil when it can't be had.
        var image: @Sendable (String, Int) async -> Data?
    }

    // MARK: Published state

    /// The manifest of every playlist with a download, by ID.
    public private(set) var manifests: [String: PlaylistManifest] = [:]
    /// Playlists still downloading, paused or failed.
    public private(set) var active: [String: PlaylistDownloadState] = [:]
    /// Playlists whose every song has a file on this device.
    public private(set) var downloadedIDs: Set<String> = []
    /// Seconds of music in each fully downloaded playlist's own folder
    /// (each song once; songs played from an album's download aren't in it).
    public private(set) var ownSeconds: [String: TimeInterval] = [:]
    /// Moves when a saved picture changed.
    public private(set) var artVersion = 0

    // MARK: Bookkeeping

    @ObservationIgnored private let backend: Backend
    /// Playlist ID → track ID → running task.
    @ObservationIgnored private var tasks: [String: [String: PlaylistDownloadTask]] = [:]
    /// Moves with every change to a playlist, so an older answer from the
    /// server is dropped.
    @ObservationIgnored private var generation: [String: Int] = [:]
    @ObservationIgnored private var artTasks: [String: Task<Void, Never>] = [:]

    static let coverSize = 600

    // MARK: Init

    init(backend: Backend) {
        self.backend = backend
        refresh()
    }

    convenience init(api: JellyfinAPI, stream: StreamURLBuilder, session: @escaping () -> URLSession,
                     preferredFormat: @escaping () -> AudioFormat?) {
        self.init(backend: Backend(
            isOnline: { NetworkMonitor.shared.isConnected },
            playlists: { api.playlists(completion: $0) },
            entries: { api.playlistTracks($0, completion: $1) },
            format: { AudioFormat.downloadFormat(for: $0, preferred: preferredFormat()) },
            url: { stream.url(for: $0, format: $1) },
            start: { info, url in
                var request = URLRequest(url: url)
                // "Download over cellular" off → the session waits for Wi‑Fi.
                request.allowsExpensiveNetworkAccess = PlaybackSettings.downloadOverCellular
                let task = session().downloadTask(with: request)
                task.taskDescription = info.encoded
                task.resume()
                return task
            },
            image: { itemID, size in
                guard let request = api.imageURLRequest(for: itemID, size: size),
                      let (data, response) = try? await URLSession.shared.data(for: request),
                      (response as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty else { return nil }
                return data
            }))
        watchConnection()
        watchPlaylistEvents()
    }

    /// Catches up when a playlist was changed in the app (songs added
    /// from an album's menu, say), whichever page is open.
    private func watchPlaylistEvents() {
        withObservationTracking {
            _ = PlaylistEvents.shared.version
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.syncAll()
                self.watchPlaylistEvents()
            }
        }
    }

    /// Catches up when the connection comes back.
    private func watchConnection() {
        withObservationTracking {
            _ = NetworkMonitor.shared.isConnected
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if NetworkMonitor.shared.isConnected { self.syncAll() }
                self.watchConnection()
            }
        }
    }

    // MARK: - Reading

    /// Reads every manifest again and works out each playlist's state.
    public func refresh() {
        var found: [String: PlaylistManifest] = [:]
        for id in PlaylistDownloadPaths.downloadedIDs() {
            if let manifest = PlaylistDownloadPaths.loadManifest(id) { found[id] = manifest }
        }
        manifests = found
        active = active.filter { found[$0.key] != nil }
        downloadedIDs = downloadedIDs.filter { found[$0] != nil }
        ownSeconds = ownSeconds.filter { found[$0.key] != nil }
        for id in found.keys { recompute(id) }
    }

    /// The playlist has a download (complete or not).
    public func hasDownload(_ playlistID: String) -> Bool { manifests[playlistID] != nil }
    public func isDownloaded(_ playlistID: String) -> Bool { downloadedIDs.contains(playlistID) }
    public func isDownloading(_ playlistID: String) -> Bool { active[playlistID] != nil }
    public func state(for playlistID: String) -> PlaylistDownloadState? { active[playlistID] }

    /// The fully downloaded playlists (the Downloads tab's section).
    public var downloadedPlaylists: [JellyfinPlaylist] {
        downloadedIDs.compactMap { manifests[$0]?.playlist }
    }

    /// The playlists lists show offline: every one that has been fully
    /// downloaded at least once, also while songs are being fetched
    /// again or after a later failure.
    public var offlinePlaylists: [JellyfinPlaylist] {
        manifests.values.filter { $0.downloadedAt != nil }.map(\.playlist)
    }

    /// Seconds of music in the fully downloaded playlists' own folders.
    public var ownPlayingTime: TimeInterval {
        ownSeconds.values.reduce(0, +)
    }

    public func size(_ playlistID: String) -> Int64 { PlaylistDownloadPaths.size(playlistID) }

    // MARK: - Starting and keeping in step

    /// Download a playlist whose rows are loaded.
    public func download(playlist: JellyfinPlaylist, entries: [PlaylistEntry]) {
        guard manifests[playlist.id] == nil else { return }
        apply(id: playlist.id, name: playlist.name, hasArt: playlist.hasArt,
              dateCreated: playlist.dateCreated, entries: entries, paused: nil, failure: nil)
    }

    /// The server's rows for a downloaded playlist (the page loaded them,
    /// or they were just changed in the app): the manifest follows, new
    /// songs download, and files no longer in the playlist go.
    public func sync(playlistID: String, entries: [PlaylistEntry]) {
        guard let old = manifests[playlistID] else { return }
        apply(id: playlistID, name: old.name, hasArt: old.hasArt, dateCreated: old.dateCreated,
              entries: entries, paused: old.paused, failure: old.failure)
    }

    /// As `sync(playlistID:entries:)`, with the server's name and picture too.
    public func sync(playlist: JellyfinPlaylist, entries: [PlaylistEntry]) {
        guard let old = manifests[playlist.id] else { return }
        apply(id: playlist.id, name: playlist.name, hasArt: playlist.hasArt,
              dateCreated: playlist.dateCreated ?? old.dateCreated,
              entries: entries, paused: old.paused, failure: old.failure)
    }

    /// The playlist was renamed in the app.
    public func renamed(playlistID: String, to name: String) {
        guard var manifest = manifests[playlistID], manifest.name != name else { return }
        manifest.name = name
        store(manifest)
        recompute(playlistID)
    }

    /// The server's list of playlists (Home refreshed, Library loaded):
    /// each downloaded one in it is compared with the server. One that's
    /// not in the list is left as it is.
    public func sync(with playlists: [JellyfinPlaylist]) {
        for playlist in playlists where manifests[playlist.id] != nil {
            fetchAndSync(playlist.id, playlist: playlist)
        }
    }

    /// Asks the server for its playlists and compares every downloaded
    /// one (at launch, when the connection comes back).
    public func syncAll() {
        guard !manifests.isEmpty, backend.isOnline() else { return }
        backend.playlists { [weak self] result in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard case .success(let playlists) = result else { return }
                    self?.sync(with: playlists)
                }
            }
        }
    }

    /// Asks the server for one playlist's rows, then syncs. `playlist`
    /// carries the server's name when it's known. A failure only shows
    /// when the download was waiting on this (`reportFailure`).
    private func fetchAndSync(_ id: String, playlist: JellyfinPlaylist? = nil, reportFailure: Bool = false) {
        guard manifests[id] != nil else { return }
        guard backend.isOnline() else { return }
        generation[id, default: 0] += 1
        let asked = generation[id]
        backend.entries(id) { [weak self] result in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, self.generation[id] == asked, self.manifests[id] != nil else { return }
                    switch result {
                    case .success(let entries):
                        if let playlist {
                            self.sync(playlist: playlist, entries: entries)
                        } else {
                            self.sync(playlistID: id, entries: entries)
                        }
                    case .failure(let error):
                        if reportFailure { self.fail(id, error.errorDescription ?? "Failed to fetch track list") }
                    }
                }
            }
        }
    }

    /// Writes the manifest for these rows, removes what's no longer in
    /// the playlist, and downloads what's missing.
    private func apply(id: String, name: String, hasArt: Bool, dateCreated: Date?,
                       entries: [PlaylistEntry], paused: Bool?, failure: String?) {
        generation[id, default: 0] += 1
        let old = manifests[id]
        let manifest = PlaylistManifest(
            id: id, name: name, hasArt: hasArt, dateCreated: dateCreated,
            entries: entries.map { PlaylistManifest.Entry($0, format: backend.format($0.track)) },
            paused: paused, failure: failure, downloadedAt: old?.downloadedAt)
        store(manifest)

        // Songs no longer in the playlist: their files and downloads go.
        let wanted = Set(manifest.entries.map { DownloadPaths.sanitize($0.trackId) })
        for (trackID, url) in PlaylistDownloadPaths.trackFiles(id) where !wanted.contains(trackID) {
            try? FileManager.default.removeItem(at: url)
        }
        for (trackID, task) in tasks[id] ?? [:] where !wanted.contains(DownloadPaths.sanitize(trackID)) {
            task.cancel()
            tasks[id]?[trackID] = nil
        }

        if manifest.paused != true, manifest.failure == nil {
            startMissing(id, entries: entries)
        }
        recompute(id)
        let songsChanged = old.map { Set($0.entries.map(\.trackId)) != Set(manifest.entries.map(\.trackId)) } ?? true
        saveArt(id, refreshCover: songsChanged || old?.entries.first?.artItemId != manifest.entries.first?.artItemId)
    }

    /// Starts a download for each song that has no file and isn't already
    /// on its way. A song that's in the playlist twice is fetched once.
    private func startMissing(_ id: String, entries: [PlaylistEntry]) {
        let files = PlaylistFiles(playlistID: id)
        var seen = Set<String>()
        let distinct = entries.filter { seen.insert($0.track.id).inserted }
        for entry in distinct where files.file(for: entry) == nil && tasks[id]?[entry.track.id] == nil {
            let format = backend.format(entry.track)
            guard let url = backend.url(entry.track, format) else {
                fail(id, "No download link for \"\(entry.track.displayName)\"")
                return
            }
            let info = DownloadTaskInfo(
                albumID: entry.albumId ?? "", trackID: entry.track.id, trackNumber: entry.track.trackNumber,
                trackName: entry.track.displayName, format: format,
                expectedTrackCount: distinct.count, playlistID: id)
            tasks[id, default: [:]][entry.track.id] = backend.start(info, url)
        }
    }

    private func store(_ manifest: PlaylistManifest) {
        do {
            try PlaylistDownloadPaths.save(manifest)
        } catch {
            print("[PlaylistDownloads] Could not save the manifest of \(manifest.id): \(error)")
        }
        manifests[manifest.id] = manifest
    }

    /// Works out a playlist's state from the files on disk.
    private func recompute(_ id: String) {
        guard var manifest = manifests[id] else {
            active[id] = nil
            downloadedIDs.remove(id)
            ownSeconds[id] = nil
            return
        }
        let files = PlaylistFiles(playlistID: id)
        let distinct = manifest.distinctEntries
        let done = distinct.filter { files.file(for: $0) != nil }.count
        if done == distinct.count {
            if manifest.paused != nil || manifest.failure != nil || manifest.downloadedAt == nil {
                manifest.paused = nil
                manifest.failure = nil
                manifest.downloadedAt = manifest.downloadedAt ?? Date()
                store(manifest)
            }
            cancelTasks(id)
            active[id] = nil
            downloadedIDs.insert(id)
            ownSeconds[id] = distinct.filter { files.ownFile(trackID: $0.trackId) != nil }
                .reduce(0) { $0 + Double($1.durationTicks) / 10_000_000 }
        } else {
            downloadedIDs.remove(id)
            ownSeconds[id] = nil
            active[id] = PlaylistDownloadState(
                playlistID: id, name: manifest.name,
                expectedTrackCount: distinct.count, completedTracks: done,
                status: manifest.failure != nil ? .failed : manifest.paused == true ? .paused : .downloading,
                errorMessage: manifest.failure)
        }
    }

    // MARK: - Pause, resume, retry, remove

    /// Stops the songs on their way; the ones already saved stay.
    public func pause(_ playlistID: String) {
        guard var manifest = manifests[playlistID], active[playlistID]?.status == .downloading else { return }
        cancelTasks(playlistID)
        manifest.paused = true
        store(manifest)
        recompute(playlistID)
    }

    /// Carries on with the missing songs (each starts again from its beginning).
    public func resume(_ playlistID: String) {
        guard var manifest = manifests[playlistID], active[playlistID]?.status == .paused else { return }
        manifest.paused = nil
        store(manifest)
        recompute(playlistID)
        fetchAndSync(playlistID, reportFailure: true)
    }

    /// Tries a failed download again; saved songs are kept.
    public func retry(_ playlistID: String) {
        guard var manifest = manifests[playlistID], active[playlistID]?.status == .failed else { return }
        manifest.failure = nil
        store(manifest)
        recompute(playlistID)
        fetchAndSync(playlistID, reportFailure: true)
    }

    /// Removes a playlist's download (or cancels it part-way): its folder
    /// goes. Albums' downloads aren't touched.
    public func remove(_ playlistID: String) {
        generation[playlistID, default: 0] += 1
        cancelTasks(playlistID)
        // A picture being saved right now could put the folder back, so
        // it's cleared again once that has stopped.
        if let saving = artTasks[playlistID] {
            saving.cancel()
            artTasks[playlistID] = Task { [weak self] in
                await saving.value
                guard let self, self.manifests[playlistID] == nil else { return }
                PlaylistDownloadPaths.delete(playlistID)
            }
        }
        PlaylistDownloadPaths.delete(playlistID)
        manifests[playlistID] = nil
        active[playlistID] = nil
        downloadedIDs.remove(playlistID)
        ownSeconds[playlistID] = nil
    }

    /// "Download over mobile data" was switched: songs whose request
    /// carries the other choice are started again with the new one (see
    /// `DownloadManager.mobileDataSettingChanged`).
    func mobileDataSettingChanged(allow: Bool) {
        for (id, running) in tasks {
            for (trackID, task) in running {
                guard let download = task as? URLSessionDownloadTask,
                      let original = download.originalRequest, let url = original.url,
                      DownloadNetworkRule.needsRestart(requestAllowsExpensive: original.allowsExpensiveNetworkAccess,
                                                       allowCellular: allow),
                      let info = DownloadTaskInfo(taskDescription: download.taskDescription) else { continue }
                // `start` reads the new setting; the old task's cancel notice is ignored.
                tasks[id]?[trackID] = backend.start(info, url)
                download.cancel()
            }
        }
    }

    private func cancelTasks(_ id: String) {
        for task in (tasks[id] ?? [:]).values { task.cancel() }
        tasks[id] = nil
    }

    private func fail(_ id: String, _ message: String) {
        guard var manifest = manifests[id] else { return }
        print("[PlaylistDownloads] Playlist \(id) failed: \(message)")
        cancelTasks(id)
        manifest.failure = message
        store(manifest)
        recompute(id)
    }

    // MARK: - Albums

    /// An album's download was deleted (or cancelled). Playlists that
    /// played its files show as downloading again, and those songs are
    /// fetched into the playlists' own folders — now when online,
    /// otherwise when the connection comes back.
    public func albumRemoved(_ albumID: String) {
        let affected = manifests.values.filter { $0.entries.contains { $0.albumId == albumID } }.map(\.id)
        for id in affected {
            recompute(id)
            if active[id]?.status == .downloading { fetchAndSync(id) }
        }
    }

    /// An album finished downloading: a playlist waiting for songs that
    /// are in it can use the album's files.
    public func albumDownloaded(_ albumID: String) {
        for manifest in manifests.values where manifest.entries.contains(where: { $0.albumId == albumID }) {
            recompute(manifest.id)
        }
    }

    // MARK: - Session events

    /// A task the session is still running (found at launch, or restarted
    /// after an interruption).
    func adopt(_ info: DownloadTaskInfo, task: PlaylistDownloadTask) {
        guard let id = info.playlistID else { return }
        guard let manifest = manifests[id], manifest.paused != true, manifest.failure == nil,
              manifest.entries.contains(where: { $0.trackId == info.trackID }) else {
            task.cancel()
            return
        }
        if let old = tasks[id]?[info.trackID], old !== task { old.cancel() }
        tasks[id, default: [:]][info.trackID] = task
        recompute(id)
    }

    /// The session saved a song to `info.destination`.
    func completed(_ info: DownloadTaskInfo) {
        guard let id = info.playlistID else { return }
        tasks[id]?[info.trackID] = nil
        guard let manifest = manifests[id] else {
            // The download was removed while the song was on its way.
            try? FileManager.default.removeItem(at: info.destination)
            if PlaylistDownloadPaths.trackFiles(id).isEmpty { PlaylistDownloadPaths.delete(id) }
            return
        }
        guard manifest.entries.contains(where: { $0.trackId == info.trackID }) else {
            try? FileManager.default.removeItem(at: info.destination)
            return
        }
        recompute(id)
    }

    /// A song couldn't be downloaded (after the session's own retries).
    func failed(_ info: DownloadTaskInfo, error: Error) {
        guard let id = info.playlistID, tasks[id]?[info.trackID] != nil else { return }
        tasks[id]?[info.trackID] = nil
        fail(id, "Track download failed: \(error.localizedDescription)")
    }

    // MARK: - Pictures

    /// Saves the rows' pictures and the playlist's own, so they show
    /// offline. Pictures no longer needed go. Failures are tried again
    /// on the next sync.
    private func saveArt(_ id: String, refreshCover: Bool) {
        guard let manifest = manifests[id] else { return }
        // One at a time for a playlist: the newer one waits for the
        // older to stop.
        let earlier = artTasks[id]
        earlier?.cancel()
        let image = backend.image
        let online = backend.isOnline()
        let items = Array(Set(manifest.entries.map(\.artItemId)))
        let first = manifest.entries.first?.artItemId
        let hasArt = manifest.hasArt
        artTasks[id] = Task { [weak self] in
            await earlier?.value
            guard !Task.isCancelled else { return }
            let changed = await Self.saveArt(playlistID: id, items: items, first: first, hasArt: hasArt,
                                             refreshCover: refreshCover, online: online, image: image)
            if changed, !Task.isCancelled { self?.artVersion += 1 }
        }
    }

    /// Waits for the pictures being saved (tests).
    func waitForArt() async {
        for task in Array(artTasks.values) { await task.value }
    }

    private nonisolated static func saveArt(
        playlistID id: String, items: [String], first: String?, hasArt: Bool,
        refreshCover: Bool, online: Bool, image: @Sendable (String, Int) async -> Data?
    ) async -> Bool {
        let fm = FileManager.default
        var changed = false
        let dir = PlaylistDownloadPaths.coversDir(id)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let wanted = Set(items.map { "\(DownloadPaths.sanitize($0)).jpg" })
        for url in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        where !wanted.contains(url.lastPathComponent) {
            try? fm.removeItem(at: url)
        }

        for item in items where PlaylistDownloadPaths.savedArt(playlistID: id, itemID: item) == nil {
            if Task.isCancelled { return changed }
            let dest = PlaylistDownloadPaths.artURL(playlistID: id, itemID: item)
            // An album's download already has its cover.
            if let cover = DownloadPaths.savedCover(albumID: item), (try? fm.copyItem(at: cover, to: dest)) != nil {
                changed = true
            } else if online, let data = await image(item, PlaylistDownloads.coverSize),
                      (try? data.write(to: dest, options: .atomic)) != nil {
                changed = true
            }
        }

        // The playlist's picture: the server's own, else the first row's.
        guard !Task.isCancelled, fm.fileExists(atPath: PlaylistDownloadPaths.manifestURL(id).path) else { return changed }
        let cover = PlaylistDownloadPaths.coverURL(id)
        if hasArt {
            if online, refreshCover || !fm.fileExists(atPath: cover.path),
               let data = await image(id, PlaylistDownloads.coverSize),
               (try? data.write(to: cover, options: .atomic)) != nil {
                changed = true
            }
        } else if let first, let art = PlaylistDownloadPaths.savedArt(playlistID: id, itemID: first),
                  let data = try? Data(contentsOf: art) {
            if (try? Data(contentsOf: cover)) != data, (try? data.write(to: cover, options: .atomic)) != nil {
                changed = true
            }
        } else if first == nil, fm.fileExists(atPath: cover.path) {
            try? fm.removeItem(at: cover)
            changed = true
        }
        return changed
    }
}
