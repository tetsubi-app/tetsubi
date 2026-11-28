import XCTest
@testable import JellyfinMusicCore

/// Quick Connect starts only from the user's tap (docs/gotchas.md, Authentication).
@MainActor
final class SignInRulesTests: XCTestCase {
    func testTheIdleHintPointsAtTheButtonOrAsksForAnAddress() {
        XCTAssertEqual(SignInRules.idleHint(hasAddress: true), "Tap Use Quick Connect to get a code.")
        XCTAssertEqual(SignInRules.idleHint(hasAddress: false), "Choose your server above, or type its address.")
    }

    func testAnotherAddressDropsTheCode() {
        XCTAssertTrue(SignInRules.shouldStopQuickConnect(address: "http://other:8096", startedFor: "http://one:8096"))
        XCTAssertTrue(SignInRules.shouldStopQuickConnect(address: "one", startedFor: nil))
        XCTAssertFalse(SignInRules.shouldStopQuickConnect(address: "http://one:8096", startedFor: "http://one:8096"))
    }

    func testResetGoesBackToIdleAndNothingStartsOnItsOwn() {
        let controller = QuickConnectController()
        XCTAssertEqual(controller.state, .idle)
        controller.reset()
        XCTAssertEqual(controller.state, .idle)
    }
}
