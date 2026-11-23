import XCTest
@testable import JellyfinMusicCore

/// Play Next / Add to Queue apply in tap order whatever order their
/// tracks arrive in.
@MainActor
final class QueueAddOrderTests: XCTestCase {
    typealias F = QueueFixtures
    var rng = SeededRNG(seed: 3)

    private func queueWithA() -> PlayQueue {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: F.tracks("A", 2), using: &rng)
        return q
    }

    func testFetchesFinishingInReverseLandInTapOrder() {
        let order = QueueAddOrder()
        var q = queueWithA()
        let b = order.reserve()
        let c = order.reserve()
        // C's tracks arrive first: it waits for B.
        order.finish(c) { q.addToQueue(group: F.album("C"), tracks: F.tracks("C", 2), using: &self.rng) }
        XCTAssertEqual(q.entries.count, 2)
        order.finish(b) { q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2), using: &self.rng) }
        XCTAssertEqual(q.entries.map(\.track.id), ["A1", "A2", "B1", "B2", "C1", "C2"])
        XCTAssertEqual(order.pendingCount, 0)
    }

    func testPlayNextInReverseKeepsTapOrder() {
        // Play Next B then Play Next C: C goes before B (each after the
        // current album), as when the fetches finish in order.
        let order = QueueAddOrder()
        var q = queueWithA()
        let b = order.reserve()
        let c = order.reserve()
        order.finish(c) { q.playNext(group: F.album("C"), tracks: F.tracks("C", 1), using: &self.rng) }
        order.finish(b) { q.playNext(group: F.album("B"), tracks: F.tracks("B", 1), using: &self.rng) }
        XCTAssertEqual(q.entries.map(\.track.id), ["A1", "A2", "C1", "B1"])
    }

    func testAFailureInTheMiddleDoesNotBlockLaterAdds() {
        let order = QueueAddOrder()
        var applied: [String] = []
        let b = order.reserve()
        let failed = order.reserve()
        let d = order.reserve()
        order.finish(d) { applied.append("D") }
        order.finish(b) { applied.append("B") }
        XCTAssertEqual(applied, ["B"], "D still waits for the middle one")
        order.finish(failed, nil)
        XCTAssertEqual(applied, ["B", "D"])
        XCTAssertEqual(order.pendingCount, 0)
    }

    func testFailureFirstAndImmediateAdds() {
        let order = QueueAddOrder()
        var applied: [String] = []
        let failed = order.reserve()
        order.finish(order.reserve()) { applied.append("B") }
        XCTAssertTrue(applied.isEmpty)
        order.finish(failed, nil)
        XCTAssertEqual(applied, ["B"])
        // Nothing pending: an add applies at once.
        order.finish(order.reserve()) { applied.append("C") }
        XCTAssertEqual(applied, ["B", "C"])
    }

    func testFinishingTwiceOrUnknownIsIgnored() {
        let order = QueueAddOrder()
        var count = 0
        let t = order.reserve()
        order.finish(t) { count += 1 }
        order.finish(t) { count += 1 }
        order.finish(99) { count += 1 }
        XCTAssertEqual(count, 1)
    }

    // MARK: Play (replace) cancels pending adds

    func testAddPendingAtReplaceNeverLandsAndAnAddAfterItDoes() {
        let order = QueueAddOrder()
        var q = queueWithA()
        let early = order.reserve()                       // Add to Queue B, still fetching
        let play = order.replace()                        // Play C: B is dropped
        let late = order.reserve()                        // Add to Queue D, tapped after Play
        XCTAssertFalse(order.isPending(early))
        // D's tracks arrive before C's: D waits for the replace.
        XCTAssertTrue(order.finish(late) { q.addToQueue(group: F.album("D"), tracks: F.tracks("D", 1), using: &self.rng) })
        XCTAssertEqual(q.entries.map(\.track.id), ["A1", "A2"])
        XCTAssertTrue(order.finish(play) { q.replace(group: F.album("C"), tracks: F.tracks("C", 2), using: &self.rng) })
        XCTAssertEqual(q.entries.map(\.track.id), ["C1", "C2", "D1"])
        // B's tracks arrive last: ignored, and reported as not live (no notice).
        XCTAssertFalse(order.finish(early) { q.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1), using: &self.rng) })
        XCTAssertFalse(order.finish(early, nil), "a failed cancelled add shows no notice either")
        XCTAssertEqual(q.entries.map(\.track.id), ["C1", "C2", "D1"])
        XCTAssertEqual(order.pendingCount, 0)
    }

    func testALaterPlaySupersedesAnEarlierOne() {
        let order = QueueAddOrder()
        var played: [String] = []
        let first = order.replace()
        let second = order.replace()
        XCTAssertTrue(order.finish(second) { played.append("second") })
        XCTAssertFalse(order.finish(first) { played.append("first") })
        XCTAssertEqual(played, ["second"])
    }
}
