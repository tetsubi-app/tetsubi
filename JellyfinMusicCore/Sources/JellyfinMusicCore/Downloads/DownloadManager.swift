// MARK: - DownloadManager — @MainActor observable download engine

import Foundation
import Observation

// MARK: - Album download state

/// UI-facing state of a single album download.
public struct DownloadAlbumState: Sendable {
    public let albumID: String
    public let title: String
    public let artistName: String
    public let format: AudioFormat
    public let expectedTrackCount: Int
    public var completedTracks: Int
    public var status: DownloadStatus
    public var progress: Double  // 0.0 – 1.0
    public var startTime: Date
    public var endTime: Date?
    public var errorMessage: String?

    public init(
        albumID: String,
        title: String,
        artistName: String,
        format: AudioFormat,
        expectedTrackCount: Int,
        startTime: Date = Date()
    ) {
        self.albumID          = albumID
        self.title            = title
        self.artistName       = artistName
        self.format           = format
        self.expectedTrackCount = expectedTrackCount
        self.completedTracks  = 0
        self.status           = .pending
        self.progress         = 0
        self.startTime        = startTime
        self.endTime          = nil
        self.errorMessage     = nil
    }

    /// Fraction of expected tracks that have completed.
    var fractionCompleted: Double {
        guard expectedTrackCount > 0 else { return 0 }
        return min(1.0, Double(completedTracks) / Double(expectedTrackCount))
    }
}

// MARK: - Per-task metadata

/// Everything needed to finish a track download, stored in the task's
/// `taskDescription`. A background session keeps tasks (and this string)
/// across app launches, so a download that finishes after the app was
/// suspended or killed can still be saved to the right place.
struct DownloadTaskInfo: Codable, Equatable {
    let albumID: String
    let trackID: String
    let trackNumber: Int
    let trackName: String
    let format: AudioFormat
    let expectedTrackCount: Int
    /// How many times this track has been retried after an interruption.
    /// Optional so descriptions written before this field still decode.
    var retries: Int?
    /// Set when the track is being downloaded for a playlist; the file
    /// then goes to that playlist's folder. Optional for the same reason.
    var playlistID: String?

    var encoded: String? {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) }
    }

    init(albumID: String, trackID: String, trackNumber: Int, trackName: String,
         format: AudioFormat, expectedTrackCount: Int, retries: Int? = nil,
         playlistID: String? = nil) {
        self.albumID = albumID
        self.trackID = trackID
        self.trackNumber = trackNumber
        self.trackName = trackName
        self.format = format
        self.expectedTrackCount = expectedTrackCount
        self.retries = retries
        self.playlistID = playlistID
    }

    init?(taskDescription: String?) {
        guard let data = taskDescription?.data(using: .utf8),
              let info = try? JSONDecoder().decode(DownloadTaskInfo.self, from: data)
        else { return nil }
        self = info
    }

    var destination: URL {
        if let playlistID {
            return PlaylistDownloadPaths.trackURL(playlistID: playlistID, trackID: trackID, format: format)
        }
        return DownloadPaths.trackPath(albumID: albumID, trackNumber: trackNumber,
                                trackName: trackName, format: format)
    }
}

// MARK: - Background session

/// Owns the single background `URLSession` for the process. iOS allows only
/// one session per identifier, so it lives here rather than in
/// `DownloadManager` (which is recreated on each sign-in).
///
/// Finished files are moved to their final location inside the delegate
/// callback, so they land even when the app was relaunched in the background
/// and no `DownloadManager` exists yet.
final class DownloadSessionDelegate: NSObject, URLSessionDownloadDelegate {

    static let shared = DownloadSessionDelegate()

    /// Identifier of the background session (`nil` when running without a
    /// bundle ID, e.g. `swift test`, which falls back to a default session).
    static let sessionIdentifier: String? =
        Bundle.main.bundleIdentifier.map { "\($0).downloads" }

