// MARK: - PausedDownloads — saved resume points for paused album downloads

import Foundation

/// One paused track: what to save it as, plus how to continue it.
struct PausedTrack: Codable, Equatable {
    let info: DownloadTaskInfo
    /// The original request URL, used when there's no resume data.
    let url: URL?
    /// URLSession resume data — continues the file where it stopped.
    let resumeData: Data?
}

/// Stores paused tracks as `{root}/{albumID}/{trackID}.json` so a pause
/// survives the app being closed. Kept outside `DownloadPaths.rootDir`,
/// where any folder with files counts as a downloaded album.
enum PausedDownloads {

    /// Used by tests so they never touch the real folder.
    nonisolated(unsafe) static var rootDirOverride: URL?

    static var rootDir: URL {
        if let override = rootDirOverride { return override }
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("PausedDownloads", isDirectory: true)
    }

    private static func albumDir(_ albumID: String) -> URL {
        rootDir.appendingPathComponent(DownloadPaths.sanitize(albumID), isDirectory: true)
    }

    static func save(_ track: PausedTrack) {
        let dir = albumDir(track.info.albumID)
        let file = dir.appendingPathComponent(DownloadPaths.sanitize(track.info.trackID) + ".json")
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try JSONEncoder().encode(track).write(to: file, options: .atomic)
        } catch {
            print("[PausedDownloads] Could not save \(track.info.trackID): \(error)")
        }
    }

    static func load(albumID: String) -> [PausedTrack] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: albumDir(albumID), includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { try? JSONDecoder().decode(PausedTrack.self, from: Data(contentsOf: $0)) }
    }

    /// Album IDs with at least one saved track.
    static func albumIDs() -> [String] {
        let dirs = (try? FileManager.default.contentsOfDirectory(
            at: rootDir, includingPropertiesForKeys: nil)) ?? []
        return dirs.map(\.lastPathComponent).filter { !load(albumID: $0).isEmpty }
    }

    static func remove(albumID: String) {
        try? FileManager.default.removeItem(at: albumDir(albumID))
    }
}
