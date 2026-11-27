import XCTest
@testable import JellyfinMusicCore

final class CompactTabBarTests: XCTestCase {
    func testOrderAndIcons() {
        XCTAssertEqual(CompactTabBarLogic.tabs, [.home, .library, .search, .downloads, .settings])
        XCTAssertEqual(Set(CompactTabBarLogic.tabs.map(\.systemImage)).count, 5)
    }

    func testTappingSelectsOrPopsToRoot() {
        let other = CompactTabBarLogic.tap(.search, current: .home)
        XCTAssertEqual(other.selection, .search)
        XCTAssertFalse(other.popsToRoot)
        let same = CompactTabBarLogic.tap(.home, current: .home)
        XCTAssertEqual(same.selection, .home)
        XCTAssertTrue(same.popsToRoot)
    }
}
