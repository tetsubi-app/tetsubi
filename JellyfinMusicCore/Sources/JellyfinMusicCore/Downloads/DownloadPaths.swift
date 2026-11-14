// MARK: - DownloadPaths — on-disk file layout for downloaded albums

import Foundation
import AVFoundation

/// Manages the filesystem layout for downloaded album content.
///
/// Albums are stored as directories: `{root}/{albumID}/01 - Track Name.flac`,
/// etc. The root directory is the shared app-group container when available,
/// falling back to the documents directory (e.g. on the simulator) where the
/// app-group entitlement isn't provisioned.
public struct DownloadPaths {

    /// Shared app-group identifier — matches the `KeychainManager` access group.
    static let appGroup = "group.gg.dekiru.musicplayer.shared"

    /// Replaces the root directory entirely. Used by tests so they never touch
    /// the real app-group container (which an unsigned test host can't write to).
    nonisolated(unsafe) static var rootDirOverride: URL?

    /// Root directory for all downloaded albums. On iOS, the App Group's
    /// shared container when it exists; otherwise (and always on the Mac,
    /// which has no App Group: macOS refused the sandboxed app access to it)
    /// the app's documents directory.
    public static var rootDir: URL {
        if let override = rootDirOverride { return override }
        #if os(iOS)
        if let url = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup
        ) {
            return url.appendingPathComponent("Downloads", isDirectory: true)
        }
        #endif
        let docs = FileManager.default.urls(
            for: .documentDirectory, in: .userDomainMask
        )[0]
        return docs.appendingPathComponent("Downloads", isDirectory: true)
    }

    /// Directory for a specific album's tracks.
    public static func albumDir(_ albumID: String) -> URL {
        rootDir.appendingPathComponent(sanitize(albumID), isDirectory: true)
    }

    /// Hidden folder holding a cover picture for each downloaded album, so
    /// the lock screen and Dynamic Island show art when the server can't be
    /// reached. Kept out of the album folders, whose files are all tracks.
    static var coversDir: URL {
        rootDir.appendingPathComponent(".covers", isDirectory: true)
    }

    /// Where an album's saved cover lives (it may not exist yet).
    public static func coverPath(albumID: String) -> URL {
        coversDir.appendingPathComponent("\(sanitize(albumID)).jpg")
    }

    /// The album's saved cover, if there is one.
    public static func savedCover(albumID: String) -> URL? {
        let url = coverPath(albumID: albumID)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Saves an album's cover picture.
    public static func saveCover(_ data: Data, albumID: String) throws {
        try FileManager.default.createDirectory(at: coversDir, withIntermediateDirectories: true)
        try data.write(to: coverPath(albumID: albumID), options: .atomic)
    }

    /// Full file path for a single track within an album directory.
    public static func trackPath(
        albumID: String,
        trackNumber: Int,
        trackName: String,
        format: AudioFormat
    ) -> URL {
        let dir = albumDir(albumID)
        let number = String(format: "%02d", trackNumber)
        let name = "\(number) - \(sanitize(trackName)).\(format.fileExtension)"
        return dir.appendingPathComponent(name)
    }

    /// Whether a track is already on disk in any format (the extension may
    /// differ if the default format changed since it was downloaded).
    public static func trackExists(albumID: String, trackNumber: Int, trackName: String) -> Bool {
        let stem = trackPath(albumID: albumID, trackNumber: trackNumber,
                             trackName: trackName, format: .flac)
            .deletingPathExtension().lastPathComponent
        let files = (try? FileManager.default.contentsOfDirectory(
            at: albumDir(albumID), includingPropertiesForKeys: nil)) ?? []
        return files.contains { $0.deletingPathExtension().lastPathComponent == stem }
    }

    /// Whether an album directory exists and contains at least one file.
    public static func exists(albumID: String) -> Bool {
        fileCount(albumID: albumID) > 0
    }

    /// IDs of every album directory under the root that contains files.
    public static func downloadedAlbumIDs() -> [String] {
        // Hidden folders (the saved covers) aren't albums.
        let dirs = (try? FileManager.default.contentsOfDirectory(
            at: rootDir, includingPropertiesForKeys: [.isDirectoryKey],
            options: .skipsHiddenFiles)) ?? []
        return dirs
            .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
            .map(\.lastPathComponent)
            // The playlists' folder sits beside the albums'.
            .filter { $0 != PlaylistDownloadPaths.folderName && fileCount(albumID: $0) > 0 }
    }

    /// When an album finished downloading: the newest modification date of
    /// its track files. `nil` if the folder is empty or missing.
    public static func downloadDate(albumID: String) -> Date? {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: albumDir(albumID), includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return files
            .compactMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
            .max()
    }

    /// Album IDs ordered newest download first. Albums without a date go
    /// last; ties fall back to the ID so the order is stable.
    public static func newestFirst<S: Sequence>(
        _ albumIDs: S,
        date: (String) -> Date? = downloadDate(albumID:)
    ) -> [String] where S.Element == String {
        let dated: [(id: String, date: Date)] = albumIDs.map { ($0, date($0) ?? .distantPast) }
        let sorted = dated.sorted { a, b in
            a.date != b.date ? a.date > b.date : a.id < b.id
        }
        return sorted.map(\.id)
    }

    /// Number of track files in an album directory.
    public static func fileCount(albumID: String) -> Int {
        let dir = albumDir(albumID)
        let contents = try? FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        return contents?.count ?? 0
    }

    /// Total size in bytes of all files under an album directory.
    public static func totalSize(albumID: String) -> Int64 {
        let dir = albumDir(albumID)
        guard let enumerator = FileManager.default
            .enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey])
        else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            if let attrs = try? FileManager.default
                .attributesOfItem(atPath: url.path),
               let size = (attrs[.size] as? Int64),
               size > 0
            {
                total += size
            }
        }
        return total
    }

    /// Total size of all downloaded content across every album.
    public static func totalDiskUsage() -> Int64 {
        let dir = rootDir
        guard FileManager.default.fileExists(atPath: dir.path) else { return 0 }
        guard let enumerator = FileManager.default
            .enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey])
        else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            if let attrs = try? FileManager.default
                .attributesOfItem(atPath: url.path),
               let size = (attrs[.size] as? Int64),
               size > 0
            {
                total += size
            }
        }
        return total
    }

    /// Delete an album's directory, all its track files and its saved cover.
    public static func delete(albumID: String) throws {
        try? FileManager.default.removeItem(at: coverPath(albumID: albumID))
        let dir = albumDir(albumID)
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: dir.path, isDirectory: &isDir),
           isDir.boolValue
        {
            try FileManager.default.removeItem(at: dir)
        }
    }

    /// List all local track files in an album directory, sorted by track number.
    ///
    /// Parses the `NN - Title.ext` naming convention to extract the track
    /// number for ordering. Returns `[]` if the directory is empty or missing.
    public static func localTracks(
        albumID: String,
        format: AudioFormat = .flac
    ) -> [(trackNumber: Int, url: URL, name: String)] {
        let dir = albumDir(albumID)
        let files = (try? FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))
            ?? []
        return files
            .compactMap { url -> (Int, URL, String)? in
                let number = parseTrackNumber(from: url.lastPathComponent)
                return number.map { ($0, url, url.lastPathComponent) }
            }
            .sorted { ($0.trackNumber, $0.name) < ($1.trackNumber, $1.name) }
    }

    /// Extract the leading integer from a filename like
    /// `05 - Some Song.flac`. Returns `nil` if the first
    /// component doesn't start with a digit.
    static func parseTrackNumber(from filename: String) -> Int? {
        let digits = String(filename.prefix(while: { $0.isNumber }))
        guard !digits.isEmpty else { return nil }
        return Int(digits)
    }

    /// Sanitise a string for use in a file or directory name — keep
    /// alphanumerics, dashes, underscores, spaces, and dots.
    static func sanitize(_ input: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(
            CharacterSet(charactersIn: "-_ .")
        )
        return String(input.unicodeScalars
            .filter { allowed.contains($0) }
            .map { Character($0) })
    }
}

