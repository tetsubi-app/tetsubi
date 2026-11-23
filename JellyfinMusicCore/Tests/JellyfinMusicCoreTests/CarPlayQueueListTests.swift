import XCTest
@testable import JellyfinMusicCore

/// CarPlay's "Up Next" list: sections per album, rows after the playing
/// track, shuffled as one list, Maniac Mode read-only, CarPlay's limits.
final class CarPlayQueueListTests: XCTestCase {
    typealias F = QueueFixtures
    typealias L = CarPlayQueueList
    var rng = SeededRNG(seed: 11)

    /// A1–A3 (current A1), then B1–B2 and C1–C2 added to the queue.
    private func threeAlbums() -> PlayQueue {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 3), using: &rng)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 2), using: &rng)
        return q
    }

    private func list(_ q: PlayQueue, maniac: Bool = false, items: Int = 100, sections: Int = 50) -> L {
        L(queue: q, maniacMode: maniac, maxItems: items, maxSections: sections)
    }

    private func texts(_ l: L) -> [[String]] { l.sections.map { $0.rows.map(\.text) } }

    private func entryId(_ q: PlayQueue, _ track: String) -> String { q.entries.first { $0.track.id == track }!.id }

    // MARK: Contents

    func testSectionPerAlbumAfterThePlayingTrack() {
        let l = list(threeAlbums())
        XCTAssertEqual(texts(l), [["Song A2", "Song A3"], ["Song B1", "Song B2"], ["Song C1", "Song C2"]])
        XCTAssertEqual(l.sections.map(\.header), ["Album A · Artist", "Album B · Artist", "Album C · Artist"])
        XCTAssertEqual(l.sections.map(\.headerDetail), ["3m 20s", "3m 20s", "3m 20s"])
        XCTAssertEqual(l.hiddenCount, 0)
        XCTAssertFalse(l.isEmpty)
        XCTAssertFalse(l.isReadOnly)
    }

    func testRowsCarryEntryIdsAndDuration() {
        let q = threeAlbums()
        let row = list(q).sections[1].rows[0]
        XCTAssertEqual(row.kind, .entry(entryId: entryId(q, "B1")))
        // Same artist as the album: just the length.
        XCTAssertEqual(row.detail, "1:40")
    }

    func testRowShowsArtistWhenNotTheAlbums() {
        var q = PlayQueue()
        let guest = QueueTrack(id: "X1", title: "Guest", artist: "Someone Else", album: "Album X",
                               albumId: "X", durationSeconds: 225)
        q.replace(group: F.album("X"), tracks: [F.track("X0", album: "X"), guest], using: &rng)
        XCTAssertEqual(list(q).sections[0].rows[0].detail, "Someone Else · 3:45")
    }

    func testHeaderWithoutArtistOrTitle() {
        XCTAssertEqual(L.header(QueueGroup(id: "p", kind: .playlist, title: "Mix")), "Mix")
        XCTAssertEqual(L.header(QueueGroup(id: "a", kind: .album, title: "", artist: "Band")), "Album · Band")
        XCTAssertEqual(L.groupTime(3725), "1h 02m 05s")
        XCTAssertNil(L.groupTime(0))
        XCTAssertNil(L.trackTime(0))
    }

    func testRepeatAllDoesNotWrap() {
        var q = threeAlbums()
        q.repeatMode = .all
        _ = q.skipTo(entryId: entryId(q, "C1"))
        XCTAssertEqual(texts(list(q)), [["Song C2"]])
    }

    func testEmptyWhenNothingFollows() {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 1), using: &rng)
        let l = list(q)
        XCTAssertTrue(l.isEmpty)
        XCTAssertEqual(l.sections, [])
        XCTAssertTrue(list(PlayQueue()).isEmpty)
        // No note over an empty list in Maniac Mode either.
        XCTAssertEqual(list(q, maniac: true).sections, [])
    }

    func testShuffledIsOneSectionInPlayOrderWithAlbumDetail() {
        var q = threeAlbums()
        q.setShuffle(true, using: &rng)
        let l = list(q)
        XCTAssertEqual(l.sections.count, 1)
        XCTAssertEqual(l.sections[0].header, "Shuffled")
        XCTAssertEqual(l.sections[0].headerDetail, "10m 00s")
        let upcoming = Array(q.entriesInPlayOrder.dropFirst())
        XCTAssertEqual(l.sections[0].rows.map(\.kind), upcoming.map { .entry(entryId: $0.id) })
        XCTAssertEqual(l.sections[0].rows[0].detail, "Artist · \(upcoming[0].track.album)")
    }

    // MARK: Maniac Mode

    func testManiacModeIsReadOnlyWithANote() {
        let l = list(threeAlbums(), maniac: true)
        XCTAssertTrue(l.isReadOnly)
        XCTAssertEqual(l.sections.first?.rows.map(\.kind), [.note])
        XCTAssertEqual(l.sections.first?.rows.first?.text, L.maniacNote)
        XCTAssertNil(l.sections.first?.header)
        XCTAssertEqual(l.sections.count, 4)
    }

    // MARK: Limits

    func testItemLimitEndsWithMore() {
        // 6 upcoming, 4 items allowed: 3 tracks and "…and 3 more".
        let l = list(threeAlbums(), items: 4)
        XCTAssertEqual(texts(l), [["Song A2", "Song A3"], ["Song B1", "…and 3 more"]])
        XCTAssertEqual(l.hiddenCount, 3)
        XCTAssertEqual(l.itemCount, 4)
        XCTAssertEqual(l.sections.last?.rows.last?.kind, .more(count: 3))
    }

    func testExactlyAtTheItemLimitNeedsNoMore() {
        let l = list(threeAlbums(), items: 6)
        XCTAssertEqual(l.hiddenCount, 0)
        XCTAssertEqual(l.itemCount, 6)
    }

    func testSectionLimitEndsWithMore() {
        let l = list(threeAlbums(), sections: 2)
        XCTAssertEqual(texts(l), [["Song A2", "Song A3"], ["Song B1", "Song B2", "…and 2 more"]])
        XCTAssertEqual(l.hiddenCount, 2)
    }

    func testLimitsCountTheManiacNote() {
        let l = list(threeAlbums(), maniac: true, items: 4, sections: 3)
        XCTAssertEqual(texts(l), [[L.maniacNote], ["Song A2", "Song A3", "…and 4 more"]])
        XCTAssertEqual(l.itemCount, 4)
    }

    func testShuffledLongQueueIsCut() {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 30), using: &rng)
        q.setShuffle(true, using: &rng)
        let l = list(q, items: 12, sections: 1)
        XCTAssertEqual(l.sections.count, 1)
        XCTAssertEqual(l.itemCount, 12)
        XCTAssertEqual(l.hiddenCount, 18)
        XCTAssertEqual(l.sections[0].rows.last?.text, "…and 18 more")
    }

    func testTinyLimits() {
        let l = list(threeAlbums(), items: 1, sections: 1)
        XCTAssertEqual(texts(l), [["…and 6 more"]])
        XCTAssertEqual(l.hiddenCount, 6)
    }
}
