// MARK: - DownloadedTracks — an album page's track list from a download

import Foundation
import AVFoundation

/// A downloaded album's tracks as the server would list them, built from
/// its files and saved titles, so the album page works offline.
public enum DownloadedTracks {

    /// The download's tracks, in file order. Titles follow the same rules
    /// as local playback (saved titles by number, else by file name for
    /// older downloads whose numbers clash, else the file name). Disc 2
    /// track 1 is saved as number 201, so numbers from 200 up become disc
    /// and track. Ids match the queued local tracks, so the playing row
    /// is highlighted. `durations` are by file name (`durations(albumID:)`).
    public static func tracks(
        albumID: String,
        albumTitle: String?,
        durations: [String: TimeInterval] = [:]
    ) -> [JellyfinTrack] {
        let local = DownloadPaths.localTracks(albumID: albumID)
        let numbers = local.map(\.trackNumber)
        let clashing = Set(numbers).count != numbers.count
        let titles = clashing ? [:] : DownloadedTitles.titles(for: albumID)
        let byFile = clashing ? DownloadedTitles.titlesByFile(for: albumID) : [:]
        let hasDiscs = !clashing && numbers.contains { $0 >= 200 }
        return local.enumerated().map { i, entry in
            let fileTitle = DownloadedTitles.fileTitle(entry.name)
            let n = entry.trackNumber
            // Clashing numbers (all "00") are numbered by position instead.
            let index = clashing ? i + 1 : hasDiscs ? n % 100 : n
            var track = JellyfinTrack(
                id: albumID + "/" + entry.url.lastPathComponent,
                name: titles[n] ?? byFile[fileTitle] ?? fileTitle,
                serverId: nil,
                indexNumber: index,
                runTimeTicks: durations[entry.name].map { Int64($0 * 10_000_000) },
                album: albumTitle,
                albumId: albumID,
                albumArtists: nil,
                imageTags: nil,
                mediaSource: nil
            )
            track.discNumber = hasDiscs ? max(1, n / 100) : nil
            return track
        }
    }

    /// Each downloaded file's length in seconds, by file name, read from
    /// the files themselves. Unreadable files are left out.
    public static func durations(albumID: String) async -> [String: TimeInterval] {
        var out: [String: TimeInterval] = [:]
        for entry in DownloadPaths.localTracks(albumID: albumID) {
            if let secs = try? await AVURLAsset(url: entry.url).load(.duration).seconds,
               secs.isFinite, secs > 0 {
                out[entry.name] = secs
            }
        }
        return out
    }
}
