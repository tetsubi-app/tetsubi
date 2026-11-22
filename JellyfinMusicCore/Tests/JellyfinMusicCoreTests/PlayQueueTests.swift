import XCTest
@testable import JellyfinMusicCore

/// Deterministic generator for shuffle tests (SplitMix64).
struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}

enum QueueFixtures {
    static func track(_ id: String, album: String = "A", seconds: Double = 100) -> QueueTrack {
        QueueTrack(id: id, title: "Song \(id)", artist: "Artist", album: "Album \(album)",
                   albumId: album, durationSeconds: seconds)
    }
    static func album(_ id: String) -> QueueGroup {
        QueueGroup(id: id, kind: .album, title: "Album \(id)", artist: "Artist", artItemId: id)
    }
    /// `n` tracks named "<album>1"…"<album>n".
    static func tracks(_ album: String, _ n: Int, seconds: Double = 100) -> [QueueTrack] {
        (1...n).map { track("\(album)\($0)", album: album, seconds: seconds) }
    }
}

final class PlayQueueTests: XCTestCase {
    typealias F = QueueFixtures
    var rng = SeededRNG(seed: 42)

    /// Track ids in natural order.
    private func natural(_ q: PlayQueue) -> [String] { q.entries.map(\.track.id) }
    /// Track ids in play order.
    private func played(_ q: PlayQueue) -> [String] { q.entriesInPlayOrder.map(\.track.id) }
    private func current(_ q: PlayQueue) -> String? { q.currentEntry?.track.id }
    private func entryId(_ q: PlayQueue, track: String) -> String { q.entries.first { $0.track.id == track }!.id }

    private func albumQueue(_ n: Int = 3, start: Int? = nil) -> PlayQueue {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", n), startIndex: start, using: &rng)
        return q
    }

    // MARK: Empty

    func testEmptyQueue() {
        var q = PlayQueue()
        XCTAssertTrue(q.isEmpty)
        XCTAssertNil(q.currentEntryId)
        XCTAssertNil(q.next())
        XCTAssertNil(q.previous())
        XCTAssertFalse(q.skipTo(entryId: "x"))
        XCTAssertEqual(q.remove(entryIds: ["x"]), .unchanged)
        XCTAssertFalse(q.move(entryIds: ["x"], toIndex: 0))
        q.clearUpcoming()
        q.setShuffle(true, using: &rng)
        XCTAssertTrue(q.shuffle)
        XCTAssertTrue(q.upcomingEntries.isEmpty)
        XCTAssertTrue(q.displayGroups.isEmpty)
        XCTAssertEqual(q.queueRemaining(position: 10), 0)
        XCTAssertEqual(q.currentGroupRemaining(position: 10), 0)
    }

    func testReplaceWithNoTracksEmpties() {
        var q = albumQueue()
        q.replace(group: F.album("B"), tracks: [], using: &rng)
        XCTAssertTrue(q.isEmpty)
        XCTAssertNil(q.currentEntryId)
        XCTAssertTrue(q.playOrder.isEmpty)
    }

    // MARK: Replace

    func testReplaceStartsAtIndex() {
        let q = albumQueue(3, start: 1)
        XCTAssertEqual(natural(q), ["A1", "A2", "A3"])
        XCTAssertEqual(played(q), ["A1", "A2", "A3"])
        XCTAssertEqual(current(q), "A2")
        XCTAssertEqual(Set(q.entries.map(\.batchId)).count, 1)
        XCTAssertEqual(Set(q.entries.map(\.id)).count, 3)
    }

    func testReplaceOutOfRangeStartsAtFirst() {
        XCTAssertEqual(current(albumQueue(3, start: 7)), "A1")
    }

    func testReplaceDuplicateTracksGetOwnEntries() {
        var q = PlayQueue()
        let t = F.track("X")
        q.replace(group: F.album("P"), tracks: [t, F.track("Y"), t], startIndex: 2, using: &rng)
        XCTAssertEqual(natural(q), ["X", "Y", "X"])
        XCTAssertEqual(q.currentEntryId, q.entries[2].id)
        XCTAssertNotEqual(q.entries[0].id, q.entries[2].id)
        XCTAssertEqual(q.next(), nil)    // the last entry, not the first X
    }

