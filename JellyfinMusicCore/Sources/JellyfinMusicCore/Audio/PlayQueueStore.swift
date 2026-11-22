// MARK: - PlayQueueStore — saving the listening queue across launches

import Foundation

// MARK: - PlayQueueSnapshot

/// The saved queue (JSON, `version` 1). The Kotlin side writes and reads
/// the same shape; optional track fields that are nil are left out.
public struct PlayQueueSnapshot: Codable, Equatable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    /// Natural order.
    public var entries: [QueueEntry]
    public var currentEntryId: String?
    /// Where in the current track playback was, in seconds.
    public var positionSeconds: Double
    public var shuffle: Bool
    /// Entry ids in play order (the natural order while shuffle is off).
    public var playOrder: [String]
    public var repeatMode: RepeatMode
    /// The Jellyfin server and user the queue was saved for (optional:
    /// a file without them loads for anyone).
    public var serverId: String?
    public var userId: String?

    public init(version: Int = PlayQueueSnapshot.currentVersion, entries: [QueueEntry],
                currentEntryId: String?, positionSeconds: Double, shuffle: Bool,
                playOrder: [String], repeatMode: RepeatMode,
                serverId: String? = nil, userId: String? = nil) {
        self.version = version
        self.entries = entries
        self.currentEntryId = currentEntryId
        self.positionSeconds = positionSeconds
        self.shuffle = shuffle
        self.playOrder = playOrder
        self.repeatMode = repeatMode
        self.serverId = serverId
        self.userId = userId
    }
}

// MARK: - PlayQueueStore

/// Saves the queue to `Application Support/PlayQueue/queue.json` (the app's
/// own container; the widget doesn't need it). Never throws to callers.
public enum PlayQueueStore {

    /// Used by tests so they never touch the real file.
    nonisolated(unsafe) static var fileURLOverride: URL?

    static var fileURL: URL {
        if let override = fileURLOverride { return override }
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("PlayQueue", isDirectory: true)
            .appendingPathComponent("queue.json")
    }

    /// Writes `snapshot` atomically; an empty queue removes the file.
    public static func save(_ snapshot: PlayQueueSnapshot) {
        guard !snapshot.entries.isEmpty else { clear(); return }
        let url = fileURL
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try encode(snapshot).write(to: url, options: .atomic)
        } catch {
            print("[PlayQueueStore] Could not save the queue: \(error)")
        }
    }

    /// The saved queue, repaired (see `PlayQueue(snapshot:)`), or nil when
    /// there is none, it can't be read, its version isn't 1, it's empty, or
    /// it was saved for another server or user: a saved `serverId`/`userId`
    /// that is present and differs from the one given (nil on either side
    /// doesn't count as a difference).
    public static func load(serverId: String? = nil, userId: String? = nil) -> PlayQueueSnapshot? {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = decode(data) else { return nil }
        if let saved = snapshot.serverId, let serverId, saved != serverId { return nil }
        if let saved = snapshot.userId, let userId, saved != userId { return nil }
        return snapshot
    }

    public static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }

    static func encode(_ snapshot: PlayQueueSnapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(snapshot)
    }

    private struct VersionOnly: Decodable { let version: Int }

    static func decode(_ data: Data) -> PlayQueueSnapshot? {
        let decoder = JSONDecoder()
        guard let v = try? decoder.decode(VersionOnly.self, from: data),
              v.version == PlayQueueSnapshot.currentVersion,
              let raw = try? decoder.decode(PlayQueueSnapshot.self, from: data) else { return nil }
        let queue = PlayQueue(snapshot: raw)
        guard !queue.isEmpty else { return nil }
        let position = raw.positionSeconds.isFinite ? max(0, raw.positionSeconds) : 0
        return queue.snapshot(positionSeconds: position, serverId: raw.serverId, userId: raw.userId)
    }
}

// MARK: - PlayQueueSaver

/// Debounced saving: call `schedule` after every change; the latest
/// snapshot is written `interval` seconds after the first unsaved change,
/// so at most one write per interval. `flush` writes a pending one now
/// (going to the background, quitting). Thread-safe.
public final class PlayQueueSaver: @unchecked Sendable {
    private let interval: TimeInterval
    private let write: @Sendable (PlayQueueSnapshot) -> Void
    private let queue = DispatchQueue(label: "PlayQueueSaver")
    private var pending: PlayQueueSnapshot?
    private var timerArmed = false

    public init(interval: TimeInterval = 1.0,
                write: @escaping @Sendable (PlayQueueSnapshot) -> Void = { PlayQueueStore.save($0) }) {
        self.interval = interval
        self.write = write
    }

    public func schedule(_ snapshot: PlayQueueSnapshot) {
        queue.async { [self] in
            pending = snapshot
            guard !timerArmed else { return }
            timerArmed = true
            queue.asyncAfter(deadline: .now() + interval) { [self] in
                timerArmed = false
                writePending()
            }
        }
    }

    /// Writes the pending snapshot, if any, before returning.
    public func flush() {
        queue.sync { writePending() }
    }

    private func writePending() {
        guard let snapshot = pending else { return }
        pending = nil
        write(snapshot)
    }
}
