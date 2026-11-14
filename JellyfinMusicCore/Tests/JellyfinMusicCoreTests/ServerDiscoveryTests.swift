import XCTest
@testable import JellyfinMusicCore

/// Finding Jellyfin servers on the local network (sign-in screen).
final class ServerDiscoveryTests: XCTestCase {

    func testParsesAServerReply() {
        let json = #"{"Address":"http://192.168.1.20:8096","Id":"abc123","Name":"Jellyfin-Box","EndpointAddress":null}"#
        let server = ServerDiscovery.parse(Data(json.utf8), senderIP: "192.168.1.20")
        XCTAssertEqual(server, DiscoveredServer(id: "abc123", name: "Jellyfin-Box", address: "http://192.168.1.20:8096"))
    }

    func testDockerContainerAddressIsSwappedForTheSender() {
        // A server in Docker reports its container address.
        let json = #"{"Address":"http://172.19.0.2:8096","Id":"abc123","Name":"Jellyfin-Box"}"#
        let server = ServerDiscovery.parse(Data(json.utf8), senderIP: "192.168.1.50")
        XCTAssertEqual(server?.address, "http://192.168.1.50:8096")
    }

    func testHostnameAddressIsKept() {
        XCTAssertEqual(
            ServerDiscovery.preferredAddress(reported: "https://music.example.com", senderIP: "192.168.1.50"),
            "https://music.example.com")
    }

    func testHTTPSAndPortSurviveTheSwap() {
        XCTAssertEqual(
            ServerDiscovery.preferredAddress(reported: "https://10.0.0.5:8920", senderIP: "192.168.1.50"),
            "https://192.168.1.50:8920")
    }

    func testMissingNameGetsAFallback() {
        let json = #"{"Address":"http://192.168.1.20:8096","Id":"abc123"}"#
        XCTAssertEqual(ServerDiscovery.parse(Data(json.utf8), senderIP: "192.168.1.20")?.name, "Jellyfin server")
    }

    func testRejectsJunk() {
        XCTAssertNil(ServerDiscovery.parse(Data("hello".utf8), senderIP: "192.168.1.20"))
        XCTAssertNil(ServerDiscovery.parse(Data(#"{"Name":"No ID"}"#.utf8), senderIP: "192.168.1.20"))
    }

    /// Real broadcast on this network. Skipped unless TETSUBI_LIVE_DISCOVERY=1
    /// (needs a server, or a stand-in, answering on UDP 7359).
    func testLiveSearch() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["TETSUBI_LIVE_DISCOVERY"] == "1")
        let servers = await ServerDiscovery.search()
        print("Found:", servers)
        XCTAssertFalse(servers.isEmpty)
    }

    // MARK: - Sweep

    private func ip(_ text: String) -> UInt32 {
        text.split(separator: ".").reduce(0) { $0 << 8 | UInt32($1)! }
    }

    func testSweepCoversAHomeNetworkExceptThisDevice() {
        let targets = ServerDiscovery.sweepTargets(ip: ip("192.168.1.20"), mask: ip("255.255.255.0"))
        XCTAssertEqual(targets.count, 253)   // .1–.254, minus this device
        XCTAssertEqual(targets.first, ip("192.168.1.1"))
        XCTAssertEqual(targets.last, ip("192.168.1.254"))
        XCTAssertFalse(targets.contains(ip("192.168.1.20")))
    }

    func testSweepCoversASlash22() {
        // A /22 home network: 192.168.4.0 to 192.168.7.255.
        let targets = ServerDiscovery.sweepTargets(ip: ip("192.168.4.81"), mask: ip("255.255.252.0"))
        XCTAssertEqual(targets.count, 1021)
        XCTAssertTrue(targets.contains(ip("192.168.5.50")))
        XCTAssertEqual(targets.last, ip("192.168.7.254"))
    }

    func testBigNetworksAreCutToTheSlash22AroundThisDevice() {
        let targets = ServerDiscovery.sweepTargets(ip: ip("10.0.9.5"), mask: ip("255.0.0.0"))
        XCTAssertEqual(targets.count, 1021)
        XCTAssertEqual(targets.first, ip("10.0.8.1"))
        XCTAssertEqual(targets.last, ip("10.0.11.254"))
    }

    func testTinyNetworksHaveNothingToSweep() {
        XCTAssertEqual(ServerDiscovery.sweepTargets(ip: ip("192.168.1.20"), mask: ip("255.255.255.255")), [])
        XCTAssertEqual(ServerDiscovery.sweepTargets(ip: ip("192.168.1.20"), mask: ip("255.255.255.254")), [])
    }

    func testOnlyPrivateRangesAreSwept() {
        XCTAssertTrue(ServerDiscovery.isPrivate(ip("10.1.2.3")))
        XCTAssertTrue(ServerDiscovery.isPrivate(ip("172.16.0.1")))
        XCTAssertTrue(ServerDiscovery.isPrivate(ip("172.31.255.1")))
        XCTAssertTrue(ServerDiscovery.isPrivate(ip("192.168.4.81")))
        XCTAssertFalse(ServerDiscovery.isPrivate(ip("172.32.0.1")))
        XCTAssertFalse(ServerDiscovery.isPrivate(ip("8.8.8.8")))
        XCTAssertFalse(ServerDiscovery.isPrivate(ip("169.254.1.1")))
    }

    func testDuplicateRepliesAreMergedAndSorted() {
        let a = DiscoveredServer(id: "1", name: "Zebra", address: "http://192.168.1.2:8096")
        let b = DiscoveredServer(id: "2", name: "attic", address: "http://192.168.1.3:8096")
        let aAgain = DiscoveredServer(id: "1", name: "Zebra", address: "http://192.168.1.2:8096")
        XCTAssertEqual(ServerDiscovery.merge([a, b, aAgain]), [b, a])
    }
}