// MARK: - Track titles

/// Real titles of downloaded tracks, by album ID and track number. File
/// names are sanitised (no brackets, apostrophes, commas…), so local
/// playback reads titles from here, not from the file name.
public enum DownloadedTitles {
    private static let key = "downloads.trackTitles"

    public static func titles(for albumID: String) -> [Int: String] {
        let all = UserDefaults.standard.dictionary(forKey: key) as? [String: [String: String]] ?? [:]
        var out: [Int: String] = [:]
        for (number, title) in all[albumID] ?? [:] {
            if let n = Int(number) { out[n] = title }
        }
        return out
    }

    private static let byFileKey = "downloads.trackTitlesByFile"

    /// Titles by file name (without the "NN - " prefix and extension), for
    /// downloads whose files share a track number: untagged tracks saved
    /// before they were numbered by position (all "00 - …").
    public static func titlesByFile(for albumID: String) -> [String: String] {
        let all = UserDefaults.standard.dictionary(forKey: byFileKey) as? [String: [String: String]] ?? [:]
        return all[albumID] ?? [:]
    }

    public static func saveByFile(_ titles: [String: String], for albumID: String) {
        guard !titles.isEmpty else { return }
        var all = UserDefaults.standard.dictionary(forKey: byFileKey) as? [String: [String: String]] ?? [:]
        all[albumID, default: [:]].merge(titles) { _, new in new }
        UserDefaults.standard.set(all, forKey: byFileKey)
    }

