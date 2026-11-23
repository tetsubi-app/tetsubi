import XCTest
@testable import JellyfinMusicCore

/// The queue UI's pure parts: when Play asks, the end-times text, the
/// queue screen's rows and moves, and Maniac Mode's read-only rule.
final class QueueEditingTests: XCTestCase {
    typealias F = QueueFixtures
    var rng = SeededRNG(seed: 7)

    /// A1–A3 (current A1), then B1–B2 and C1–C2 added to the queue.
    private func threeAlbums() -> PlayQueue {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 3), using: &rng)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 2), using: &rng)
        return q
    }

    private func played(_ q: PlayQueue) -> [String] { q.entriesInPlayOrder.map(\.track.id) }
    private func id(_ q: PlayQueue, _ track: String) -> String { q.entries.first { $0.track.id == track }!.id }

    private func apply(_ move: QueueEditor.Move?, to q: inout PlayQueue) {
        guard let move else { return XCTFail("no move") }
        XCTAssertTrue(q.move(entryIds: move.entryIds, toIndex: move.toIndex))
    }

    /// Row index of a track's row, or of the header above `track`'s group.
    private func row(_ e: QueueEditor, track: String) -> Int {
        e.rows.firstIndex { if case .entry(let x, _) = $0 { x.track.id == track } else { false } }!
    }
    private func header(_ e: QueueEditor, track: String) -> Int {
        e.rows.firstIndex {
            if case .header(let i, _) = $0 { e.groups[i].entries.contains { $0.track.id == track } } else { false }
        }!
    }

    // MARK: The Play prompt

    func testPromptOnlyWithAnotherBatchUpcoming() {
        XCTAssertFalse(PlayQueue().needsPlayPrompt)
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 3), using: &rng)
        XCTAssertFalse(q.needsPlayPrompt, "only the current album")
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertTrue(q.needsPlayPrompt)
        q.skipTo(entryId: id(q, "A3"))
        XCTAssertTrue(q.needsPlayPrompt, "B still to come")
        q.skipTo(entryId: id(q, "B1"))
        XCTAssertFalse(q.needsPlayPrompt, "A was played, B is the current group")
        q.repeatMode = .all
        XCTAssertTrue(q.needsPlayPrompt, "Repeat All brings A round again")
    }

    func testPromptWhileShuffled() {
        var q = threeAlbums()
        q.setShuffle(true, using: &rng)
        XCTAssertTrue(q.needsPlayPrompt)
    }

    // MARK: End times

    func testEndTimesText() {
        XCTAssertEqual(QueueEndTimes.variants(groupLabel: "Album ends", groupEnd: "13:02", queueEnd: "15:40",
                                              moreQueued: true),
                       ["Album ends 13:02 · Queue ends 15:40", "Queue ends 15:40"])
        XCTAssertEqual(QueueEndTimes.variants(groupLabel: "Album ends", groupEnd: "13:02", queueEnd: "13:02",
                                              moreQueued: false),
                       ["Ends: 13:02"])
        XCTAssertEqual(QueueEndTimes.groupLabel(kind: .album, count: 9), "Album ends")
        XCTAssertEqual(QueueEndTimes.groupLabel(kind: .playlist, count: 9), "Playlist ends")
        XCTAssertEqual(QueueEndTimes.groupLabel(kind: .tracks, count: 1), "Song ends")
        XCTAssertEqual(QueueEndTimes.groupLabel(kind: .tracks, count: 2), "Songs end")
    }

    func testMoreThanCurrentGroup() {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 3), using: &rng)
        XCTAssertFalse(q.hasMoreThanCurrentGroup)
        XCTAssertEqual(q.currentGroupCount, 3)
        q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &rng)
        XCTAssertTrue(q.hasMoreThanCurrentGroup)
        q.skipTo(entryId: id(q, "A2"))
        XCTAssertEqual(q.currentGroupCount, 2)
        q.skipTo(entryId: id(q, "B1"))
        XCTAssertFalse(q.hasMoreThanCurrentGroup, "nothing after B")
        q.skipTo(entryId: id(q, "A1"))
        q.setShuffle(true, using: &rng)
        XCTAssertFalse(q.hasMoreThanCurrentGroup, "shuffled: only the queue's end")
    }

    // MARK: Rows

    func testRowsGroupUpcomingUnderHeaders() {
        let e = QueueEditor(queue: threeAlbums())
        XCTAssertEqual(e.current?.track.id, "A1")
        XCTAssertEqual(e.upcoming.map(\.track.id), ["A2", "A3", "B1", "B2", "C1", "C2"])
        XCTAssertEqual(e.groups.map { $0.entries.map(\.track.id) }, [["A2", "A3"], ["B1", "B2"], ["C1", "C2"]])
        XCTAssertEqual(e.groups.map(\.start), [0, 2, 4])
        XCTAssertEqual(e.rows.count, 9)
        XCTAssertEqual(e.groups[1].durationSeconds, 200)
        XCTAssertEqual(e.entryIds(ofRow: header(e, track: "B1")).count, 2)
        XCTAssertEqual(e.entryIds(ofRow: row(e, track: "C2")), [e.upcoming[5].id])
        XCTAssertEqual(Set(e.rows.map(\.id)).count, 9, "row ids are unique")
    }

    func testRowsFlatWhileShuffled() {
        var q = threeAlbums()
        q.setShuffle(true, using: &rng)
        let e = QueueEditor(queue: q)
        XCTAssertTrue(e.isShuffled)
        XCTAssertTrue(e.groups.isEmpty)
        XCTAssertEqual(e.rows.count, 6)
        XCTAssertEqual(e.upcoming.map(\.id), Array(q.playOrder.dropFirst()))
    }

    func testEmptyUpcoming() {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 2), using: &rng)
        q.skipTo(entryId: id(q, "A2"))
        let e = QueueEditor(queue: q)
        XCTAssertTrue(e.isEmpty)
        XCTAssertTrue(e.rows.isEmpty)
        XCTAssertNil(QueueEditor(queue: PlayQueue()).current)
    }

    // MARK: Drag

    func testDragTrackToTheEnd() {
        var q = threeAlbums()
        let e = QueueEditor(queue: q)
        apply(e.move(fromRows: [row(e, track: "A2")], toRow: e.rows.count), to: &q)
        XCTAssertEqual(played(q), ["A1", "A3", "B1", "B2", "C1", "C2", "A2"])
        XCTAssertEqual(q.currentEntry?.track.id, "A1")
    }

    func testDragTrackUpIntoAnotherGroup() {
        var q = threeAlbums()
        let e = QueueEditor(queue: q)
        // C2 dropped between B1 and B2.
        apply(e.move(fromRows: [row(e, track: "C2")], toRow: row(e, track: "B2")), to: &q)
        XCTAssertEqual(played(q), ["A1", "A2", "A3", "B1", "C2", "B2", "C1"])
    }

    func testDragGroupToTheTop() {
        var q = threeAlbums()
        let e = QueueEditor(queue: q)
        apply(e.move(fromRows: [header(e, track: "C1")], toRow: 0), to: &q)
        XCTAssertEqual(played(q), ["A1", "C1", "C2", "A2", "A3", "B1", "B2"], "the playing track stays first")
    }

    func testDragGroupDownIntoAGroupLandsAfterIt() {
        var q = threeAlbums()
        let e = QueueEditor(queue: q)
        // B's header dropped between C1 and C2.
        apply(e.move(fromRows: [header(e, track: "B1")], toRow: row(e, track: "C2")), to: &q)
        XCTAssertEqual(played(q), ["A1", "A2", "A3", "C1", "C2", "B1", "B2"])
    }

    func testDragGroupUpIntoAGroupLandsBeforeIt() {
        var q = threeAlbums()
        let e = QueueEditor(queue: q)
        // C's header dropped between A2 and A3.
        apply(e.move(fromRows: [header(e, track: "C1")], toRow: row(e, track: "A3")), to: &q)
        XCTAssertEqual(played(q), ["A1", "C1", "C2", "A2", "A3", "B1", "B2"])
    }

    func testDragToWhereItIsDoesNothing() {
        let e = QueueEditor(queue: threeAlbums())
        let r = row(e, track: "B1")
        XCTAssertNil(e.move(fromRows: [r], toRow: r))
        XCTAssertNil(e.move(fromRows: [r], toRow: r + 1))
        let h = header(e, track: "B1")
        XCTAssertNil(e.move(fromRows: [h], toRow: h))
        XCTAssertNil(e.move(fromRows: [], toRow: 0))
    }

    func testDragCountsFromTheCurrentEntry() {
        var q = threeAlbums()
        q.skipTo(entryId: id(q, "B1"))
        let e = QueueEditor(queue: q)
        XCTAssertEqual(e.upcoming.map(\.track.id), ["B2", "C1", "C2"])
        apply(e.move(fromRows: [header(e, track: "C1")], toRow: 0), to: &q)
        XCTAssertEqual(played(q), ["A1", "A2", "A3", "B1", "C1", "C2", "B2"])
    }

    func testDragWhileShuffledMovesThePlayOrder() {
        var q = threeAlbums()
        q.setShuffle(true, using: &rng)
        let natural = q.entries.map(\.track.id)
        let e = QueueEditor(queue: q)
        let last = e.upcoming.last!.track.id
        apply(e.move(fromRows: [e.rows.count - 1], toRow: 0), to: &q)
        XCTAssertEqual(played(q)[1], last)
        XCTAssertEqual(q.entries.map(\.track.id), natural, "the queue order to go back to is kept")
    }

    // MARK: Move up / down (VoiceOver)

    func testMoveUpAndDownTracks() {
        var q = threeAlbums()
        var e = QueueEditor(queue: q)
        XCTAssertNil(e.moveUp(row: row(e, track: "A2")), "can't go above the playing track")
        XCTAssertNil(e.moveDown(row: row(e, track: "C2")))
        apply(e.moveDown(row: row(e, track: "A2")), to: &q)
        XCTAssertEqual(played(q), ["A1", "A3", "A2", "B1", "B2", "C1", "C2"])
        e = QueueEditor(queue: q)
        apply(e.moveUp(row: row(e, track: "B1")), to: &q)
        XCTAssertEqual(played(q), ["A1", "A3", "B1", "A2", "B2", "C1", "C2"])
    }

    func testMoveUpAndDownGroups() {
        var q = threeAlbums()
        var e = QueueEditor(queue: q)
        XCTAssertNil(e.moveUp(row: header(e, track: "A2")))
        XCTAssertNil(e.moveDown(row: header(e, track: "C1")))
        apply(e.moveDown(row: header(e, track: "A2")), to: &q)
        XCTAssertEqual(played(q), ["A1", "B1", "B2", "A2", "A3", "C1", "C2"])
        e = QueueEditor(queue: q)
        apply(e.moveUp(row: header(e, track: "C1")), to: &q)
        XCTAssertEqual(played(q), ["A1", "B1", "B2", "C1", "C2", "A2", "A3"])
    }

    // MARK: Maniac Mode

    func testManiacModeIsReadOnly() {
        XCTAssertTrue(QueueEditor.isReadOnly(maniacMode: true))
        XCTAssertFalse(QueueEditor.isReadOnly(maniacMode: false))
        // Adding is still allowed; editing is not.
        XCTAssertTrue(PlayQueue.canEdit(.playNext, maniacMode: true))
        XCTAssertTrue(PlayQueue.canEdit(.addToQueue, maniacMode: true))
        for edit in [QueueEdit.move, .remove, .clearUpcoming, .skipTo] {
            XCTAssertFalse(PlayQueue.canEdit(edit, maniacMode: true), edit.rawValue)
        }
    }

    // MARK: Wording

    func testWording() {
        XCTAssertEqual(QueueActions.confirmation(.playNext), "Playing next")
        XCTAssertEqual(QueueActions.confirmation(.addToQueue), "Added to queue")
        XCTAssertEqual(QueuePrompt.title("Blue"), "Play \u{201C}Blue\u{201D}")
    }
}
