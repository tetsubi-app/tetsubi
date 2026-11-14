import XCTest
@testable import JellyfinMusicCore

/// Library ▸ Sort ▸ Random: shuffled, shown 24 at a time.
@MainActor
final class LibraryRandomTests: XCTestCase {

    private func albums(_ count: Int) -> [JellyfinAlbum] {
        (0..<count).map { i in
            JellyfinAlbum(id: "a\(i)", name: "Album \(i)", serverId: nil, premiereDate: nil, productionYear: nil,
                          albumArtists: [JellyfinPerson(id: "ar", name: "Artist", serverId: nil)],
                          album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
        }
    }

    private func model(with count: Int) -> LibraryViewModel {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        let model = LibraryViewModel(api: api)
        model.receive(albums(count))
        model.sortOption = .random
        return model
    }

    func testShowsFirstPageThenMoreAtTheEnd() {
        let model = model(with: 60)
        XCTAssertEqual(model.sortedAlbums.count, 24)

        // A card that isn't the last does nothing.
        model.loadMoreIfNeeded(after: model.sortedAlbums[10])
        XCTAssertEqual(model.sortedAlbums.count, 24)

        model.loadMoreIfNeeded(after: model.sortedAlbums.last!)
        XCTAssertEqual(model.sortedAlbums.count, 48)
        model.loadMoreIfNeeded(after: model.sortedAlbums.last!)
        XCTAssertEqual(model.sortedAlbums.count, 60)
        model.loadMoreIfNeeded(after: model.sortedAlbums.last!)
        XCTAssertEqual(model.sortedAlbums.count, 60)
    }

    func testEveryAlbumOnceWithNoRepeats() {
        let model = model(with: 60)
        while let last = model.sortedAlbums.last, model.sortedAlbums.count < 60 {
            model.loadMoreIfNeeded(after: last)
        }
        XCTAssertEqual(Set(model.sortedAlbums.map(\.id)), Set(albums(60).map(\.id)))
        XCTAssertEqual(model.sortedAlbums.count, 60)
    }

    func testOrderSurvivesARefreshAndPagesKeepTheirPlace() {
        let model = model(with: 60)
        model.loadMoreIfNeeded(after: model.sortedAlbums.last!)
        let before = model.sortedAlbums.map(\.id)
        // Same albums from the server plus one new one.
        model.receive(albums(61))
        XCTAssertEqual(Array(model.sortedAlbums.map(\.id).prefix(48)), before)
    }

    func testPickingRandomAgainStartsOverAtOnePage() {
        let model = model(with: 60)
        model.loadMoreIfNeeded(after: model.sortedAlbums.last!)
        model.sortOption = .random
        XCTAssertEqual(model.sortedAlbums.count, 24)
    }

    func testOtherSortsShowEverything() {
        let model = model(with: 60)
        model.sortOption = .name
        XCTAssertEqual(model.sortedAlbums.count, 60)
    }
}