    private(set) lazy var session: URLSession = {
        let config: URLSessionConfiguration
        if let id = Self.sessionIdentifier {
            config = .background(withIdentifier: id)
            config.sessionSendsLaunchEvents = true
            config.isDiscretionary = false
        } else {
            config = .default
            // As a background session does: a request that may not use this
            // network (mobile data off) waits instead of failing.
            config.waitsForConnectivity = true
        }
        // Idle timeout only; transcoded streams can take a while to start.
        config.timeoutIntervalForRequest = 300
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Set by the current `DownloadManager`. Called on the main queue.
    var onComplete: ((DownloadTaskInfo) -> Void)?
    var onError:    ((DownloadTaskInfo, Error) -> Void)?
    /// Passes the cancelled task's identifier, so a late notice can't
    /// untrack a newer task for the same track (e.g. after Pause → Resume).
    var onCancel:   ((DownloadTaskInfo, Int) -> Void)?
    /// A track was restarted with a new task (resumed or from scratch).
    var onRetry:    ((DownloadTaskInfo, URLSessionDownloadTask) -> Void)?

    /// Attempts per track before the album is marked failed.
    static let maxRetries = 5

    /// iOS hands this over when it relaunches the app for finished background
    /// downloads; it must be called once all events are delivered.
    var backgroundCompletionHandler: (() -> Void)?

    // MARK: URLSessionDownloadDelegate

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let info = DownloadTaskInfo(taskDescription: downloadTask.taskDescription) else { return }

        // A 401/404 still "finishes" with an error body — don't save it as a track.
        if let http = downloadTask.response as? HTTPURLResponse,
           !(200..<300).contains(http.statusCode) {
            let error = URLError(.badServerResponse, userInfo: [
                NSLocalizedDescriptionKey: "Server returned HTTP \(http.statusCode)"
            ])
            DispatchQueue.main.async { [weak self] in self?.onError?(info, error) }
            return
        }

        // URLSession deletes `location` as soon as this method returns, so
        // move it to its final place synchronously.
        let dest = info.destination
        do {
            try FileManager.default.createDirectory(
                at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.moveItem(at: location, to: dest)
            DispatchQueue.main.async { [weak self] in self?.onComplete?(info) }
        } catch {
            print("[DownloadManager] Could not save \(info.trackID): \(error)")
            DispatchQueue.main.async { [weak self] in self?.onError?(info, error) }
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error,
              let info = DownloadTaskInfo(taskDescription: task.taskDescription) else { return }

        let nsError = error as NSError
        // Our own `cancel()` has no reason key. The system adds one when it
        // cancels a background task, e.g. the user force-quit the app.
        let cancelledByUser = (error as? URLError)?.code == .cancelled
            && nsError.userInfo[NSURLErrorBackgroundTaskCancelledReasonKey] == nil

        // On mobile data with mobile data off: made again to wait for Wi‑Fi,
        // not counted as a retry (it would fail the album after five).
        if !cancelledByUser, DownloadNetworkRule.isWaitingForAllowedNetwork(error),
           let retry = makeRetryTask(after: task, error: nsError, info: info, in: session, counts: false) {
            DispatchQueue.main.async { [weak self] in self?.onRetry?(retry.info, retry.task) }
            return
        }

        if !cancelledByUser,
           (info.retries ?? 0) < Self.maxRetries,
           let retry = makeRetryTask(after: task, error: nsError, info: info, in: session) {
            DispatchQueue.main.async { [weak self] in self?.onRetry?(retry.info, retry.task) }
            return
        }

        DispatchQueue.main.async { [weak self] in
            if cancelledByUser {
                self?.onCancel?(info, task.taskIdentifier)
            } else {
                self?.onError?(info, error)
            }
        }
    }

    /// Continues an interrupted track from where it stopped when URLSession
    /// supplied resume data (needs a server that honours Range requests —
    /// direct-play files do, transcoded streams don't), otherwise restarts it.
    private func makeRetryTask(
        after task: URLSessionTask,
        error: NSError,
        info: DownloadTaskInfo,
        in session: URLSession,
        counts: Bool = true
    ) -> (info: DownloadTaskInfo, task: URLSessionDownloadTask)? {
        let newTask: URLSessionDownloadTask
        if let resumeData = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            newTask = session.downloadTask(withResumeData: resumeData)
            print("[DownloadManager] Resuming \(info.trackID) (\(resumeData.count) B resume data).")
        } else if let request = task.originalRequest {
            newTask = session.downloadTask(with: request)
            print("[DownloadManager] Restarting \(info.trackID): \(error.localizedDescription)")
        } else {
            return nil
        }
        var next = info
        if counts { next.retries = (info.retries ?? 0) + 1 }
        newTask.taskDescription = next.encoded
        newTask.resume()
        return (next, newTask)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { [weak self] in
            self?.backgroundCompletionHandler?()
            self?.backgroundCompletionHandler = nil
        }
    }
}

// MARK: - Manager

/// `@MainActor @Observable` download engine.
///
/// Uses the shared background `URLSession` (`DownloadSessionDelegate`), so
/// downloads keep going while the app is suspended. Each album download
/// fetches the track list via `JellyfinAPI`, builds a `URLDownloadTask` per
/// track (using `StreamURLBuilder` so the same format-selection logic
/// applies), and tracks completion count up to the album level.
@MainActor
@Observable
public final class DownloadManager {

