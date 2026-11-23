import XCTest
@testable import JellyfinMusicCore

/// An engine that only remembers what it was told, standing in for the
/// AVQueuePlayer (as Kotlin's `FakeEngine`).
@MainActor
final class FakeQueueEngine: QueueEngine {
    enum Call: Equatable {
        case load([String], position: Double, play: Bool)
        case replaceAfterCurrent([String])
    }
    var calls: [Call] = []
    /// What the engine holds, playing entry first.
    var held: [String] = []
    var playing = false

    func load(entryIds: [String], position: Double, play: Bool) {
        calls.append(.load(entryIds, position: position, play: play))
        held = entryIds
        playing = play && !entryIds.isEmpty
    }

    func replaceAfterCurrent(_ entryIds: [String]) {
        calls.append(.replaceAfterCurrent(entryIds))
        held = Array(held.prefix(1)) + entryIds
    }

    /// The playing entry ends and the engine goes on to the next it holds
    /// (gapless), as the AVQueuePlayer does. Returns that entry.
    func advance() -> String? {
        guard !held.isEmpty else { return nil }
        held.removeFirst()
        return held.first
    }
}

/// The queue driving an engine: what the engine is told for each operation.
@MainActor
final class PlayQueueControllerTests: XCTestCase {
    typealias F = QueueFixtures

    private var engine: FakeQueueEngine!
    private var controller: PlayQueueController!
    private var saved: SavedSnapshots!
    private var saver: PlayQueueSaver!

    /// Snapshots the saver wrote (thread-safe; the saver writes on its queue).
    final class SavedSnapshots: @unchecked Sendable {
        private let lock = NSLock()
        private var list: [PlayQueueSnapshot] = []
        func append(_ s: PlayQueueSnapshot) { lock.lock(); list.append(s); lock.unlock() }
        var all: [PlayQueueSnapshot] { lock.lock(); defer { lock.unlock() }; return list }
    }

    override func setUp() async throws {
        engine = FakeQueueEngine()
        controller = PlayQueueController()
        controller.engine = engine
        controller.rng = SeededRNG(seed: 7)
        let saved = SavedSnapshots()
        self.saved = saved
        // A long interval: writes happen only on flush, so tests are exact.
        saver = PlayQueueSaver(interval: 60) { saved.append($0) }
        controller.saver = saver
        controller.serverId = "server"
        controller.userId = "user"
    }

    // MARK: Helpers

    private var queue: PlayQueue { controller.queue }
    private func ids(_ tracks: [String]) -> [String] { tracks.map { entryId($0) } }
    private func entryId(_ track: String) -> String { queue.entries.first { $0.track.id == track }!.id }
    private func trackIds(_ entryIds: [String]) -> [String] {
        entryIds.map { id in queue.entry(id: id)!.track.id }
    }
    private var heldTracks: [String] { trackIds(engine.held) }
    private var current: String? { queue.currentEntry?.track.id }

    private func playAlbumA(_ n: Int = 3, start: Int? = nil) {
        XCTAssertEqual(controller.replace(group: F.album("A"), tracks: F.tracks("A", n), startIndex: start),
                       .started(play: true))
    }

    // MARK: Replace

    func testPlayingAnAlbumLoadsItFromTheStart() {
        playAlbumA()
        XCTAssertEqual(engine.calls.count, 1)
        XCTAssertEqual(heldTracks, ["A1", "A2", "A3"])
        XCTAssertTrue(engine.playing)
        XCTAssertEqual(current, "A1")
    }

    func testPlayingFromATrackHoldsItAndWhatFollows() {
        playAlbumA(4, start: 2)
        XCTAssertEqual(heldTracks, ["A3", "A4"])
        XCTAssertEqual(current, "A3")
    }

    func testReplacingEmptyEmpties() {
        playAlbumA()
        XCTAssertEqual(controller.replace(group: F.album("B"), tracks: []), .emptied)
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(engine.held, [])
    }

    // MARK: Play Next / Add to Queue

    func testPlayNextGoesAfterTheAlbumWithoutInterrupting() {
        playAlbumA()
        engine.calls = []
        XCTAssertEqual(controller.playNext(group: F.album("B"), tracks: F.tracks("B", 2)), .upcomingChanged)
        XCTAssertEqual(engine.calls.count, 1)
        guard case .replaceAfterCurrent(let upcoming) = engine.calls[0] else {
            return XCTFail("expected replaceAfterCurrent")
        }
        XCTAssertEqual(trackIds(upcoming), ["A2", "A3", "B1", "B2"])
        XCTAssertEqual(current, "A1")
    }

