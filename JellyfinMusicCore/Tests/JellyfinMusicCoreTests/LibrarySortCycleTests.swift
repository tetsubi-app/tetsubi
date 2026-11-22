import XCTest
@testable import JellyfinMusicCore

/// Tapping the selected Albums tab steps the sort: Name → Artist → Year → Date Added → Random → Name.
@MainActor
final class LibrarySortCycleTests: XCTestCase {

    private var defaults: UserDefaults!
    private let suite = "LibrarySortCycleTests"

    override func setUp() {
        super.setUp()
        UserDefaults().removePersistentDomain(forName: suite)
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func model() -> LibraryViewModel {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        return LibraryViewModel(api: api, defaults: defaults)
    }

    func testTheOrderIsTheMenusAndGoesRound() {
        XCTAssertEqual(LibrarySortOption.name.next, .artist)
        XCTAssertEqual(LibrarySortOption.artist.next, .year)
        XCTAssertEqual(LibrarySortOption.year.next, .dateAdded)
        XCTAssertEqual(LibrarySortOption.dateAdded.next, .random)
        XCTAssertEqual(LibrarySortOption.random.next, .name)
    }

    func testCyclingSetsAndRemembersTheSort() {
        let model = model()
        XCTAssertEqual(model.sortOption, .name)
        XCTAssertEqual(model.cycleSort(), .artist)
        XCTAssertEqual(model.cycleSort(), .year)
        XCTAssertEqual(model.sortOption, .year)
        // Remembered as the menu's choice.
        XCTAssertEqual(LibraryViewModel(api: JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "t"),
                                        defaults: defaults).sortOption, .year)
        XCTAssertEqual(model.cycleSort(), .dateAdded)
        XCTAssertEqual(model.cycleSort(), .random)
        XCTAssertEqual(model.cycleSort(), .name)
    }

    func testReachingRandomDealsAShuffle() {
        let model = model()
        let albums = (0..<60).map { i in
            JellyfinAlbum(id: "a\(i)", name: "Album \(i)", serverId: nil, premiereDate: nil, productionYear: nil,
                          albumArtists: [JellyfinPerson(id: "ar", name: "Artist", serverId: nil)],
                          album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
        }
        model.receive(albums)
        model.sortOption = .dateAdded
        XCTAssertEqual(model.sortedAlbums.count, 60)
        XCTAssertEqual(model.cycleSort(), .random)
        // Random's first page, as picking it from the menu.
        XCTAssertEqual(model.sortedAlbums.count, 24)
    }
}
