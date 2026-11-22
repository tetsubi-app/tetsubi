import XCTest
@testable import JellyfinMusicCore

final class PlayQueueStoreTests: XCTestCase {
    typealias F = QueueFixtures
    private var tempDir: URL!
    private var file: URL { tempDir.appendingPathComponent("PlayQueue/queue.json") }

    override func setUp() {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("PlayQueueStoreTests-\(UUID().uuidString)")
        PlayQueueStore.fileURLOverride = file
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        PlayQueueStore.fileURLOverride = nil
    }

    private func sampleQueue() -> PlayQueue {
        var rng = SeededRNG(seed: 3)
        var q = PlayQueue()
        var t = F.track("A1")
        t.container = "flac"
        t.normalizationGain = -6.5
        q.replace(group: F.album("A"), tracks: [t] + F.tracks("A", 3).dropFirst(), startIndex: 1, using: &rng)
        q.addToQueue(group: QueueGroup(id: "P", kind: .playlist, title: "Morning"),
                     tracks: [F.track("X", album: "Z"), F.track("A2")], using: &rng)
        q.setShuffle(true, using: &rng)
        q.repeatMode = .all
        return q
    }

    func testRoundTrip() {
        let q = sampleQueue()
        PlayQueueStore.save(q.snapshot(positionSeconds: 42.5))
        let loaded = PlayQueueStore.load()
        XCTAssertEqual(loaded, q.snapshot(positionSeconds: 42.5))
        XCTAssertEqual(loaded.map(PlayQueue.init(snapshot:)), q)
        XCTAssertEqual(loaded?.version, 1)
    }

    func testRoundTripWithServerAndUser() {
        let snap = sampleQueue().snapshot(positionSeconds: 5, serverId: "srv", userId: "usr")
        PlayQueueStore.save(snap)
        XCTAssertEqual(PlayQueueStore.load(serverId: "srv", userId: "usr"), snap)
        XCTAssertEqual(PlayQueueStore.load()?.serverId, "srv")
        XCTAssertEqual(PlayQueueStore.load()?.userId, "usr")
    }

    func testOtherServerOrUserLoadsNil() {
        PlayQueueStore.save(sampleQueue().snapshot(positionSeconds: 5, serverId: "srv", userId: "usr"))
        XCTAssertNil(PlayQueueStore.load(serverId: "other", userId: "usr"))
        XCTAssertNil(PlayQueueStore.load(serverId: "srv", userId: "other"))
    }

    func testSnapshotWithoutServerOrUserLoadsForAnyone() throws {
        PlayQueueStore.save(sampleQueue().snapshot(positionSeconds: 5))
        let data = try Data(contentsOf: file)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNil(json["serverId"])
        XCTAssertNil(json["userId"])
        XCTAssertNotNil(PlayQueueStore.load(serverId: "srv", userId: "usr"))
    }

    func testCodecRoundTripsLowerCased() throws {
        var q = PlayQueue()
        var t = F.track("A1")
        t.codec = "aac"
        q.replace(group: F.album("A"), tracks: [t, QueueTrack(id: "A2", title: "T", artist: "Ar",
            album: "Al", albumId: "A", durationSeconds: 1, codec: "ALAC")])
        PlayQueueStore.save(q.snapshot(positionSeconds: 0))
        let loaded = try XCTUnwrap(PlayQueueStore.load())
        XCTAssertEqual(loaded.entries.map(\.track.codec), ["aac", "alac"])
    }

    func testMissingFileLoadsNil() {
        XCTAssertNil(PlayQueueStore.load())
    }

