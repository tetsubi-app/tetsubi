import XCTest
@testable import JellyfinMusicCore

/// Turning a typed server address into one that works (sign-in screen).
final class ServerAddressTests: XCTestCase {

    private func strings(_ input: String) -> [String] {
        ServerAddress.candidates(for: input).map(\.absoluteString)
    }

    func testBareIPTriesJellyfinPortFirst() {
        XCTAssertEqual(strings("192.168.1.20"), [
            "http://192.168.1.20:8096",
            "https://192.168.1.20",
            "http://192.168.1.20",
            "https://192.168.1.20:8920",
        ])
    }

    func testBareHostWithPort() {
        XCTAssertEqual(strings("jellyfin-box:8096"), ["http://jellyfin-box:8096", "https://jellyfin-box:8096"])
    }

    func testHTTPWithoutPortTriesAsTypedThen8096() {
        XCTAssertEqual(strings("http://192.168.1.20"), ["http://192.168.1.20", "http://192.168.1.20:8096"])
    }

    func testHTTPSWithoutPortTriesAsTypedThen8920() {
        XCTAssertEqual(strings("https://music.example.com"),
                       ["https://music.example.com", "https://music.example.com:8920"])
    }

    func testFullAddressIsUsedAsIs() {
        XCTAssertEqual(strings("http://192.168.1.20:8096"), ["http://192.168.1.20:8096"])
    }

    func testTrailingSlashSpacesAndCaseAreTidied() {
        XCTAssertEqual(strings("  HTTP://192.168.1.20:8096/  "), ["http://192.168.1.20:8096"])
    }

    func testPathIsKept() {
        // A server behind a proxy at a sub-path.
        XCTAssertEqual(strings("https://example.com/jellyfin"),
                       ["https://example.com/jellyfin", "https://example.com:8920/jellyfin"])
    }

    func testNonsenseGivesNothing() {
        XCTAssertEqual(strings(""), [])
        XCTAssertEqual(strings("   "), [])
        XCTAssertEqual(strings("my server"), [])
        XCTAssertEqual(strings("ftp://192.168.1.20"), [])
    }

    // MARK: - Resolving

    private static let jellyfinInfo = Data(#"{"Id":"abc","Version":"12.4.0","ProductName":"Jellyfin Server","ServerName":"Jellyfin-Box"}"#.utf8)

    func testResolvePicksTheFirstAddressThatIsJellyfin() async {
        let result = await ServerAddress.resolve("192.168.1.20") { url in
            // Only the plain-http, port-80 address answers.
            url.absoluteString == "http://192.168.1.20/System/Info/Public" ? Self.jellyfinInfo : nil
        }
        XCTAssertEqual(try? result.get().absoluteString, "http://192.168.1.20")
    }

    func testResolveSkipsAWebPageThatIsntJellyfin() async {
        // Port 80 is a router's web page; Jellyfin is on 8096.
        let result = await ServerAddress.resolve("http://192.168.1.20") { url in
            url.port == 8096 ? Self.jellyfinInfo : Data("<html>Router</html>".utf8)
        }
        XCTAssertEqual(try? result.get().absoluteString, "http://192.168.1.20:8096")
    }

    func testResolveReportsNotFound() async {
        let result = await ServerAddress.resolve("192.168.1.20") { _ in nil }
        XCTAssertEqual(result, .failure(.notFound("192.168.1.20")))
    }

    func testResolveReportsInvalid() async {
        let result = await ServerAddress.resolve("my server") { _ in Self.jellyfinInfo }
        XCTAssertEqual(result, .failure(.invalid))
    }

    /// Real server. Skipped unless TETSUBI_LIVE_ADDRESS is set to an address
    /// as a user might type it, e.g. "192.168.1.50".
    func testLiveResolve() async throws {
        let input = ProcessInfo.processInfo.environment["TETSUBI_LIVE_ADDRESS"] ?? ""
        try XCTSkipIf(input.isEmpty, "set TETSUBI_LIVE_ADDRESS")
        let result = await ServerAddress.resolve(input)
        print("Resolved \(input) →", result)
        XCTAssertNoThrow(try result.get())
    }

    func testOtherProductsAreNotJellyfin() {
        XCTAssertFalse(ServerAddress.isJellyfinInfo(Data(#"{"Id":"x","Version":"4.9","ProductName":"Emby Server"}"#.utf8)))
        XCTAssertFalse(ServerAddress.isJellyfinInfo(Data("{}".utf8)))
        XCTAssertTrue(ServerAddress.isJellyfinInfo(Self.jellyfinInfo))
    }
}
