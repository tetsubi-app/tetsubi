import XCTest
@testable import JellyfinMusicCore

/// Server URLs keep the server's own path (a server behind a proxy at /jellyfin).
final class BuildURLTests: XCTestCase {

    private func url(base: String, _ path: String, _ query: [URLQueryItem] = []) -> String? {
        JellyfinAPI(baseURL: URL(string: base)!).buildURL(path, queryItems: query)?.absoluteString
    }

    func testPlainServer() {
        XCTAssertEqual(url(base: "http://jellyfin-box:8096", "Users/Me"), "http://jellyfin-box:8096/Users/Me")
        XCTAssertEqual(url(base: "http://jellyfin-box:8096", "/Users/Me"), "http://jellyfin-box:8096/Users/Me")
        XCTAssertEqual(url(base: "http://jellyfin-box:8096/", "Users/Me"), "http://jellyfin-box:8096/Users/Me")
    }

    func testSubPathIsKept() {
        XCTAssertEqual(url(base: "https://example.com/jellyfin", "Users/Me"),
                       "https://example.com/jellyfin/Users/Me")
        XCTAssertEqual(url(base: "https://example.com/jellyfin/", "/Items/a1/Images/Primary"),
                       "https://example.com/jellyfin/Items/a1/Images/Primary")
    }

    func testQueryIsAdded() {
        XCTAssertEqual(url(base: "https://example.com/jellyfin", "QuickConnect/Connect",
                           [URLQueryItem(name: "secret", value: "abc")]),
                       "https://example.com/jellyfin/QuickConnect/Connect?secret=abc")
    }

    func testImageRequestUsesSubPath() {
        let api = JellyfinAPI(baseURL: URL(string: "https://example.com/jellyfin")!, accessToken: "t")
        let s = api.imageURLRequest(for: "al-1", size: 300)?.url?.absoluteString ?? ""
        XCTAssertTrue(s.hasPrefix("https://example.com/jellyfin/Items/al-1/Images/Primary"), s)
    }
}
