import Foundation

/// Pictures of downloaded tracks that have their own, different from the
/// album's (`JellyfinTrack.hasOwnArt`): The Slip, loose singles. Saved as
/// `<album dir>/covers/<track file name>.jpg`, so they show offline and
/// are deleted with the album.
public enum TrackCovers {
    private static let checkedKey = "downloads.trackCoversChecked"

    static func dir(_ albumID: String) -> URL {
        DownloadPaths.albumDir(albumID).appendingPathComponent("covers", isDirectory: true)
    }

    public static func url(albumID: String, fileName: String) -> URL {
        dir(albumID).appendingPathComponent((fileName as NSString).deletingPathExtension + ".jpg")
    }

    public static func existing(albumID: String, fileName: String) -> URL? {
        let u = url(albumID: albumID, fileName: fileName)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    /// Tracks with their own picture, paired with their downloaded file:
    /// by download number, or by file name when the numbers clash (old
    /// downloads of untagged tracks).
    public static func match(tracks: [JellyfinTrack], files: [String]) -> [(trackID: String, fileName: String)] {
        let numbers = files.map { DownloadPaths.parseTrackNumber(from: $0) ?? -1 }
        let clashing = Set(numbers).count != numbers.count
        let byNumber = Dictionary(zip(numbers, files), uniquingKeysWith: { a, _ in a })
        let byName = Dictionary(files.map { (DownloadedTitles.fileTitle($0), $0) }, uniquingKeysWith: { a, _ in a })
        return zip(tracks, JellyfinTrack.fileNumbers(for: tracks)).compactMap { track, number in
            guard track.hasOwnArt else { return nil }
            let file = clashing ? byName[DownloadPaths.sanitize(track.displayName)] : byNumber[number]
            return file.map { (track.id, $0) }
        }
    }

    /// Fetches and saves each pair's picture (600 px); failures are skipped.
    public static func save(_ pairs: [(trackID: String, fileName: String)], albumID: String, api: JellyfinAPI) async {
        guard !pairs.isEmpty else { return }
        try? FileManager.default.createDirectory(at: dir(albumID), withIntermediateDirectories: true)
        for (trackID, fileName) in pairs {
            guard let request = api.imageURLRequest(for: trackID, size: 600),
                  let (data, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty else { continue }
            try? data.write(to: url(albumID: albumID, fileName: fileName), options: .atomic)
        }
    }

    static func checked(albumID: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: checkedKey) ?? []).contains(albumID)
    }

    static func markChecked(albumID: String) {
        var ids = UserDefaults.standard.stringArray(forKey: checkedKey) ?? []
        if !ids.contains(albumID) { ids.append(albumID) }
        UserDefaults.standard.set(ids, forKey: checkedKey)
    }
}