    func testCorruptFileLoadsNil() throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try Data("{\"version\":1,\"entries\":[{\"id\":".utf8).write(to: file)
        XCTAssertNil(PlayQueueStore.load())
        try Data("not json".utf8).write(to: file)
        XCTAssertNil(PlayQueueStore.load())
    }

    func testOtherVersionLoadsNil() throws {
        var s = sampleQueue().snapshot(positionSeconds: 0)
        s.version = 2
        XCTAssertNil(PlayQueueStore.decode(try PlayQueueStore.encode(s)))
        XCTAssertNil(PlayQueueStore.decode(Data("{\"entries\":[]}".utf8)), "no version")
    }

    func testEmptyQueueRemovesFile() {
        PlayQueueStore.save(sampleQueue().snapshot(positionSeconds: 1))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        PlayQueueStore.save(PlayQueue().snapshot(positionSeconds: 0))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertNil(PlayQueueStore.load())
    }

    func testRepairsBadPlayOrderAndCurrent() throws {
        var s = sampleQueue().snapshot(positionSeconds: -3)
        s.playOrder = Array(s.playOrder.dropLast())
        s.currentEntryId = "gone"
        let repaired = try XCTUnwrap(PlayQueueStore.decode(try PlayQueueStore.encode(s)))
        XCTAssertFalse(repaired.shuffle)
        XCTAssertEqual(repaired.playOrder, repaired.entries.map(\.id))
        XCTAssertEqual(repaired.currentEntryId, repaired.entries.first?.id)
        XCTAssertEqual(repaired.positionSeconds, 0)
    }

    func testRepairsDuplicateEntryIds() throws {
        var s = sampleQueue().snapshot(positionSeconds: 0)
        s.entries.append(s.entries[0])
        let repaired = try XCTUnwrap(PlayQueueStore.decode(try PlayQueueStore.encode(s)))
        XCTAssertEqual(repaired.entries.count, s.entries.count - 1)
        XCTAssertTrue(repaired.shuffle)
    }

    func testJSONShape() throws {
        var q = PlayQueue()
        q.replace(group: F.album("A"), tracks: [F.track("A1")])
        let data = try PlayQueueStore.encode(q.snapshot(positionSeconds: 12))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["version", "entries", "currentEntryId", "positionSeconds",
                                        "shuffle", "playOrder", "repeatMode"])
        XCTAssertEqual(json["repeatMode"] as? String, "off")
        let entry = try XCTUnwrap((json["entries"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(entry.keys), ["id", "batchId", "track", "group"])
        XCTAssertEqual((entry["group"] as? [String: Any])?["kind"] as? String, "album")
        let track = try XCTUnwrap(entry["track"] as? [String: Any])
        XCTAssertNil(track["container"], "nil optionals are left out")
        // A file the Kotlin side writes with explicit nulls must still load.
        let withNulls = String(decoding: data, as: UTF8.self)
            .replacingOccurrences(of: "\"artItemId\":\"A\",\"artist\":\"Artist\",\"durationSeconds\"",
                                  with: "\"artItemId\":\"A\",\"artist\":\"Artist\",\"container\":null,\"durationSeconds\"")
        XCTAssertTrue(withNulls.contains("null"))
        XCTAssertNotNil(PlayQueueStore.decode(Data(withNulls.utf8)))
        if ProcessInfo.processInfo.environment["PRINT_QUEUE_JSON"] != nil {
            let pretty = try JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys])
            print(String(decoding: pretty, as: UTF8.self))
        }
    }

    func testSaverDebouncesToLatest() {
        let lock = NSLock()
        var writes: [Double] = []
        let saver = PlayQueueSaver(interval: 0.1) { s in
            lock.lock(); writes.append(s.positionSeconds); lock.unlock()
        }
        let q = sampleQueue()
        for p in 1...5 { saver.schedule(q.snapshot(positionSeconds: Double(p))) }
        let done = expectation(description: "written")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.4) { done.fulfill() }
        wait(for: [done], timeout: 2)
        lock.lock(); XCTAssertEqual(writes, [5]); lock.unlock()
    }

    func testSaverFlushWritesPendingNow() {
        let lock = NSLock()
        var writes: [Double] = []
        let saver = PlayQueueSaver(interval: 10) { s in
            lock.lock(); writes.append(s.positionSeconds); lock.unlock()
        }
        saver.schedule(sampleQueue().snapshot(positionSeconds: 7))
        saver.flush()
        saver.flush()   // nothing pending: no second write
        lock.lock(); XCTAssertEqual(writes, [7]); lock.unlock()
    }

    func testSaverWritesToStore() {
        let saver = PlayQueueSaver(interval: 0.05)
        let snap = sampleQueue().snapshot(positionSeconds: 3)
        saver.schedule(snap)
        saver.flush()
        XCTAssertEqual(PlayQueueStore.load(), snap)
    }
}