    func testReplaceWhileShuffledPutsStartFirst() {
        var q = PlayQueue()
        q.setShuffle(true, using: &rng)
        q.replace(group: F.album("A"), tracks: F.tracks("A", 8), startIndex: 5, using: &rng)
        XCTAssertEqual(current(q), "A6")
        XCTAssertEqual(q.playOrder.first, q.currentEntryId)
        XCTAssertEqual(Set(q.playOrder), Set(q.entries.map(\.id)))
        XCTAssertEqual(natural(q), F.tracks("A", 8).map(\.id))
    }

    func testReplaceWhileShuffledWithoutStartBeginsAtPlayOrderHead() {
        var q = PlayQueue()
        q.setShuffle(true, using: &rng)
        q.replace(group: F.album("A"), tracks: F.tracks("A", 8), using: &rng)
        XCTAssertEqual(q.playOrder.first, q.currentEntryId)
        XCTAssertEqual(q.playOrder.count, 8)
    }

    func testReplaceKeepsRepeatAndShuffleSettings() {
        var q = albumQueue()
        q.repeatMode = .all
        q.replace(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertEqual(q.repeatMode, .all)
        XCTAssertFalse(q.shuffle)
    }

    // MARK: Play Next / Add to Queue

    func testPlayNextGoesAfterCurrentGroup() {
        var q = albumQueue(3, start: 0)
        q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 2), using: &rng)
        q.playNext(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertEqual(natural(q), ["A1", "A2", "A3", "B1", "B2", "C1", "C2"])
        XCTAssertEqual(current(q), "A1")
        XCTAssertEqual(played(q), natural(q))
    }

    func testSecondPlayNextGoesBeforeFirst() {
        var q = albumQueue(2)
        q.playNext(group: F.album("B"), tracks: F.tracks("B", 1), using: &rng)
        q.playNext(group: F.album("C"), tracks: F.tracks("C", 1), using: &rng)
        XCTAssertEqual(natural(q), ["A1", "A2", "C1", "B1"])
    }

