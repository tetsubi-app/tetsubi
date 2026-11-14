// MARK: - FailedDownloads — albums whose download failed partway

import Foundation

/// Remembers failed album downloads (in UserDefaults) so they still show as
/// failed — not as downloaded — after the app restarts.
enum FailedDownloads {

    struct Entry: Codable, Equatable {
        let albumID: String
        let message: String
        let format: AudioFormat
        let expectedTrackCount: Int
    }

    /// Used by tests so they never touch the app's real defaults.
    nonisolated(unsafe) static var defaults: UserDefaults = .standard
    private static let key = "downloads.failedAlbums"

    static func all() -> [Entry] {
        guard let data = defaults.data(forKey: key),
              let entries = try? JSONDecoder().decode([String: Entry].self, from: data)
        else { return [] }
        return Array(entries.values)
    }

    static func save(_ entry: Entry) {
        var entries = Dictionary(uniqueKeysWithValues: all().map { ($0.albumID, $0) })
        entries[entry.albumID] = entry
        write(entries)
    }

    static func remove(albumID: String) {
        var entries = Dictionary(uniqueKeysWithValues: all().map { ($0.albumID, $0) })
        guard entries.removeValue(forKey: albumID) != nil else { return }
        write(entries)
    }

    private static func write(_ entries: [String: Entry]) {
        defaults.set(try? JSONEncoder().encode(entries), forKey: key)
    }
}
