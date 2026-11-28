// MARK: - DateFilterHelper — "On This Day" logic

import Foundation

/// An album identified by its release date, used for "On This Day" filtering.
public struct DatedAlbum: Sendable {
    public let releaseDate: Date
    public init(releaseDate: Date) { self.releaseDate = releaseDate }
}

/// Filters a list of album release dates to find those that fall
/// on today's month-day, ignoring the year.  No minimum distance limit.
public struct DateFilterHelper {

    /// The calendar for reading a release date's month, day and year. `JellyfinAlbum.releaseDate` is the
    /// release day at midnight UTC, so it must be read in UTC whatever the phone's time zone is.
    static let releaseCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// Returns albums whose release month/day match today's month/day,
    /// sorted by most recent (fewest years ago) first.
    ///
    /// - Parameters:
    ///   - albums: a list of `DatedAlbum` values
    ///   - today: optional override for testing; defaults to `Date()`
    /// - Returns: `(yearsAgo, releaseDate)` pairs
    public static func onThisDay(
        from albums: [DatedAlbum],
        today: Date = Date()
    ) -> [(yearsAgo: Int, releaseDate: Date)] {
        let calendar = Calendar.current
        let todayMD = calendar.dateComponents([.month, .day], from: today)

        return albums.compactMap { album in
            let rd = album.releaseDate
            let albumMD = releaseCalendar.dateComponents([.month, .day], from: rd)
            guard todayMD.month == albumMD.month,
                  todayMD.day == albumMD.day else {
                return nil
            }
            let todayYear = calendar.component(.year, from: today)
            let rdYear = releaseCalendar.component(.year, from: rd)
            let yearsAgo = todayYear - rdYear
            return yearsAgo > 0 ? (yearsAgo, rd) : nil
        }.sorted { $0.yearsAgo < $1.yearsAgo }
    }

    /// At most this many albums in Home's On This Day row.
    public static let onThisDayLimit = 12

    /// Albums released on `today`'s month and day in an earlier year, the
    /// most recent first, with how many years ago.
    public static func onThisDayMatches(
        _ albums: [JellyfinAlbum],
        today: Date = Date(),
        calendar: Calendar = .current
    ) -> [(album: JellyfinAlbum, yearsAgo: Int, releaseDate: Date)] {
        let todayMD = calendar.dateComponents([.month, .day], from: today)
        let todayYear = calendar.component(.year, from: today)
        return albums
            .compactMap { album -> (album: JellyfinAlbum, yearsAgo: Int, releaseDate: Date)? in
                guard let rd = album.releaseDate else { return nil }
                // A release date is a calendar day, kept as midnight UTC: read its day in UTC, not in the
                // phone's time zone (which, west of UTC, would show the day before).
                let md = releaseCalendar.dateComponents([.month, .day], from: rd)
                guard md.month == todayMD.month, md.day == todayMD.day else { return nil }
                let yearsAgo = todayYear - releaseCalendar.component(.year, from: rd)
                return yearsAgo > 0 ? (album, yearsAgo, rd) : nil
            }
            .sorted { $0.yearsAgo < $1.yearsAgo }
    }

    /// Human-readable label: "30 years ago", "1 year ago", etc.
    public static func anniversaryLabel(yearsAgo: Int) -> String {
        guard yearsAgo > 0 else { return "" }
        return yearsAgo == 1
            ? "1 year ago"
            : "\(yearsAgo) years ago"
    }
}