    func testPlayNextWhenCurrentIsLast() {
        var q = albumQueue(3, start: 2)
        q.playNext(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertEqual(natural(q), ["A1", "A2", "A3", "B1", "B2"])
        XCTAssertEqual(q.next(), entryId(q, track: "B1"))
    }

    func testPlayNextWhilePlayingInsideLaterGroup() {
        var q = albumQueue(2)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 3), using: &rng)
        q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 1), using: &rng)
        q.skipTo(entryId: entryId(q, track: "B2"))
        q.playNext(group: F.album("D"), tracks: F.tracks("D", 1), using: &rng)
        XCTAssertEqual(natural(q), ["A1", "A2", "B1", "B2", "B3", "D1", "C1"])
    }

    func testSameAlbumQueuedTwiceShowsTwoGroups() {
        var q = albumQueue(2)
        q.addToQueue(group: F.album("A"), tracks: F.tracks("A", 2), using: &rng)
        XCTAssertEqual(q.displayGroups.count, 2)
        XCTAssertEqual(q.displayGroups.map { $0.entries.map(\.track.id) }, [["A1", "A2"], ["A1", "A2"]])
        XCTAssertEqual(Set(q.entries.map(\.id)).count, 4)
    }

    func testAddToEmptyQueueActsAsReplace() {
        var q = PlayQueue()
        let added = q.addToQueue(group: F.album("A"), tracks: F.tracks("A", 2), using: &rng)
        XCTAssertEqual(added.count, 2)
        XCTAssertEqual(current(q), "A1")
        var p = PlayQueue()
        p.playNext(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertEqual(current(p), "B1")
    }

    func testAddNothingChangesNothing() {
        var q = albumQueue()
        let before = q
        q.addToQueue(group: F.album("B"), tracks: [], using: &rng)
        q.playNext(group: F.album("B"), tracks: [], using: &rng)
        XCTAssertEqual(q, before)
    }

    func testAddWhileShuffledGoesAfterCurrentInPlayOrder() {
        var q = albumQueue(6, start: 2)
        q.setShuffle(true, using: &rng)
        q.next(); q.next()  // current is now third in play order
        let ci = q.currentPlayIndex!
        let before = Array(q.playOrder[...ci])
        let added = q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 4), using: &rng)
        XCTAssertEqual(Array(q.playOrder[...ci]), before, "nothing new before or at current")
        XCTAssertEqual(Set(q.playOrder), Set(q.entries.map(\.id)))
        XCTAssertTrue(added.allSatisfy { q.playOrder.firstIndex(of: $0.id)! > ci })
        // Natural order: at the end.
        XCTAssertEqual(natural(q), ["A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "B4"])
        // Shuffle off restores it.
        q.setShuffle(false, using: &rng)
        XCTAssertEqual(played(q), natural(q))
    }

    func testPlayNextWhileShuffledInsertsInNaturalPlace() {
        var q = albumQueue(3, start: 0)
        q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 2), using: &rng)
        q.setShuffle(true, using: &rng)
        q.playNext(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertEqual(natural(q), ["A1", "A2", "A3", "B1", "B2", "C1", "C2"])
        XCTAssertEqual(q.playOrder.first, q.currentEntryId)
        XCTAssertEqual(q.playOrder.count, 7)
        // Play order: right after current, in their given order.
        XCTAssertEqual(Array(played(q)[1...2]), ["B1", "B2"])
        XCTAssertEqual(q.next(), entryId(q, track: "B1"))
        XCTAssertEqual(q.next(), entryId(q, track: "B2"))
    }

    func testPlayNextWhileShuffledMidQueue() {
        var q = albumQueue(6)
        q.setShuffle(true, using: &rng)
        q.next(); q.next()
        let ci = q.currentPlayIndex!
        let before = q.playOrder
        q.playNext(group: F.album("B"), tracks: F.tracks("B", 3), using: &rng)
        XCTAssertEqual(Array(played(q)[(ci + 1)...(ci + 3)]), ["B1", "B2", "B3"])
        XCTAssertEqual(q.playOrder.filter { before.contains($0) }, before, "the rest keeps its order")
        // A second Play Next goes before the first.
        q.playNext(group: F.album("C"), tracks: F.tracks("C", 1), using: &rng)
        XCTAssertEqual(Array(played(q)[(ci + 1)...(ci + 4)]), ["C1", "B1", "B2", "B3"])
        q.setShuffle(false, using: &rng)
        XCTAssertEqual(played(q), ["A1", "A2", "A3", "A4", "A5", "A6", "C1", "B1", "B2", "B3"])
    }

    func testAddWhileShuffledIsDeterministicForSeed() {
        func run() -> [String] {
            var r = SeededRNG(seed: 7)
            var q = PlayQueue()
            q.setShuffle(true, using: &r)
            q.replace(group: F.album("A"), tracks: F.tracks("A", 5), startIndex: 0, using: &r)
            q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 5), using: &r)
            return q.entriesInPlayOrder.map(\.track.id)
        }
        XCTAssertEqual(run(), run())
    }

    // MARK: Move

    func testMoveSingleEntry() {
        var q = albumQueue(4)
        XCTAssertTrue(q.move(entryIds: [entryId(q, track: "A4")], toIndex: 1))
        XCTAssertEqual(natural(q), ["A1", "A4", "A2", "A3"])
        XCTAssertEqual(played(q), natural(q))
        XCTAssertEqual(current(q), "A1")
    }

    func testMoveToSamePlaceIsNoChange() {
        var q = albumQueue(3)
        XCTAssertFalse(q.move(entryIds: [entryId(q, track: "A2")], toIndex: 1))
    }

    func testMoveIndexIsClamped() {
        var q = albumQueue(3)
        q.move(entryIds: [entryId(q, track: "A1")], toIndex: 99)
        XCTAssertEqual(natural(q), ["A2", "A3", "A1"])
        q.move(entryIds: [entryId(q, track: "A1")], toIndex: -5)
        XCTAssertEqual(natural(q), ["A1", "A2", "A3"])
    }

    func testMoveWholeGroupKeepsOrder() {
        var q = albumQueue(2)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 3), using: &rng)
        let cGroup = q.displayGroups[2].entries.map(\.id)
        // Ids given out of order: relative order is the queue's, not the argument's.
        q.move(entryIds: cGroup.reversed(), toIndex: 2)
        XCTAssertEqual(natural(q), ["A1", "A2", "C1", "C2", "C3", "B1", "B2"])
        XCTAssertEqual(q.displayGroups.map(\.group.id), ["A", "C", "B"])
        XCTAssertEqual(current(q), "A1")
    }

    func testMoveCurrentKeepsItCurrent() {
        var q = albumQueue(3, start: 0)
        q.move(entryIds: [q.currentEntryId!], toIndex: 2)
        XCTAssertEqual(natural(q), ["A2", "A3", "A1"])
        XCTAssertEqual(current(q), "A1")
        XCTAssertNil(q.next())
    }

    func testMoveWhileShuffledReordersPlayOrderOnly() {
        var q = albumQueue(5)
        q.setShuffle(true, using: &rng)
        let naturalBefore = natural(q)
        let last = q.playOrder.last!
        q.move(entryIds: [last], toIndex: 1)
        XCTAssertEqual(q.playOrder[1], last)
        XCTAssertEqual(natural(q), naturalBefore)
    }

    func testMoveUnknownIdsIgnored() {
        var q = albumQueue(3)
        XCTAssertFalse(q.move(entryIds: ["nope"], toIndex: 0))
    }

    // MARK: Remove

    func testRemoveUpcoming() {
        var q = albumQueue(3)
        XCTAssertEqual(q.remove(entryIds: [entryId(q, track: "A2")]), .unchanged)
        XCTAssertEqual(natural(q), ["A1", "A3"])
        XCTAssertEqual(played(q), ["A1", "A3"])
    }

    func testRemoveCurrentMovesToNext() {
        var q = albumQueue(3, start: 1)
        XCTAssertEqual(q.remove(entryIds: [q.currentEntryId!]), .moved)
        XCTAssertEqual(current(q), "A3")
    }

    func testRemoveCurrentSkipsUnplayable() {
        var q = albumQueue(4, start: 0)
        let change = q.remove(entryIds: [q.currentEntryId!]) { $0.track.id != "A2" }
        XCTAssertEqual(change, .moved)
        XCTAssertEqual(current(q), "A3")
    }

    func testRemoveCurrentAndFollowingTogether() {
        var q = albumQueue(4, start: 1)
        q.remove(entryIds: [entryId(q, track: "A2"), entryId(q, track: "A3")])
        XCTAssertEqual(current(q), "A4")
    }

    func testRemoveCurrentAtEndEndsAtFirst() {
        var q = albumQueue(3, start: 2)
        XCTAssertEqual(q.remove(entryIds: [q.currentEntryId!]), .ended)
        XCTAssertEqual(current(q), "A1")
        XCTAssertEqual(natural(q), ["A1", "A2"])
    }

    func testRemoveCurrentAtEndWithRepeatAllWraps() {
        var q = albumQueue(3, start: 2)
        q.repeatMode = .all
        XCTAssertEqual(q.remove(entryIds: [q.currentEntryId!]), .moved)
        XCTAssertEqual(current(q), "A1")
    }

    func testRemoveEverything() {
        var q = albumQueue(2)
        XCTAssertEqual(q.remove(entryIds: q.entries.map(\.id)), .ended)
        XCTAssertTrue(q.isEmpty)
        XCTAssertNil(q.currentEntryId)
        XCTAssertTrue(q.playOrder.isEmpty)
    }

    func testRemoveWholeGroupWhileShuffled() {
        var q = albumQueue(3)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 3), using: &rng)
        q.setShuffle(true, using: &rng)
        let bIds = q.entries.filter { $0.group.id == "B" }.map(\.id)
        q.remove(entryIds: bIds)
        XCTAssertEqual(natural(q), ["A1", "A2", "A3"])
        XCTAssertEqual(Set(q.playOrder), Set(q.entries.map(\.id)))
        XCTAssertEqual(current(q), "A1")
    }

    // MARK: Clear

    func testClearUpcomingKeepsOnlyCurrent() {
        var q = albumQueue(3, start: 1)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        q.clearUpcoming()
        XCTAssertEqual(natural(q), ["A2"])
        XCTAssertEqual(played(q), ["A2"])
        XCTAssertEqual(current(q), "A2")
        XCTAssertNil(q.next())
    }

    // MARK: Skip, next, previous

    func testSkipTo() {
        var q = albumQueue(3)
        XCTAssertTrue(q.skipTo(entryId: entryId(q, track: "A3")))
        XCTAssertEqual(current(q), "A3")
        XCTAssertFalse(q.skipTo(entryId: "nope"))
        XCTAssertEqual(current(q), "A3")
    }

    func testNextAndPreviousAcrossGroups() {
        var q = albumQueue(2, start: 1)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertEqual(q.next(trackEnded: true), entryId(q, track: "B1"))
        XCTAssertEqual(q.previous(), entryId(q, track: "A2"))
    }

    func testNextAtEndWithRepeatOff() {
        var q = albumQueue(2, start: 1)
        XCTAssertNil(q.next(trackEnded: true))
        XCTAssertEqual(current(q), "A2", "current unchanged at the end")
        XCTAssertNil(albumQueue(2, start: 0).previousResult())
    }

    func testRepeatAllWrapsWholeQueue() {
        var q = albumQueue(2, start: 0)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1), using: &rng)
        q.repeatMode = .all
        q.next(); q.next()
        XCTAssertEqual(current(q), "B1")
        XCTAssertEqual(q.next(trackEnded: true), entryId(q, track: "A1"))
        XCTAssertEqual(q.previous(), entryId(q, track: "B1"))
    }

    func testRepeatAllSingleEntryRepeatsIt() {
        var q = albumQueue(1)
        q.repeatMode = .all
        XCTAssertEqual(q.next(), q.currentEntryId)
        XCTAssertEqual(q.previous(), q.currentEntryId)
    }

    func testRepeatOneKeepsTrackAtTrackEndOnly() {
        var q = albumQueue(3, start: 0)
        q.repeatMode = .one
        XCTAssertEqual(q.next(trackEnded: true), entryId(q, track: "A1"))
        XCTAssertEqual(q.next(), entryId(q, track: "A2"), "a user skip moves on")
        q.skipTo(entryId: entryId(q, track: "A3"))
        XCTAssertNil(q.next(), "Repeat One doesn't wrap a user skip")
    }

    func testNextSkipsUnplayable() {
        var q = albumQueue(4, start: 0)
        XCTAssertEqual(q.next { !["A2", "A3"].contains($0.track.id) }, entryId(q, track: "A4"))
        XCTAssertNil(q.next { $0.track.id != "A4" && $0.track.id != "A1" })
        XCTAssertEqual(q.previous { $0.track.id == "A1" }, entryId(q, track: "A1"))
    }

    func testNextFollowsShuffledOrder() {
        var q = albumQueue(6)
        q.setShuffle(true, using: &rng)
        var seen = [q.currentEntryId!]
        while let id = q.next(trackEnded: true) { seen.append(id) }
        XCTAssertEqual(seen, q.playOrder)
    }

    // MARK: Shuffle

    func testShuffleRoundTrip() {
        var q = albumQueue(8, start: 3)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 4), using: &rng)
        let naturalOrder = natural(q)
        q.setShuffle(true, using: &rng)
        XCTAssertEqual(q.playOrder.first, q.currentEntryId)
        XCTAssertEqual(current(q), "A4")
        XCTAssertEqual(Set(q.playOrder), Set(q.entries.map(\.id)))
        XCTAssertNotEqual(played(q), naturalOrder)
        XCTAssertEqual(natural(q), naturalOrder)
        q.next(); q.next()
        let currentNow = q.currentEntryId
        q.setShuffle(false, using: &rng)
        XCTAssertEqual(played(q), naturalOrder)
        XCTAssertEqual(q.currentEntryId, currentNow)
    }

    func testShuffleSameValueDoesNotReshuffle() {
        var q = albumQueue(8)
        q.setShuffle(true, using: &rng)
        let order = q.playOrder
        q.setShuffle(true, using: &rng)
        XCTAssertEqual(q.playOrder, order)
    }

    func testShuffleIsDeterministicForSeed() {
        var r1 = SeededRNG(seed: 1), r2 = SeededRNG(seed: 1)
        var a = albumQueue(10), b = a
        a.setShuffle(true, using: &r1)
        b.setShuffle(true, using: &r2)
        XCTAssertEqual(a.playOrder, b.playOrder)
    }

    // MARK: Derived

    func testUpcomingEntries() {
        var q = albumQueue(3, start: 1)
        XCTAssertEqual(q.upcomingEntries.map(\.track.id), ["A3"])
        q.repeatMode = .all
        XCTAssertEqual(q.upcomingEntries.map(\.track.id), ["A3", "A1"])
    }

    func testDisplayGroupsWhileShuffledFollowPlayOrder() {
        var q = albumQueue(3)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 3), using: &rng)
        q.setShuffle(true, using: &rng)
        let flat = q.displayGroups.flatMap(\.entries).map(\.id)
        XCTAssertEqual(flat, q.playOrder)
        for g in q.displayGroups {
            XCTAssertTrue(g.entries.allSatisfy { $0.group == g.group && $0.batchId == g.batchId })
        }
    }

    func testRemainingTimes() {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 3, seconds: 200), startIndex: 1, using: &rng)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2, seconds: 300), using: &rng)
        // Current A2 at 50 s: 150 left, + A3 200.
        XCTAssertEqual(q.currentGroupRemaining(position: 50), 350)
        XCTAssertEqual(q.queueRemaining(position: 50), 950)
        // A position past the end counts as 0 left of the track.
        XCTAssertEqual(q.currentGroupRemaining(position: 999), 200)
        q.skipTo(entryId: entryId(q, track: "B2"))
        XCTAssertEqual(q.currentGroupRemaining(position: 100), 200)
        XCTAssertEqual(q.queueRemaining(position: 100), 200)
        XCTAssertEqual(q.displayGroups.map(\.durationSeconds), [600, 600])
    }

    func testRemainingWhileShuffledUsesPlayOrder() {
        var q = albumQueue(4)
        q.setShuffle(true, using: &rng)
        XCTAssertEqual(q.queueRemaining(position: 0), 400)
        // Every A entry is one batch; the run in play order is the whole queue.
        XCTAssertEqual(q.currentGroupRemaining(position: 0), 400)
        q.skipTo(entryId: q.playOrder.last!)
        XCTAssertEqual(q.queueRemaining(position: 30), 70)
    }

    // MARK: Maniac Mode

    func testManiacRules() {
        for edit in QueueEdit.allCases {
            XCTAssertTrue(PlayQueue.canEdit(edit, maniacMode: false))
        }
        let allowed = QueueEdit.allCases.filter { PlayQueue.canEdit($0, maniacMode: true) }
        XCTAssertEqual(allowed, [.replace, .playNext, .addToQueue])
    }

    // MARK: Track snapshot

    func testQueueTrackFromPlaybackTrack() {
        var pt = PlaybackTrack(id: "t", trackNumber: 4, title: "T", artist: "Ar",
                               album: "Al", albumId: "al", duration: 123)
        pt.albumArtistId = "ar"
        pt.sampleRate = 44100
        pt.codec = "alac"
        let t = QueueTrack(playbackTrack: pt)
        XCTAssertEqual(t.id, "t")
        XCTAssertEqual(t.artItemId, "al")
        XCTAssertEqual(t.artistId, "ar")
        XCTAssertEqual(t.durationSeconds, 123)
        XCTAssertEqual(t.trackNumber, 4)
        XCTAssertEqual(t.sampleRate, 44100)
        XCTAssertEqual(t.codec, "alac")
    }
}

private extension PlayQueue {
    func previousResult() -> String? {
        var copy = self
        return copy.previous()
    }
}
