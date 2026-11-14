import XCTest
@testable import JellyfinMusicCore

final class UpNextTests: XCTestCase {

    func testRepeatOffListsRemainingTracks() {
        XCTAssertEqual(upNextIndices(count: 5, current: 1, repeatMode: .off), [2, 3, 4])
        XCTAssertEqual(upNextIndices(count: 5, current: 4, repeatMode: .off), [])
    }

    func testRepeatAllWrapsToStart() {
        XCTAssertEqual(upNextIndices(count: 5, current: 3, repeatMode: .all), [4, 0, 1, 2])
        XCTAssertEqual(upNextIndices(count: 1, current: 0, repeatMode: .all), [])
    }

    func testRepeatOneStillListsWhatFollows() {
        XCTAssertEqual(upNextIndices(count: 3, current: 0, repeatMode: .one), [1, 2])
    }

    func testEmptyOrInvalidQueue() {
        XCTAssertEqual(upNextIndices(count: 0, current: 0, repeatMode: .all), [])
        XCTAssertEqual(upNextIndices(count: 3, current: 7, repeatMode: .off), [])
    }
}
