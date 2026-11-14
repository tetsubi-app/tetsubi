import XCTest
@testable import JellyfinMusicCore

/// Home's On This Day: which albums match today's month and day.
final class OnThisDayMatchTests: XCTestCase {

    private func album(_ id: String, _ date: String) -> JellyfinAlbum {
        JellyfinAlbum(id: id, name: id, serverId: nil, premiereDate: date, productionYear: nil,
                      albumArtists: nil, album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
    }

    func testMatchesTodayInEarlierYearsMostRecentFirst() {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let today = utc.date(from: DateComponents(year: 2026, month: 11, day: 10))!
        let matches = DateFilterHelper.onThisDayMatches([
            album("old", "2008-11-10T00:00:00.0000000Z"),
            album("recent", "2021-11-10T00:00:00.0000000Z"),
            album("wrongDay", "2008-11-11T00:00:00.0000000Z"),
            album("thisYear", "2026-11-10T00:00:00.0000000Z"),
        ], today: today, calendar: utc)
        XCTAssertEqual(matches.map(\.album.id), ["recent", "old"])
        XCTAssertEqual(matches.map(\.yearsAgo), [5, 18])
        XCTAssertEqual(DateFilterHelper.onThisDayLimit, 12)
    }
}