    func testAddToQueueGoesAtTheEnd() {
        playAlbumA(2)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1))
        controller.playNext(group: F.album("C"), tracks: F.tracks("C", 1))
        XCTAssertEqual(heldTracks, ["A1", "A2", "C1", "B1"])
        XCTAssertFalse(engine.calls.dropFirst().contains { if case .load = $0 { true } else { false } },
                       "the playing track is never reloaded")
    }

    func testAddingToAnEmptyQueuePlays() {
        XCTAssertEqual(controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2)), .started(play: true))
        XCTAssertEqual(heldTracks, ["B1", "B2"])
        XCTAssertTrue(engine.playing)
    }

    func testAddingNothingDoesNothing() {
        playAlbumA()
        engine.calls = []
        XCTAssertEqual(controller.addToQueue(group: F.album("B"), tracks: []), .none)
        XCTAssertEqual(engine.calls, [])
    }

    func testRegisterSeesTheNewEntriesBeforeTheEngine() {
        playAlbumA(1)
        var registered: [String] = []
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2)) { entries in
            registered = entries.map(\.track.id)
            XCTAssertEqual(self.engine.calls.count, 1, "the engine hasn't been told yet")
        }
        XCTAssertEqual(registered, ["B1", "B2"])
    }

    // MARK: Gapless across albums

    func testTheEngineMovingIntoTheNextAlbumUpdatesTheCurrentEntry() {
        playAlbumA(1)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        engine.calls = []
        let next = engine.advance()
        XCTAssertEqual(controller.trackEnded(engineNext: next), .advanced)
        XCTAssertEqual(current, "B1")
        XCTAssertEqual(queue.currentEntry?.group.id, "B", "the album playing is now B")
        XCTAssertEqual(engine.calls, [], "nothing reloaded: the join stays gapless")
    }

    func testTheEndOfTheQueueStopsAtTheFirstEntry() {
        playAlbumA(2)
        XCTAssertEqual(controller.trackEnded(engineNext: engine.advance()), .advanced)
        XCTAssertEqual(controller.trackEnded(engineNext: engine.advance()), .ended)
        XCTAssertEqual(current, "A1")
        XCTAssertEqual(heldTracks, ["A1", "A2"])
        XCTAssertFalse(engine.playing)
    }

    // MARK: Remove

    func testRemovingTheCurrentEntryPlaysTheNext() {
        playAlbumA()
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1))
        XCTAssertEqual(controller.remove(entryIds: [entryId("A1")], playing: true), .started(play: true))
        XCTAssertEqual(current, "A2")
        XCTAssertEqual(heldTracks, ["A2", "A3", "B1"])
        XCTAssertTrue(engine.playing)
    }

    func testRemovingTheLastEntryEnds() {
        playAlbumA(3, start: 2)
        XCTAssertEqual(controller.remove(entryIds: [entryId("A3")], playing: true), .ended)
        XCTAssertEqual(current, "A1")
        XCTAssertFalse(engine.playing)
        XCTAssertEqual(heldTracks, ["A1", "A2"])
    }

    func testRemovingEverythingEmpties() {
        playAlbumA(2)
        XCTAssertEqual(controller.remove(entryIds: queue.entries.map(\.id), playing: true), .emptied)
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(engine.held, [])
        saver.flush()
        XCTAssertEqual(saved.all.last?.entries, [], "an empty queue is written, which removes the saved one")
    }

    func testReplacingWithNothingRemovesTheSavedQueue() {
        playAlbumA(2)
        XCTAssertEqual(controller.replace(group: F.album("B"), tracks: []), .emptied)
        saver.flush()
        XCTAssertEqual(saved.all.last?.entries, [])
    }

    func testAnEmptyQueueThatWasNeverLoadedLeavesTheSavedOneAlone() {
        controller.scheduleSave()
        controller.flushSave()
        XCTAssertEqual(saved.all.count, 0)
    }

    func testRemovingUpcomingOnlyChangesWhatFollows() {
        playAlbumA()
        engine.calls = []
        XCTAssertEqual(controller.remove(entryIds: [entryId("A2")], playing: true), .upcomingChanged)
        XCTAssertEqual(heldTracks, ["A1", "A3"])
    }

    // MARK: Move, clear, skip

    func testMovingChangesWhatFollows() {
        playAlbumA()
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        XCTAssertEqual(controller.move(entryIds: ids(["B1", "B2"]), toIndex: 1), .upcomingChanged)
        XCTAssertEqual(heldTracks, ["A1", "B1", "B2", "A2", "A3"])
        XCTAssertEqual(current, "A1")
    }

    func testClearUpcomingKeepsThePlayingTrack() {
        playAlbumA()
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        XCTAssertEqual(controller.clearUpcoming(), .upcomingChanged)
        XCTAssertEqual(heldTracks, ["A1"])
        XCTAssertEqual(queue.entries.count, 1)
    }

    func testSkipToPlaysThatEntry() {
        playAlbumA()
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        XCTAssertEqual(controller.skipTo(entryId: entryId("B1")), .started(play: true))
        XCTAssertEqual(heldTracks, ["B1", "B2"])
        XCTAssertEqual(controller.skipTo(entryId: "unknown"), .none)
    }

    // MARK: Next / previous

    func testNextAndPreviousLoad() {
        playAlbumA()
        XCTAssertEqual(controller.next(), .started(play: true))
        XCTAssertEqual(heldTracks, ["A2", "A3"])
        XCTAssertEqual(controller.previous(), .started(play: true))
        XCTAssertEqual(heldTracks, ["A1", "A2", "A3"])
        XCTAssertEqual(controller.previous(), .none, "nothing before the first track")
    }

    func testNextSkipsWhatCantPlay() {
        playAlbumA()
        let a2 = entryId("A2")
        controller.isPlayable = { $0.id != a2 }
        controller.next()
        XCTAssertEqual(current, "A3")
    }

    func testTheEngineGoingOnToAnEntryThatCantPlayLoadsTheNextOne() {
        playAlbumA()
        let a2 = entryId("A2")
        controller.isPlayable = { $0.id != a2 }
        XCTAssertEqual(controller.trackEnded(engineNext: engine.advance()), .started(play: true))
        XCTAssertEqual(heldTracks, ["A3"])
    }

    // MARK: Shuffle

    func testShuffleAcrossAlbumsKeepsThePlayingTrack() {
        playAlbumA(4)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 4))
        engine.calls = []
        XCTAssertEqual(controller.setShuffle(true), .upcomingChanged)
        XCTAssertEqual(current, "A1")
        XCTAssertEqual(heldTracks.first, "A1")
        XCTAssertEqual(Set(heldTracks), Set(F.tracks("A", 4).map(\.id) + F.tracks("B", 4).map(\.id)),
                       "every track of both albums is still there")
        XCTAssertNotEqual(heldTracks, ["A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4"])
        XCTAssertFalse(engine.calls.contains { if case .load = $0 { true } else { false } })

        XCTAssertEqual(controller.setShuffle(false), .upcomingChanged)
        XCTAssertEqual(heldTracks, ["A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4"])
    }

    func testShuffleOffWithAnotherTrackPlayingKeepsIt() {
        playAlbumA(3)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        controller.setShuffle(true)
        controller.skipTo(entryId: entryId("B1"))
        controller.setShuffle(false)
        XCTAssertEqual(current, "B1")
        XCTAssertEqual(heldTracks, ["B1", "B2"], "back in queue order from the playing track")
    }

    func testPlayingWithShuffleOnShufflesTheNewQueue() {
        controller.setShuffle(true)
        controller.replace(group: F.album("A"), tracks: F.tracks("A", 6), startIndex: 3)
        XCTAssertEqual(current, "A4", "the tapped track plays first")
        XCTAssertEqual(Set(heldTracks), Set(F.tracks("A", 6).map(\.id)))
    }

    // MARK: Repeat

    func testRepeatAllWrapsTheWholeQueue() {
        playAlbumA(1)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1))
        controller.setRepeatMode(.all)
        XCTAssertEqual(controller.trackEnded(engineNext: engine.advance()), .advanced)
        XCTAssertEqual(current, "B1")
        // The engine holds nothing after the last entry: going round loads.
        XCTAssertEqual(controller.trackEnded(engineNext: engine.advance()), .started(play: true))
        XCTAssertEqual(current, "A1")
        XCTAssertEqual(heldTracks, ["A1", "B1"])
        XCTAssertEqual(controller.next(), .started(play: true))
        XCTAssertEqual(controller.next(), .started(play: true), "a skip past the end wraps too")
        XCTAssertEqual(current, "A1")
    }

    func testRepeatOnePlaysTheTrackAgain() {
        playAlbumA(2)
        controller.setRepeatMode(.one)
        XCTAssertEqual(controller.trackEnded(engineNext: engine.advance()), .started(play: true))
        XCTAssertEqual(current, "A1")
        XCTAssertEqual(heldTracks, ["A1", "A2"])
        XCTAssertEqual(controller.next(), .started(play: true), "a skip still moves on")
        XCTAssertEqual(current, "A2")
    }

    // MARK: Maniac Mode

    func testManiacModeRefusesEditsButAllowsAdding() {
        playAlbumA()
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1))
        controller.setManiacMode(true)
        engine.calls = []
        let before = queue
        XCTAssertEqual(controller.move(entryIds: ids(["B1"]), toIndex: 0), .none)
        XCTAssertEqual(controller.remove(entryIds: ids(["A2"]), playing: true), .none)
        XCTAssertEqual(controller.clearUpcoming(), .none)
        XCTAssertEqual(controller.skipTo(entryId: entryId("B1")), .none)
        XCTAssertEqual(controller.next(), .none)
        XCTAssertEqual(controller.previous(), .none)
        XCTAssertEqual(controller.setShuffle(true), .none)
        XCTAssertEqual(controller.setRepeatMode(.all), .none)
        XCTAssertEqual(queue, before)
        XCTAssertEqual(engine.calls, [])

        XCTAssertEqual(controller.playNext(group: F.album("C"), tracks: F.tracks("C", 1)), .upcomingChanged)
        XCTAssertEqual(controller.addToQueue(group: F.album("D"), tracks: F.tracks("D", 1)), .upcomingChanged)
        XCTAssertEqual(heldTracks, ["A1", "A2", "A3", "C1", "B1", "D1"])
        // The queue still goes on from album to album.
        controller.trackEnded(engineNext: entryId("A2"))
        controller.trackEnded(engineNext: entryId("A3"))
        XCTAssertEqual(controller.trackEnded(engineNext: entryId("C1")), .advanced)
        XCTAssertEqual(current, "C1")
    }

    func testManiacModeTurnsShuffleAndRepeatOff() {
        playAlbumA(4)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        controller.setShuffle(true)
        controller.setRepeatMode(.all)
        controller.setManiacMode(true)
        XCTAssertFalse(queue.shuffle)
        XCTAssertEqual(queue.repeatMode, .off)
        XCTAssertEqual(heldTracks, ["A1", "A2", "A3", "A4", "B1", "B2"])
        XCTAssertEqual(controller.setShuffle(false), .none, "already off")
    }

    // MARK: Times

    func testQueueRemainingFollowsEdits() {
        controller.replace(group: F.album("A"), tracks: F.tracks("A", 3, seconds: 100))
        XCTAssertEqual(queue.queueRemaining(position: 30), 270)
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2, seconds: 50))
        XCTAssertEqual(queue.queueRemaining(position: 30), 370)
        XCTAssertEqual(queue.currentGroupRemaining(position: 30), 270)
        controller.remove(entryIds: [entryId("A3")], playing: true)
        XCTAssertEqual(queue.queueRemaining(position: 30), 270)
        controller.clearUpcoming()
        XCTAssertEqual(queue.queueRemaining(position: 30), 70)
    }

    // MARK: Saving

    func testEveryChangeIsSaved() {
        playAlbumA()
        controller.position = { 12 }
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 1))
        saver.flush()
        let last = try? XCTUnwrap(saved.all.last)
        XCTAssertEqual(last?.entries.map(\.track.id), ["A1", "A2", "A3", "B1"])
        XCTAssertEqual(last?.positionSeconds, 12)
        XCTAssertEqual(last?.serverId, "server")
        XCTAssertEqual(last?.userId, "user")

        controller.setShuffle(true)
        saver.flush()
        XCTAssertEqual(saved.all.last?.shuffle, true)
        controller.setRepeatMode(.all)
        saver.flush()
        XCTAssertEqual(saved.all.last?.repeatMode, .all)
    }

    func testANewTrackIsSavedAtItsStart() {
        playAlbumA()
        controller.position = { 95 }
        controller.next()
        saver.flush()
        XCTAssertEqual(saved.all.last?.currentEntryId, entryId("A2"))
        XCTAssertEqual(saved.all.last?.positionSeconds, 0, "not the old track's position")
        controller.trackEnded(engineNext: engine.advance())
        saver.flush()
        XCTAssertEqual(saved.all.last?.currentEntryId, entryId("A3"))
        XCTAssertEqual(saved.all.last?.positionSeconds, 0)
    }

    func testRefusedEditsAreNotSaved() {
        playAlbumA()
        saver.flush()
        let count = saved.all.count
        controller.setManiacMode(true)
        saver.flush()
        let afterManiac = saved.all.count
        controller.move(entryIds: ids(["A3"]), toIndex: 0)
        saver.flush()
        XCTAssertEqual(saved.all.count, afterManiac)
        XCTAssertGreaterThanOrEqual(afterManiac, count)
    }

    // MARK: Restore

    func testRestoreComesBackPausedAtTheSameEntryAndPosition() {
        playAlbumA()
        controller.addToQueue(group: F.album("B"), tracks: F.tracks("B", 2))
        controller.setShuffle(true)
        controller.setRepeatMode(.all)
        controller.skipTo(entryId: entryId("B1"))
        let snapshot = queue.snapshot(positionSeconds: 42.5, serverId: "server", userId: "user")

        let other = PlayQueueController()
        let otherEngine = FakeQueueEngine()
        other.engine = otherEngine
        var registered = 0
        XCTAssertEqual(other.restore(snapshot) { registered = $0.count }, 42.5)
        XCTAssertEqual(registered, 5)
        XCTAssertEqual(other.queue.currentEntryId, queue.currentEntryId)
        XCTAssertEqual(other.queue.playOrder, queue.playOrder)
        XCTAssertTrue(other.queue.shuffle)
        XCTAssertEqual(other.queue.repeatMode, .all)
        guard case .load(let held, let position, let play) = otherEngine.calls.first else {
            return XCTFail("expected a load")
        }
        XCTAssertEqual(held.first, queue.currentEntryId)
        XCTAssertEqual(held, [queue.currentEntryId!] + other.engineUpcoming)
        XCTAssertEqual(position, 42.5)
        XCTAssertFalse(play, "restored paused")
    }

    func testRestoreSkipsAnEntryThatCantPlay() {
        playAlbumA()
        let snapshot = queue.snapshot(positionSeconds: 30)
        let a1 = entryId("A1")
        let other = PlayQueueController()
        let otherEngine = FakeQueueEngine()
        other.engine = otherEngine
        other.isPlayable = { $0.id != a1 }
        XCTAssertEqual(other.restore(snapshot), 0, "a different track starts from 0")
        XCTAssertEqual(other.queue.currentEntry?.track.id, "A2")

        let none = PlayQueueController()
        none.isPlayable = { _ in false }
        XCTAssertNil(none.restore(snapshot), "nothing playable: nothing restored")
        XCTAssertTrue(none.queue.isEmpty)
    }

    /// The player knows an entry's file only once the entry is registered
    /// (a downloaded album's tracks can't play any other way).
    func testRestoreRegistersEntriesBeforeAskingWhatCanPlay() {
        playAlbumA()
        let snapshot = queue.snapshot(positionSeconds: 30)
        let other = PlayQueueController()
        let otherEngine = FakeQueueEngine()
        other.engine = otherEngine
        var known: Set<String> = []
        other.isPlayable = { known.contains($0.id) }
        XCTAssertEqual(other.restore(snapshot) { known = Set($0.map(\.id)) }, 30)
        XCTAssertEqual(other.queue.currentEntry?.track.id, "A1", "the saved entry, at its position")
    }

    func testRestoreInManiacModeIsInOrderWithoutRepeat() {
        playAlbumA(4)
        controller.setShuffle(true)
        controller.setRepeatMode(.one)
        let snapshot = queue.snapshot(positionSeconds: 5)
        let other = PlayQueueController()
        other.maniacMode = true
        other.restore(snapshot)
        XCTAssertFalse(other.queue.shuffle)
        XCTAssertEqual(other.queue.repeatMode, .off)
    }

    // MARK: Signing out

    func testSigningOutClearsTheQueueAndTheSavedOne() {
        playAlbumA()
        controller.clear(forget: true)
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(engine.held, [])
        XCTAssertEqual(saved.all.last?.entries, [], "an empty queue is written, which removes the file")
    }

    func testStoppingKeepsTheSavedQueue() {
        playAlbumA()
        controller.clear(forget: false)
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(saved.all.last?.entries.count, 3)
    }
}
