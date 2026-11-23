import XCTest
@testable import JellyfinMusicCore

/// Maniac Mode: albums play start to finish — no skip, shuffle, repeat or scrubbing.
@MainActor
final class ManiacModeTests: XCTestCase {

    private var suite = ""

    override func setUp() async throws {
        suite = "ManiacModeTests-\(UUID().uuidString)"
        PlaybackSettings.defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() async throws {
        UserDefaults().removePersistentDomain(forName: suite)
        PlaybackSettings.defaults = .standard
    }

    private func makePlayer() -> PlayerManager {
        PlayerManager(api: JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token"))
    }

    func testOffByDefaultAndSaved() {
        XCTAssertFalse(PlaybackSettings.maniacMode)
        let player = makePlayer()
        XCTAssertFalse(player.maniacMode)
        player.setManiacMode(true)
        XCTAssertTrue(PlaybackSettings.maniacMode)
        XCTAssertTrue(makePlayer().maniacMode, "a new player picks up the saved setting")
    }

    func testTurningOnSwitchesShuffleAndRepeatOff() {
        let player = makePlayer()
        player.setShuffle(true)
        player.setRepeatMode(.one)
        player.setManiacMode(true)
        XCTAssertFalse(player.shuffle)
        XCTAssertEqual(player.repeatMode, .off)
    }

    func testShuffleAndRepeatCantBeTurnedOnWhileOn() {
        let player = makePlayer()
        player.setManiacMode(true)
        player.setShuffle(true)
        player.toggleShuffle()
        XCTAssertFalse(player.shuffle)
        player.cycleRepeatMode()
        player.setRepeatMode(.all)
        XCTAssertEqual(player.repeatMode, .off)
    }

    func testTurningOffAllowsThemAgainButLeavesThemOff() {
        let player = makePlayer()
        player.setShuffle(true)
        player.setManiacMode(true)
        player.setManiacMode(false)
        XCTAssertFalse(player.shuffle, "stays off after Maniac Mode ends")
        player.toggleShuffle()
        player.cycleRepeatMode()
        XCTAssertTrue(player.shuffle)
        XCTAssertEqual(player.repeatMode, .all)
    }

    func testPostsAChangeNotification() {
        let player = makePlayer()
        let posted = expectation(forNotification: PlayerManager.maniacModeDidChange, object: player)
        player.setManiacMode(true)
        wait(for: [posted], timeout: 1)
    }

    // MARK: End of the album

    func testFinishingTheAlbumClosesNowPlaying() {
        let player = makePlayer()
        player.isNowPlayingPresented = true
        player.finishQueue()
        XCTAssertFalse(player.isNowPlayingPresented)
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.position, 0)
    }

    // MARK: A track that fails

    func testAFailedTrackStopsWithAMessage() {
        let player = makePlayer()
        XCTAssertNil(player.errorMessage)
        player.trackFailed()
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.lastError, .trackFailed)
        XCTAssertTrue(player.errorMessage?.contains("press Play to try again") ?? false)
    }

    // MARK: Interruptions

    func testAnInterruptionWhilePausedDoesntStartPlayback() {
        let player = makePlayer()
        player.interruptionBegan()
        player.interruptionEnded(shouldResume: true)
        XCTAssertFalse(player.isPlaying, "wasn't playing before, so stays paused")
    }

    func testInterruptionWithoutResumeStaysPaused() {
        let player = makePlayer()
        player.interruptionBegan()
        player.interruptionEnded(shouldResume: false)
        XCTAssertFalse(player.isPlaying)
    }
}