    /// A downloaded file's name part: "00 - Im In Love.flac" → "Im In Love".
    public static func fileTitle(_ fileName: String) -> String {
        (fileName as NSString).deletingPathExtension
            .replacingOccurrences(of: "^\\d+ - ", with: "", options: .regularExpression)
    }

    /// Server titles keyed the way their downloaded files are named, so a
    /// file can be matched to its track: "Im In Love" → "I'm In Love".
    public static func byFileName(_ titles: [String]) -> [String: String] {
        Dictionary(titles.map { (DownloadPaths.sanitize($0), $0) }, uniquingKeysWith: { a, _ in a })
    }

    public static func save(_ titles: [Int: String], for albumID: String) {
        guard !titles.isEmpty else { return }
        var all = UserDefaults.standard.dictionary(forKey: key) as? [String: [String: String]] ?? [:]
        var album = all[albumID] ?? [:]
        for (n, title) in titles { album[String(n)] = title }
        all[albumID] = album
        UserDefaults.standard.set(all, forKey: key)
    }
}

// MARK: - Album duration

extension DownloadPaths {
    private static let durationKey = "downloads.albumDuration"

    /// Total playing time of a downloaded album, read from its files.
    /// Saved per album with the file count, so it's only re-read when
    /// tracks are added or removed. Nil if there are no readable files.
    public static func albumDuration(albumID: String) async -> TimeInterval? {
        let files = localTracks(albumID: albumID).map(\.url)
        guard !files.isEmpty else { return nil }
        let saved = UserDefaults.standard.dictionary(forKey: durationKey) as? [String: [Double]] ?? [:]
        if let entry = saved[albumID], entry.count == 2, Int(entry[0]) == files.count {
            return entry[1]
        }
        var total: TimeInterval = 0
        for url in files {
            if let d = try? await AVURLAsset(url: url).load(.duration).seconds, d.isFinite, d > 0 {
                total += d
            }
        }
        guard total > 0 else { return nil }
        var all = UserDefaults.standard.dictionary(forKey: durationKey) as? [String: [Double]] ?? [:]
        all[albumID] = [Double(files.count), total]
        UserDefaults.standard.set(all, forKey: durationKey)
        return total
    }
}

// MARK: - Byte formatting

extension DownloadPaths {
    /// Formats a byte count as a human-readable string, e.g. "1.2 GB".
    public static func formatBytes(_ bytes: Int64) -> String {
        let fmt = ByteCountFormatter()
        fmt.countStyle = .file
        return fmt.string(fromByteCount: bytes)
    }
}