    // MARK: - Public state

    /// albumID → live download state.
    public private(set) var activeDownloads: [String: DownloadAlbumState] = [:]

    /// Album IDs that have been fully downloaded.
    public private(set) var completedAlbumIDs: Set<String> = []

    /// Downloaded playlists. Their songs come through the same session.
    public let playlists: PlaylistDownloads

    /// Preferred download format. `nil` means "original" — whatever Jellyfin
    /// serves. Persisted across launches.
    public var defaultFormat: AudioFormat? = DownloadManager.loadDefaultFormat() {
        didSet {
            UserDefaults.standard.set(defaultFormat?.rawValue ?? Self.originalFormatToken,
                                      forKey: Self.defaultFormatKey)
        }
    }

    private static let defaultFormatKey = "downloads.defaultFormat"
    private static let originalFormatToken = "original"

    private static func loadDefaultFormat() -> AudioFormat? {
        UserDefaults.standard.string(forKey: defaultFormatKey).flatMap(AudioFormat.init(rawValue:))
            .flatMap { $0.playsOnApple ? $0 : nil }
    }

    // MARK: - Dependencies

    private let api: JellyfinAPI

    /// Cover-art request for an album in the Downloads page. Card-sized
    /// requests (the default) use the cover saved with the download, which is
    /// `coverSize` pixels and works offline, and otherwise ask the server for
    /// the same size; small thumbnails ask for `size` directly.
    public func artRequest(for albumID: String, size: Int? = nil) -> URLRequest? {
        let size = size ?? Self.coverSize
        return Self.artRequest(albumID: albumID, size: size, saved: DownloadPaths.savedCover(albumID:)) {
            api.imageURLRequest(for: albumID, size: size)
        }
    }

    /// The choice made by `artRequest(for:size:)`, apart from the API, so it can be tested:
    /// a saved cover wins whenever the picture is bigger than a thumbnail.
    nonisolated static func artRequest(albumID: String, size: Int, saved: (String) -> URL?,
                           remote: () -> URLRequest?) -> URLRequest? {
        if size > thumbnailSize, let url = saved(albumID) { return URLRequest(url: url) }
        return remote()
    }

    /// Requests up to this many pixels are thumbnails, fetched from the server.
    nonisolated static let thumbnailSize = 200
    private let stream: StreamURLBuilder
    private let delegate = DownloadSessionDelegate.shared
    private var session: URLSession { delegate.session }

    // MARK: - Internal bookkeeping

    /// trackID → in-flight `URLDownloadTask`.
    private var tasks: [String: URLSessionDownloadTask] = [:]
    /// albumID → trackIDs still downloading.
    private var albumTrackIDs: [String: Set<String>] = [:]
    /// albumID → number of tracks completed so far.
    private var completedCount: [String: Int] = [:]
    /// Albums whose resume points are still being written after Pause.
    private var savingPause: Set<String> = []
    /// Albums cancelled this session. A track that finishes just after Cancel
    /// still lands on disk, so its file is deleted when it arrives.
    private var cancelledAlbums: Set<String> = []

    // MARK: - Init

    public init(api: JellyfinAPI) {
        self.api    = api
        let stream  = StreamURLBuilder(api: api, userId: api.userId ?? "")
        self.stream = stream
        self.playlists = PlaylistDownloads(
            api: api, stream: stream,
            session: { DownloadSessionDelegate.shared.session },
            preferredFormat: { DownloadManager.loadDefaultFormat() })
        wireDelegate()
        restorePausedDownloads()   // before refreshDownloaded: paused and failed
        restoreFailedDownloads()   // albums have partial files
        refreshDownloaded()
        restoreInFlightDownloads()
    }

    // MARK: - Background events (iOS)

    /// Call from `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
    /// Recreates the session so iOS can deliver the finished downloads, then
    /// calls `completionHandler` once they've all been handled.
    public static func handleBackgroundEvents(
        identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        guard identifier == DownloadSessionDelegate.sessionIdentifier else { return }
        let delegate = DownloadSessionDelegate.shared
        delegate.backgroundCompletionHandler = completionHandler
        _ = delegate.session
    }

    // MARK: - Delegate wiring

