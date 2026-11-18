import XCTest
import CoreGraphics
import ImageIO
@testable import JellyfinMusicCore
@testable import TetsubiWidgetData

/// The home-screen widget: the snapshot file, what the widget shows for
/// it, and the writer's throttling and album choice.
final class WidgetSnapshotTests: XCTestCase {

    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("WidgetSnapshotTests-\(UUID().uuidString)", isDirectory: true)
        WidgetSnapshotStore.directoryOverride = dir
    }

    override func tearDown() {
        WidgetSnapshotStore.directoryOverride = nil
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func playing(maniac: Bool = false, isPlaying: Bool = true,
                         until: Date? = nil, queue: Int = 3066, remaining: Int = 1900) -> WidgetSnapshot {
        WidgetSnapshot(trackTitle: "Song", artist: "Band", album: "Record",
                       albumId: "a1", discIds: ["a1", "a2"], isPlaying: isPlaying,
                       maniacMode: maniac, accentRGB: .init(red: 1, green: 0.5, blue: 0),
                       artFile: "art-1.jpg", signedIn: true, updatedAt: now, playingUntil: until,
                       queueSeconds: queue, remainingSeconds: remaining)
    }

    private func track(_ snapshot: WidgetSnapshot, at date: Date) -> WidgetDisplay.Track? {
        if case .track(let track) = WidgetDisplay.decide(snapshot, now: date) { return track }
        return nil
    }

    // MARK: Snapshot file

    func testSnapshotRoundTripsThroughTheFile() throws {
        let snapshot = playing(until: now.addingTimeInterval(200))
        try WidgetSnapshotStore.write(snapshot)
        XCTAssertEqual(WidgetSnapshotStore.read(), snapshot)
    }

    func testNoFileReadsAsNil() {
        XCTAssertNil(WidgetSnapshotStore.read())
    }

    func testSnapshotJSONUsesTheDesignedNames() throws {
        try WidgetSnapshotStore.write(playing())
        let data = try Data(contentsOf: dir.appendingPathComponent("nowplaying.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for key in ["trackTitle", "artist", "album", "albumId", "discIds", "isPlaying",
                    "maniacMode", "accentRGB", "artFile", "signedIn", "updatedAt",
                    "queueSeconds", "remainingSeconds"] {
            XCTAssertNotNil(json[key], key)
        }
        XCTAssertEqual(json["queueSeconds"] as? Int, 3066)
        XCTAssertEqual(json["remainingSeconds"] as? Int, 1900)
    }

    func testOlderSnapshotWithoutQueueTimesStillReads() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let json = #"{"trackTitle":"Song","albumId":"a1","discIds":["a1"],"isPlaying":false,"maniacMode":false,"signedIn":true,"updatedAt":1800000000}"#
        try Data(json.utf8).write(to: dir.appendingPathComponent("nowplaying.json"))
        let snapshot = try XCTUnwrap(WidgetSnapshotStore.read())
        XCTAssertEqual(snapshot.queueSeconds, 0)
        XCTAssertEqual(snapshot.remainingSeconds, 0)
        XCTAssertNil(track(snapshot, at: now)?.durationText)
    }

    func testSameContentIgnoresTheTime() {
        var later = playing()
        later.updatedAt = now.addingTimeInterval(60)
        XCTAssertTrue(playing().sameContent(as: later))
        later.isPlaying = false
        XCTAssertFalse(playing().sameContent(as: later))
    }

    func testSavingArtRemovesOlderArtButKeepsTheOneInUse() throws {
        try WidgetSnapshotStore.saveArt(Data([1]), name: "art-a.jpg")
        try WidgetSnapshotStore.saveArt(Data([2]), name: "art-b.jpg", keeping: "art-a.jpg")
        XCTAssertNotNil(WidgetSnapshotStore.artURL("art-a.jpg"))
        try WidgetSnapshotStore.saveArt(Data([3]), name: "art-c.jpg", keeping: "art-b.jpg")
        XCTAssertNil(WidgetSnapshotStore.artURL("art-a.jpg"))
        XCTAssertNotNil(WidgetSnapshotStore.artURL("art-b.jpg"))
        XCTAssertNotNil(WidgetSnapshotStore.artURL("art-c.jpg"))
        XCTAssertNil(WidgetSnapshotStore.artURL(nil))
    }

    // MARK: What to show

    func testPlaying() {
        guard case .track(let track) = WidgetDisplay.decide(playing(until: now.addingTimeInterval(100)), now: now) else {
            return XCTFail("should show the track")
        }
        XCTAssertEqual(track.title, "Song")
        XCTAssertTrue(track.isPlaying)
        XCTAssertTrue(track.showsSkips)
        XCTAssertEqual(track.accent, .init(red: 1, green: 0.5, blue: 0))
        XCTAssertEqual(track.artFile, "art-1.jpg")
        XCTAssertEqual(track.accessibilityLabel, "Song by Band")
        XCTAssertEqual(WidgetDisplay.decide(playing(), now: now).openURL, WidgetShared.nowPlayingURL)
    }

    func testStoppedWithLastAlbumStillShowsItWithPlay() {
        guard case .track(let track) = WidgetDisplay.decide(playing(isPlaying: false), now: now) else {
            return XCTFail("the last track should stay shown")
        }
        XCTAssertFalse(track.isPlaying)
        XCTAssertEqual(track.accent, .init(red: 1, green: 0.5, blue: 0), "keeps its colour")
    }

    func testPlayingPastTheTracksEndShowsPlay() {
        // The app was closed while playing: nothing rewrote the snapshot.
        let snapshot = playing(until: now.addingTimeInterval(-1))
        guard case .track(let track) = WidgetDisplay.decide(snapshot, now: now) else { return XCTFail() }
        XCTAssertFalse(track.isPlaying)
    }

    func testNothingPlayedYet() {
        XCTAssertEqual(WidgetDisplay.decide(nil, now: now), .nothingPlaying)
        XCTAssertEqual(WidgetDisplay.decide(WidgetSnapshot(signedIn: true), now: now), .nothingPlaying)
        XCTAssertEqual(WidgetDisplay.nothingPlaying.openURL, WidgetShared.homeURL)
    }

    func testSignedOut() {
        var snapshot = playing()
        snapshot.signedIn = false
        XCTAssertEqual(WidgetDisplay.decide(snapshot, now: now), .signedOut)
        XCTAssertEqual(WidgetDisplay.signedOut.openURL, WidgetShared.homeURL)
    }

    func testManiacModeHidesSkips() {
        guard case .track(let track) = WidgetDisplay.decide(playing(maniac: true), now: now) else { return XCTFail() }
        XCTAssertFalse(track.showsSkips)
    }

    func testNoArtistReadsAsJustTheTitle() {
        var snapshot = playing()
        snapshot.artist = nil
        guard case .track(let track) = WidgetDisplay.decide(snapshot, now: now) else { return XCTFail() }
        XCTAssertEqual(track.accessibilityLabel, "Song")
    }

    func testRedrawnAtTheTracksEndWhilePlaying() {
        let end = now.addingTimeInterval(90)
        XCTAssertEqual(WidgetDisplay.timelineDates(for: playing(until: end), now: now), [now, end])
        XCTAssertEqual(WidgetDisplay.timelineDates(for: playing(until: now.addingTimeInterval(-5)), now: now), [now])
        XCTAssertEqual(WidgetDisplay.timelineDates(for: nil, now: now), [now])
    }

    // MARK: Duration and finish time

    func testDurationReadsLikeNowPlaying() {
        XCTAssertEqual(WidgetDisplay.formatDuration(3066), "51m 06s")
        XCTAssertEqual(WidgetDisplay.formatDuration(6305), "1h 45m 05s")
        XCTAssertEqual(WidgetDisplay.formatDuration(59), "0m 59s")
        for seconds in [0, 59, 3066, 3600, 6305] {
            XCTAssertEqual(WidgetDisplay.formatDuration(seconds),
                           NowPlayingView.formatAlbumDuration(TimeInterval(seconds)))
        }
        XCTAssertEqual(track(playing(until: now.addingTimeInterval(100)), at: now)?.durationText,
                       "Duration: 51m 06s")
    }

    func testPlayingFinishesAtTheSnapshotTimePlusWhatsLeft() {
        let snapshot = playing(until: now.addingTimeInterval(300))
        // Ten seconds on, still the same moment: the clock doesn't move it.
        XCTAssertEqual(track(snapshot, at: now.addingTimeInterval(10))?.finishesAt, now.addingTimeInterval(1900))
    }

    func testPausedFinishMovesWithTheClock() {
        let snapshot = playing(isPlaying: false)
        XCTAssertEqual(track(snapshot, at: now)?.finishesAt, now.addingTimeInterval(1900))
        let later = now.addingTimeInterval(600)
        XCTAssertEqual(track(snapshot, at: later)?.finishesAt, later.addingTimeInterval(1900))
        // An hour on (perhaps closed while paused): only the duration.
        let hourOn = now.addingTimeInterval(WidgetDisplay.pausedFinishWindow)
        XCTAssertNil(track(snapshot, at: hourOn)?.finishesAt)
        XCTAssertEqual(track(snapshot, at: hourOn)?.durationText, "Duration: 51m 06s")
    }

    func testOutOfDatePlayingSnapshotShowsOnlyTheDuration() {
        // The app was closed while playing: the finish time can't be trusted.
        let snapshot = playing(until: now.addingTimeInterval(-1))
        let shown = track(snapshot, at: now)
        XCTAssertNil(shown?.finishesAt)
        XCTAssertNil(shown?.finishText())
        XCTAssertEqual(shown?.durationText, "Duration: 51m 06s")
    }

    func testUnknownLengthHidesBoth() {
        let shown = track(playing(until: now.addingTimeInterval(100), queue: 0, remaining: 0), at: now)
        XCTAssertNil(shown?.durationText)
        XCTAssertNil(shown?.finishesAt)
        XCTAssertNil(shown?.finishText())
    }

    func testFinishTimeFollowsTheTwelveOrTwentyFourHourClock() {
        let utc = TimeZone(identifier: "UTC")!
        // 1_800_000_000 is 08:00:00 UTC; + 19900 s = 13:31:40.
        let shown = track(playing(until: now.addingTimeInterval(100), remaining: 19_900), at: now)
        let british = shown?.finishText(locale: Locale(identifier: "en_GB"), timeZone: utc)
        XCTAssertEqual(british, "Ends: 13:31")
        let american = shown?.finishText(locale: Locale(identifier: "en_US"), timeZone: utc)
        XCTAssertTrue(american?.hasPrefix("Ends: 1:31") == true, american ?? "nil")
        XCTAssertTrue(american?.contains("PM") == true, american ?? "nil")
    }

    func testMediumLineMatchesNowPlayingsWording() {
        let utc = TimeZone(identifier: "UTC")!
        let gb = Locale(identifier: "en_GB")
        // 08:00:00 UTC + 20_520 s = 13:42:00.
        let shown = track(playing(until: now.addingTimeInterval(100), remaining: 20_520), at: now)
        XCTAssertEqual(shown?.timesLine(locale: gb, timeZone: utc), "Duration: 51m 06s   Ends: 13:42")
        let stale = track(playing(until: now.addingTimeInterval(-1)), at: now)
        XCTAssertEqual(stale?.timesLine(locale: gb, timeZone: utc), "Duration: 51m 06s")
        XCTAssertNil(track(playing(queue: 0, remaining: 0), at: now)?.timesLine())
    }

    func testPausedTimelineTicksEachMinuteForAnHour() throws {
        // 1900 s left: the finish (date + 1900) turns over a minute at
        // date = now + 20 s, then every 60 s.
        let snapshot = playing(isPlaying: false)
        let dates = WidgetDisplay.timelineDates(for: snapshot, now: now)
        XCTAssertEqual(dates.first, now)
        XCTAssertEqual(dates[1], now.addingTimeInterval(20))
        XCTAssertEqual(dates[2], now.addingTimeInterval(80))
        let end = now.addingTimeInterval(WidgetDisplay.pausedFinishWindow)
        XCTAssertEqual(dates.last, end)
        XCTAssertEqual(dates.count, 62)
        XCTAssertEqual(dates, dates.sorted())
        for date in dates.dropFirst().dropLast() {
            let finish = try XCTUnwrap(track(snapshot, at: date)?.finishesAt)
            XCTAssertEqual(finish.timeIntervalSince1970.truncatingRemainder(dividingBy: 60), 0, accuracy: 0.001)
        }
        XCTAssertNil(track(snapshot, at: end)?.finishesAt)
        // Half an hour later the timeline only covers what's left of the hour.
        let half = WidgetDisplay.timelineDates(for: snapshot, now: now.addingTimeInterval(1800))
        XCTAssertEqual(half.last, end)
        XCTAssertLessThan(half.count, 33)
        // Over an hour old, or no length known: nothing to tick.
        XCTAssertEqual(WidgetDisplay.timelineDates(for: snapshot, now: end), [end])
        XCTAssertEqual(WidgetDisplay.timelineDates(for: playing(isPlaying: false, queue: 0, remaining: 0), now: now), [now])
    }

    func testFinishTimeDriftingWithTheClockIsNotAChange() {
        // Playing on: ten seconds later, ten seconds fewer left.
        var later = playing(until: now.addingTimeInterval(300))
        later.updatedAt = now.addingTimeInterval(10)
        later.remainingSeconds = 1890
        XCTAssertTrue(playing(until: now.addingTimeInterval(300)).sameContent(as: later))
        // A seek of a minute moves the finish: write it.
        later.remainingSeconds = 1830
        XCTAssertFalse(playing(until: now.addingTimeInterval(300)).sameContent(as: later))
        // Paused, what's left is what matters.
        var paused = playing(isPlaying: false)
        paused.updatedAt = now.addingTimeInterval(30)
        XCTAssertTrue(playing(isPlaying: false).sameContent(as: paused))
        paused.remainingSeconds = 1800
        XCTAssertFalse(playing(isPlaying: false).sameContent(as: paused))
        // A new queue length is always a change.
        var longer = playing(isPlaying: false)
        longer.queueSeconds += 200
        XCTAssertFalse(playing(isPlaying: false).sameContent(as: longer))
    }

    func testQueueTimesAreWholeSeconds() {
        let times = WidgetSnapshotWriter.queueTimes(length: 3065.6, remaining: 1899.7)
        XCTAssertEqual(times.queue, 3066)
        XCTAssertEqual(times.remaining, 1900)
        XCTAssertEqual(WidgetSnapshotWriter.queueTimes(length: 0, remaining: 50).queue, 0)
        XCTAssertEqual(WidgetSnapshotWriter.queueTimes(length: 0, remaining: 50).remaining, 0)
        // Never more left than the whole queue, never less than nothing.
        XCTAssertEqual(WidgetSnapshotWriter.queueTimes(length: 100, remaining: 500).remaining, 100)
        XCTAssertEqual(WidgetSnapshotWriter.queueTimes(length: 100, remaining: -3).remaining, 0)
        XCTAssertEqual(WidgetSnapshotWriter.queueTimes(length: .nan, remaining: 3).queue, 0)
    }

    // MARK: Writer

    func testWritesAtMostOncePerSecond() {
        // Last write half a second ago: wait for the rest of the second.
        XCTAssertEqual(WidgetSnapshotWriter.nextWrite(now: now, lastWrite: now.addingTimeInterval(-0.5),
                                                      artWaitUntil: nil),
                       now.addingTimeInterval(0.5))
        // Long ago: straight away.
        XCTAssertEqual(WidgetSnapshotWriter.nextWrite(now: now, lastWrite: .distantPast, artWaitUntil: nil), now)
        // A new track's art still loading holds the write back.
        XCTAssertEqual(WidgetSnapshotWriter.nextWrite(now: now, lastWrite: .distantPast,
                                                      artWaitUntil: now.addingTimeInterval(2)),
                       now.addingTimeInterval(2))
    }

    func testPlayingUntilTheTracksEnd() {
        // `now` is a whole number of 15 s, so nothing is rounded here.
        XCTAssertEqual(WidgetSnapshotWriter.playingUntil(duration: 200, position: 50, now: now),
                       now.addingTimeInterval(150 + WidgetSnapshotWriter.endMargin))
        XCTAssertEqual(WidgetSnapshotWriter.playingUntil(duration: 0, position: 0, now: now),
                       now.addingTimeInterval(WidgetSnapshotWriter.unknownLengthWait))
    }

    func testPlayingOnGivesTheSameEndTime() {
        // A second later and a second further in: no new snapshot needed.
        let first = WidgetSnapshotWriter.playingUntil(duration: 200, position: 50.2, now: now.addingTimeInterval(0.2))
        let later = WidgetSnapshotWriter.playingUntil(duration: 200, position: 51.4, now: now.addingTimeInterval(1.4))
        XCTAssertEqual(first, later)
        XCTAssertGreaterThanOrEqual(first, now.addingTimeInterval(150 + WidgetSnapshotWriter.endMargin))
    }

    func testAlbumQueueKeepsItsDiscsInOrderEvenShuffled() {
        // Disc 1 = "d1" (also the merged album's ID), disc 2 = "d2", shuffled.
        let ids = WidgetSnapshotWriter.albumIDs(queueAlbumIDs: ["d2", "d1", "d2", "d1"],
                                                sourcePositions: [3, 0, 2, 1],
                                                sourceID: "d1", currentAlbumID: "d2")
        XCTAssertEqual(ids.album, "d1")
        XCTAssertEqual(ids.discs, ["d1", "d2"])
    }

    func testSingleAlbum() {
        let ids = WidgetSnapshotWriter.albumIDs(queueAlbumIDs: ["a", "a"], sourcePositions: [],
                                                sourceID: "a", currentAlbumID: "a")
        XCTAssertEqual(ids.album, "a")
        XCTAssertEqual(ids.discs, ["a"])
    }

    func testPlaylistUsesThePlayingTracksAlbum() {
        let ids = WidgetSnapshotWriter.albumIDs(queueAlbumIDs: ["x", "y"], sourcePositions: [0, 1],
                                                sourceID: "playlist", currentAlbumID: "y")
        XCTAssertEqual(ids.album, "y")
        XCTAssertEqual(ids.discs, ["y"])
    }

    func testArtIsShrunkToAJPEG() throws {
        let side = 1000
        let context = CGContext(data: nil, width: side, height: side / 2, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.setFillColor(red: 0.2, green: 0.4, blue: 0.9, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: side, height: side / 2))
        let data = try XCTUnwrap(WidgetSnapshotWriter.jpeg(of: context.makeImage()!, side: 300))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(image.width, 300)
        XCTAssertEqual(image.height, 150)
        XCTAssertEqual(CGImageSourceGetType(source) as String?, "public.jpeg")
    }
}
