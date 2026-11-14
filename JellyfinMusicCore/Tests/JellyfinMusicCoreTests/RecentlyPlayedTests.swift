// Recently Played: played tracks down to one entry per album.

import XCTest
@testable import JellyfinMusicCore

final class RecentlyPlayedTests: XCTestCase {

    private func tracks(_ json: String) throws -> [JellyfinAlbum] {
        try JSONDecoder().decode(JellyfinAPI.APIResponse<JellyfinAlbum>.self, from: Data(json.utf8)).items
    }

    func testGroupsPlayedTracksIntoAlbums() throws {
        let items = try tracks("""
        {"Items":[
          {"Id":"t1","Type":"Audio","Album":"Bleach","AlbumId":"bleach","AlbumArtists":[{"Name":"Nirvana"}],"UserData":{"PlayCount":2}},
          {"Id":"t2","Type":"Audio","Album":"Bleach","AlbumId":"bleach","AlbumArtists":[{"Name":"Nirvana"}],"UserData":{"PlayCount":1}},
          {"Id":"t3","Type":"Audio","Album":"Never","AlbumId":"never","UserData":{"PlayCount":0}},
          {"Id":"t4","Type":"Audio","Album":"Nevermind","AlbumId":"nevermind","AlbumArtists":[{"Name":"Nirvana"}],"UserData":{"PlayCount":5}}
        ]}
        """)
        XCTAssertEqual(JellyfinAPI.distinctPlayedAlbums(items, limit: 12).map(\.id), ["bleach", "nevermind"])
    }

    /// Tracks of one album with different artists or album names: one entry
    /// (on Android the repeated ID crashed Home's row).
    func testListsAnAlbumOnceWhenItsTracksDisagree() throws {
        let items = try tracks("""
        {"Items":[
          {"Id":"t1","Type":"Audio","Album":"Unity","AlbumId":"unity","AlbumArtists":[{"Name":"HEY-SMITH"}],"UserData":{"PlayCount":1}},
          {"Id":"t2","Type":"Audio","Album":"Unity","AlbumId":"unity","AlbumArtists":[{"Name":"Less Than Jake"}],"UserData":{"PlayCount":1}},
          {"Id":"t3","Type":"Audio","Album":"Unity (Split)","AlbumId":"unity","UserData":{"PlayCount":1}}
        ]}
        """)
        XCTAssertEqual(JellyfinAPI.distinctPlayedAlbums(items, limit: 12).map(\.id), ["unity"])
    }

    func testStopsAtTheLimit() throws {
        let items = try tracks("""
        {"Items":[
          {"Id":"t1","Type":"Audio","Album":"A","AlbumId":"a","UserData":{"PlayCount":1}},
          {"Id":"t2","Type":"Audio","Album":"B","AlbumId":"b","UserData":{"PlayCount":1}},
          {"Id":"t3","Type":"Audio","Album":"C","AlbumId":"c","UserData":{"PlayCount":1}}
        ]}
        """)
        XCTAssertEqual(JellyfinAPI.distinctPlayedAlbums(items, limit: 2).map(\.id), ["a", "b"])
    }
}