    private func wireDelegate() {
        // Tasks that carry a playlist ID belong to `playlists`.
        delegate.onComplete = { [weak self] info in
            if info.playlistID != nil { self?.playlists.completed(info) } else { self?.handleCompletion(info) }
        }
        delegate.onError = { [weak self] info, error in
            if info.playlistID != nil {
                self?.playlists.failed(info, error: error)
            } else {
                self?.handleError(info, error: error)
            }
        }
        delegate.onCancel = { [weak self] info, taskID in
            guard info.playlistID == nil,
                  let self, self.tasks[info.trackID]?.taskIdentifier == taskID else { return }
            self.forgetTask(info)
        }
        delegate.onRetry = { [weak self] info, task in
            if info.playlistID != nil { self?.playlists.adopt(info, task: task) } else { self?.adopt([(info, task)]) }
        }
    }

    /// Rebuilds album progress from tasks the background session is still
    /// running (e.g. started before the app was suspended or quit).
    private func restoreInFlightDownloads() {
        session.getAllTasks { [weak self] all in
            let running = all.compactMap { task -> (DownloadTaskInfo, URLSessionDownloadTask)? in
                guard let download = task as? URLSessionDownloadTask,
                      download.state == .running || download.state == .suspended,
                      let info = DownloadTaskInfo(taskDescription: download.taskDescription)
                else { return nil }
                return (info, download)
            }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let albums = running.filter { $0.0.playlistID == nil }
                    if !albums.isEmpty { self.adopt(albums) }
                    for (info, task) in running where info.playlistID != nil {
                        self.playlists.adopt(info, task: task)
                    }
                    // Once the running tasks are known, so nothing starts twice.
                    self.playlists.syncAll()
                }
            }
        }
    }

    private func adopt(_ running: [(DownloadTaskInfo, URLSessionDownloadTask)]) {
        for (info, task) in running {
            tasks[info.trackID] = task
            albumTrackIDs[info.albumID, default: []].insert(info.trackID)
        }
        for (albumID, trackIDs) in albumTrackIDs where activeDownloads[albumID] == nil {
            guard let sample = running.first(where: { $0.0.albumID == albumID })?.0 else { continue }
            let names = albumInfo(for: albumID)
            var state = DownloadAlbumState(
                albumID: albumID,
                title: names?.title ?? "",
                artistName: names?.artist ?? "",
                format: sample.format,
                expectedTrackCount: sample.expectedTrackCount
            )
            // Tracks already on disk; works whether tasks arrive all at once
            // (launch) or one by one (retries after a force-quit).
            let done = min(DownloadPaths.fileCount(albumID: albumID),
                           max(0, sample.expectedTrackCount - trackIDs.count))
            completedCount[albumID] = done
            state.completedTracks = done
            state.progress = state.fractionCompleted
            state.status = .downloading
            activeDownloads[albumID] = state
        }
        refreshDownloaded()
        print("[DownloadManager] Adopted \(running.count) track download(s).")
    }

    // MARK: - Downloaded albums on disk

    private nonisolated static let albumInfoKey = "downloads.albumInfo"

    /// Title and artist remembered for downloaded albums (the folder only has the ID).
    public func albumInfo(for albumID: String) -> (title: String, artist: String)? {
        Self.storedAlbumInfo(for: albumID)
    }

    /// Same as `albumInfo(for:)`, without needing a manager instance.
    public nonisolated static func storedAlbumInfo(for albumID: String) -> (title: String, artist: String)? {
        let all = UserDefaults.standard.dictionary(forKey: Self.albumInfoKey) as? [String: [String]] ?? [:]
        guard let v = all[albumID], v.count == 2 else { return nil }
        return (v[0], v[1])
    }

    private func saveAlbumInfo(_ album: JellyfinAlbum) {
        var all = UserDefaults.standard.dictionary(forKey: Self.albumInfoKey) as? [String: [String]] ?? [:]
        all[album.id] = [album.displayName, album.artistName]
        UserDefaults.standard.set(all, forKey: Self.albumInfoKey)
    }

    /// Rebuilds the downloaded-album set from disk. `completedAlbumIDs` is
    /// otherwise only filled in memory, so it was empty after a relaunch and
    /// the Downloads tab showed nothing. Albums still downloading are excluded.
    public func refreshDownloaded() {
        let onDisk = Set(DownloadPaths.downloadedAlbumIDs())
        completedAlbumIDs = onDisk.subtracting(activeDownloads.keys)
        // Albums downloaded before covers were saved get theirs now (when
        // the server is reachable; otherwise next time).
        for albumID in onDisk where DownloadPaths.savedCover(albumID: albumID) == nil {
            saveCover(albumID: albumID)
        }
    }

    /// Size of the saved cover (px): matches the lock-screen artwork request.
    static let coverSize = 600

    /// Fetches and saves an album's cover alongside its download, so it
    /// shows on the lock screen offline. Failures are ignored: the cover is
    /// tried again on the next launch.
    private func saveCover(albumID: String) {
        guard let request = api.imageURLRequest(for: albumID, size: Self.coverSize) else { return }
        Task.detached(priority: .utility) {
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse).map({ (200...299).contains($0.statusCode) }) ?? true,
                  !data.isEmpty else { return }
            do { try DownloadPaths.saveCover(data, albumID: albumID) }
            catch { print("[DownloadManager] Could not save cover for \(albumID): \(error)") }
        }
    }

    // MARK: - Public API

    /// Begin downloading an album. Fetches the track list then creates a
    /// `URLDownloadTask` for every track. No-op if the album is already
    /// active or already downloaded.
    public func download(
        album: JellyfinAlbum,
        format: AudioFormat? = nil
    ) {
        let preferred = format ?? defaultFormat
        guard activeDownloads[album.id] == nil else {
            print("[DownloadManager] Already downloading \(album.id).")
            return
        }
        guard !completedAlbumIDs.contains(album.id),
              !DownloadPaths.exists(albumID: album.id) else {
            print("[DownloadManager] Album already downloaded: \(album.id).")
            return
        }
        cancelledAlbums.remove(album.id)
        saveAlbumInfo(album)
        fetchAndStart(albumID: album.id, title: album.displayName,
                      artist: album.artistName, preferred: preferred)
    }

    /// Retry a failed album. Tracks already on disk are kept; only the
    /// missing ones are downloaded.
    public func retry(albumID: String) {
        guard let state = activeDownloads[albumID], state.status == .failed else { return }
        FailedDownloads.remove(albumID: albumID)
        fetchAndStart(albumID: albumID, title: state.title,
                      artist: state.artistName, preferred: defaultFormat)
    }

    /// Whether an album's download failed and can be retried.
    public func isFailed(_ albumID: String) -> Bool {
        activeDownloads[albumID]?.status == .failed
    }

    /// Fetches the track list, then starts the tracks not yet on disk.
    private func fetchAndStart(albumID: String, title: String, artist: String,
                               preferred: AudioFormat?) {
        try? FileManager.default.createDirectory(
            at: DownloadPaths.albumDir(albumID),
            withIntermediateDirectories: true
        )
        var placeholder = DownloadAlbumState(
            albumID: albumID,
            title: title,
            artistName: artist,
            format: preferred ?? .flac,
            expectedTrackCount: 0
        )
        // Keep showing the old progress while the track list reloads on retry.
        if let old = activeDownloads[albumID] {
            placeholder = DownloadAlbumState(albumID: albumID, title: title, artistName: artist,
                                             format: old.format,
                                             expectedTrackCount: old.expectedTrackCount)
            placeholder.completedTracks = old.completedTracks
            placeholder.progress = old.progress
        }
        activeDownloads[albumID] = placeholder
        completedCount[albumID] = 0
        if DownloadPaths.savedCover(albumID: albumID) == nil { saveCover(albumID: albumID) }

        api.tracksForAlbum(albumID) { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success(let tracks):
                    self.startTrackDownloads(albumID: albumID, title: title, artist: artist,
                                             tracks: tracks, preferred: preferred)
                case .failure(let err):
                    self.teardownAlbum(albumID: albumID,
                                       error: err.errorDescription ?? "Failed to fetch track list")
                    print("[DownloadManager] tracksForAlbum failed: \(err)")
                }
            }
        }
    }

    /// Cancel every download task for an album and delete the tracks that
    /// already finished.
    public func cancel(albumID: String) {
        for trackID in albumTrackIDs[albumID] ?? [] {
            tasks[trackID]?.cancel()
            tasks[trackID] = nil
        }
        activeDownloads[albumID] = nil
        albumTrackIDs[albumID]   = nil
        completedCount[albumID]  = nil
        PausedDownloads.remove(albumID: albumID)
        FailedDownloads.remove(albumID: albumID)
        // Remove the tracks that already finished, so a cancelled album
        // doesn't show up as downloaded.
        cancelledAlbums.insert(albumID)
        do {
            try DownloadPaths.delete(albumID: albumID)
        } catch {
            print("[DownloadManager] Could not delete cancelled album \(albumID): \(error)")
        }
        completedAlbumIDs.remove(albumID)
        playlists.albumRemoved(albumID)
        print("[DownloadManager] Canceled album \(albumID) and deleted its tracks.")
    }

    /// Pause an album. Each unfinished track is stopped and its resume point
    /// saved to disk, so it can continue later — even after the app is closed.
    public func pause(albumID: String) {
        guard var state = activeDownloads[albumID], state.status == .downloading else { return }
        let running = (albumTrackIDs[albumID] ?? []).compactMap { tasks[$0] }
        state.status = .paused
        activeDownloads[albumID] = state
        // Untrack first; a track that finishes during the pause still lands on
        // disk and is skipped on resume.
        albumTrackIDs[albumID] = nil
        for task in running {
            if let info = DownloadTaskInfo(taskDescription: task.taskDescription) {
                tasks[info.trackID] = nil
            }
        }

        savingPause.insert(albumID)
        let group = DispatchGroup()
        for task in running {
            guard let info = DownloadTaskInfo(taskDescription: task.taskDescription) else {
                task.cancel()
                continue
            }
            let url = task.originalRequest?.url
            group.enter()
            task.cancel { resumeData in
                PausedDownloads.save(PausedTrack(info: info, url: url, resumeData: resumeData))
                group.leave()
            }
        }
        group.notify(queue: .main) { [weak self] in
            MainActor.assumeIsolated {
                self?.savingPause.remove(albumID)
                // Cancelled while the resume points were being written.
                if self?.cancelledAlbums.contains(albumID) == true {
                    PausedDownloads.remove(albumID: albumID)
                    return
                }
                print("[DownloadManager] Paused album \(albumID) (\(running.count) track(s)).")
            }
        }
    }

    /// Continue a paused album from the saved resume points.
    public func resume(albumID: String) {
        guard var state = activeDownloads[albumID], state.status == .paused,
              !savingPause.contains(albumID) else { return }
        let saved = PausedDownloads.load(albumID: albumID)
        PausedDownloads.remove(albumID: albumID)

        var trackIDs = Set<String>()
        for track in saved
        where !FileManager.default.fileExists(atPath: track.info.destination.path) {
            let task: URLSessionDownloadTask
            if let data = track.resumeData {
                task = session.downloadTask(withResumeData: data)
            } else if let url = track.url {
                var request = URLRequest(url: url)
                request.allowsExpensiveNetworkAccess = PlaybackSettings.downloadOverCellular
                task = session.downloadTask(with: request)
            } else {
                continue
            }
            task.taskDescription = track.info.encoded
            tasks[track.info.trackID] = task
            trackIDs.insert(track.info.trackID)
            task.resume()
        }

        let done = DownloadPaths.fileCount(albumID: albumID)
        completedCount[albumID] = done
        state.completedTracks = done
        state.progress = state.fractionCompleted
        state.status = .downloading
        activeDownloads[albumID] = state
        albumTrackIDs[albumID] = trackIDs

        if trackIDs.isEmpty {
            if done >= state.expectedTrackCount && state.expectedTrackCount > 0 {
                markComplete(albumID: albumID)
            } else {
                teardownAlbum(albumID: albumID, error: "Nothing saved to resume")
            }
        }
        print("[DownloadManager] Resumed album \(albumID) (\(trackIDs.count) track(s)).")
    }

    /// "Download over mobile data" was switched. A song's request carries
    /// the choice it was made with, so songs still on their way with the
    /// other one are made again: switched on, the ones waiting for Wi‑Fi
    /// start on mobile data; switched off, none carries on over mobile data.
    /// A song part-way through starts again from its beginning.
    public func mobileDataSettingChanged() {
        let allow = PlaybackSettings.downloadOverCellular
        for (trackID, task) in tasks {
            guard let original = task.originalRequest,
                  DownloadNetworkRule.needsRestart(requestAllowsExpensive: original.allowsExpensiveNetworkAccess,
                                                   allowCellular: allow) else { continue }
            var request = original
            request.allowsExpensiveNetworkAccess = allow
            let replacement = session.downloadTask(with: request)
            replacement.taskDescription = task.taskDescription
            // Tracked first, so the old task's cancel notice is ignored.
            tasks[trackID] = replacement
            replacement.resume()
            task.cancel()
        }
        playlists.mobileDataSettingChanged(allow: allow)
    }

    /// Whether an album download is paused.
    public func isPaused(_ albumID: String) -> Bool {
        activeDownloads[albumID]?.status == .paused
    }

    /// Shows albums that failed in an earlier session, so they can be retried.
    private func restoreFailedDownloads() {
        for entry in FailedDownloads.all() where activeDownloads[entry.albumID] == nil {
            let names = albumInfo(for: entry.albumID)
            var state = DownloadAlbumState(
                albumID: entry.albumID,
                title: names?.title ?? "",
                artistName: names?.artist ?? "",
                format: entry.format,
                expectedTrackCount: entry.expectedTrackCount
            )
            state.completedTracks = DownloadPaths.fileCount(albumID: entry.albumID)
            state.progress = state.fractionCompleted
            state.status = .failed
            state.errorMessage = entry.message
            activeDownloads[entry.albumID] = state
        }
    }

    /// Shows albums paused in an earlier session as paused downloads.
    private func restorePausedDownloads() {
        for albumID in PausedDownloads.albumIDs() where activeDownloads[albumID] == nil {
            guard let sample = PausedDownloads.load(albumID: albumID).first?.info else { continue }
            let names = albumInfo(for: albumID)
            var state = DownloadAlbumState(
                albumID: albumID,
                title: names?.title ?? "",
                artistName: names?.artist ?? "",
                format: sample.format,
                expectedTrackCount: sample.expectedTrackCount
            )
            state.completedTracks = DownloadPaths.fileCount(albumID: albumID)
            state.progress = state.fractionCompleted
            state.status = .paused
            activeDownloads[albumID] = state
        }
    }

    /// Whether an album download is in progress.
    public func isDownloading(_ albumID: String) -> Bool {
        activeDownloads[albumID] != nil
    }

    /// Whether an album has been fully downloaded. An album still in
    /// `activeDownloads` (downloading, paused or failed) isn't, even though
    /// its finished tracks are already on disk.
    public func isDownloaded(_ albumID: String) -> Bool {
        Self.isDownloaded(inProgress: activeDownloads[albumID] != nil,
                          finished: completedAlbumIDs.contains(albumID),
                          onDisk: DownloadPaths.exists(albumID: albumID))
    }

    /// Downloaded = finished (or found on disk) and not still in progress.
    /// Without the in-progress check, the first saved track made the whole
    /// album show "Downloaded".
    nonisolated static func isDownloaded(inProgress: Bool, finished: Bool, onDisk: Bool) -> Bool {
        !inProgress && (finished || onDisk)
    }

    /// The live download state of an album, if it's currently downloading.
    public func state(for albumID: String) -> DownloadAlbumState? {
        activeDownloads[albumID]
    }

    /// Delete all files for a downloaded album from disk.
    public func deleteDownloadedAlbum(_ albumID: String) {
        do {
            try DownloadPaths.delete(albumID: albumID)
            completedAlbumIDs.remove(albumID)
            playlists.albumRemoved(albumID)
            print("[DownloadManager] Deleted album \(albumID).")
        } catch {
            print("[DownloadManager] Delete error: \(error)")
        }
    }

    /// Total disk usage across all downloaded albums, in bytes.
    public var totalDiskUsage: Int64 {
        DownloadPaths.totalDiskUsage()
    }

    // MARK: - Internal — task management

    private func startTrackDownloads(
        albumID: String,
        title: String,
        artist: String,
        tracks: [JellyfinTrack],
        preferred: AudioFormat?
    ) {
        // Cancelled while the track list was loading.
        guard activeDownloads[albumID] != nil else { return }
        guard !tracks.isEmpty else {
            teardownAlbum(albumID: albumID, error: "The album has no tracks")
            return
        }

        // On a retry some tracks are already on disk (maybe in another format).
        let numbered = Array(zip(tracks, JellyfinTrack.fileNumbers(for: tracks)))
        let missing = numbered.filter { track, number in
            !DownloadPaths.trackExists(albumID: albumID, trackNumber: number,
                                       trackName: track.displayName)
        }
        var downloads: [(JellyfinTrack, Int, AudioFormat, URL)] = []
        for (track, number) in missing {
            let trackFormat = AudioFormat.downloadFormat(for: track, preferred: preferred)
            guard let url = stream.downloadURL(for: track, format: trackFormat) else {
                // Without this the album would wait forever for the track.
                teardownAlbum(albumID: albumID, error: "No download link for \"\(track.displayName)\"")
                return
            }
            downloads.append((track, number, trackFormat, url))
        }

        let format = tracks.first.map { AudioFormat.downloadFormat(for: $0, preferred: preferred) }
            ?? preferred ?? .flac
        let done = tracks.count - missing.count
        completedCount[albumID] = done
        var state = DownloadAlbumState(
            albumID:     albumID,
            title:       title,
            artistName:  artist,
            format:      format,
            expectedTrackCount: tracks.count
        )
        state.completedTracks = done
        state.progress = state.fractionCompleted
        state.status = .downloading
        activeDownloads[albumID] = state

        guard !downloads.isEmpty else {
            markComplete(albumID: albumID)
            return
        }

        // Tracks with their own picture: save it beside the files.
        let covers: [(trackID: String, fileName: String)] = numbered.compactMap { track, number in
            guard track.hasOwnArt else { return nil }
            let format = AudioFormat.downloadFormat(for: track, preferred: preferred)
            return (track.id, DownloadPaths.trackPath(albumID: albumID, trackNumber: number,
                                                     trackName: track.displayName, format: format).lastPathComponent)
        }
        let coverAPI = api
        Task {
            await TrackCovers.save(covers, albumID: albumID, api: coverAPI)
            TrackCovers.markChecked(albumID: albumID)
        }

        // Keep the real titles: file names lose brackets and punctuation.
        DownloadedTitles.save(
            Dictionary(numbered.map { ($1, $0.displayName) }, uniquingKeysWith: { a, _ in a }),
            for: albumID)

        var trackIDs = Set<String>()
        for (track, number, trackFormat, url) in downloads {
            let info = DownloadTaskInfo(
                albumID: albumID,
                trackID: track.id,
                trackNumber: number,   // disc-aware: disc 2 track 1 → 201
                trackName: track.displayName,
                format: trackFormat,
                expectedTrackCount: tracks.count
            )
            var request = URLRequest(url: url)
            // "Download over cellular" off → the background session waits for Wi‑Fi.
            request.allowsExpensiveNetworkAccess = PlaybackSettings.downloadOverCellular
            let task = session.downloadTask(with: request)
            task.taskDescription = info.encoded
            tasks[track.id] = task
            trackIDs.insert(track.id)
            task.resume()
        }
        albumTrackIDs[albumID] = trackIDs
    }

    /// The delegate has already moved the file to `info.destination`.
    private func handleCompletion(_ info: DownloadTaskInfo) {
        if cancelledAlbums.contains(info.albumID) {
            // Finished just after Cancel: discard it.
            tasks[info.trackID] = nil
            try? FileManager.default.removeItem(at: info.destination)
            if DownloadPaths.fileCount(albumID: info.albumID) == 0 {
                try? DownloadPaths.delete(albumID: info.albumID)
            }
            return
        }
        guard albumTrackIDs[info.albumID]?.contains(info.trackID) == true else {
            // Not tracked (e.g. finished before `adopt` ran); the file is on disk.
            tasks[info.trackID] = nil
            refreshDownloaded()
            return
        }
        forgetTask(info)
        updateProgress(albumID: info.albumID)
    }

    private func handleError(_ info: DownloadTaskInfo, error: Error) {
        let wasTracked = albumTrackIDs[info.albumID]?.contains(info.trackID) == true
        forgetTask(info)
        guard wasTracked else { return }
        teardownAlbum(albumID: info.albumID,
                      error: "Track download failed: \(error.localizedDescription)")
    }

    private func forgetTask(_ info: DownloadTaskInfo) {
        tasks[info.trackID] = nil
        albumTrackIDs[info.albumID]?.remove(info.trackID)
    }

    /// After a track finishes, recompute the album's progress and
    /// mark it complete when every track has landed.
    private func updateProgress(albumID: String) {
        guard var state = activeDownloads[albumID] else { return }
        let done = (completedCount[albumID] ?? 0) + 1
        completedCount[albumID] = done
        state.completedTracks = done
        state.progress = state.fractionCompleted

        if done >= state.expectedTrackCount && state.expectedTrackCount > 0 {
            markComplete(albumID: albumID)
        } else {
            activeDownloads[albumID] = state
        }
    }

    private func markComplete(albumID: String) {
        FailedDownloads.remove(albumID: albumID)
        activeDownloads[albumID] = nil
        albumTrackIDs[albumID]  = nil
        completedCount[albumID] = nil
        completedAlbumIDs.insert(albumID)
        playlists.albumDownloaded(albumID)
        print("[DownloadManager] Album \(albumID) download complete.")
    }

    /// Tear down an album's download state and record the failure.
    /// Marks an album failed. It stays in `activeDownloads` (so its partial
    /// files don't count as downloaded) and is saved so it survives a relaunch.
    /// The user can Retry or Cancel it.
    private func teardownAlbum(albumID: String, error: String) {
        guard var state = activeDownloads[albumID] else { return }
        print("[DownloadManager] Album \(albumID) failed: \(error)")
        for trackID in albumTrackIDs[albumID] ?? [] {
            tasks[trackID]?.cancel()
            tasks[trackID] = nil
        }
        albumTrackIDs[albumID]  = nil
        completedCount[albumID] = nil
        state.completedTracks = DownloadPaths.fileCount(albumID: albumID)
        state.progress = state.fractionCompleted
        state.status = .failed
        state.errorMessage = error
        state.endTime = Date()
        activeDownloads[albumID] = state
        FailedDownloads.save(FailedDownloads.Entry(
            albumID: albumID, message: error, format: state.format,
            expectedTrackCount: state.expectedTrackCount))
    }
}
