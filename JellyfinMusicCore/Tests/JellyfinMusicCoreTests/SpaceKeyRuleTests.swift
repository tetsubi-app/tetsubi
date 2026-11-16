#if os(macOS)
import XCTest
import AppKit
@testable import JellyfinMusicCore

/// The Mac's Space bar plays or pauses, but never takes a space someone is typing.
final class SpaceKeyRuleTests: XCTestCase {

    private func toggles(keyCode: UInt16 = SpaceKeyRule.spaceKeyCode,
                         modifiers: NSEvent.ModifierFlags = [],
                         isRepeat: Bool = false,
                         hasTrack: Bool = true,
                         inWindow: Bool = true,
                         firstResponder: NSResponder? = NSView()) -> Bool {
        SpaceKeyRule.shouldToggle(keyCode: keyCode, modifiers: modifiers, isRepeat: isRepeat,
                                  hasTrack: hasTrack, inMainWindowOrSheet: inWindow,
                                  firstResponder: firstResponder)
    }

    func testSpaceTogglesWhenNothingTakesText() {
        XCTAssertTrue(toggles())
        XCTAssertTrue(toggles(firstResponder: nil))
        XCTAssertTrue(toggles(firstResponder: NSTableView()))
        XCTAssertTrue(toggles(firstResponder: NSSlider()))   // after seeking
    }

    func testOtherKeysAreLeftAlone() {
        XCTAssertFalse(toggles(keyCode: 0))   // "a"
        XCTAssertFalse(toggles(keyCode: 36))  // return
    }

    func testTypingKeepsItsSpaces() {
        XCTAssertFalse(toggles(firstResponder: NSTextView()))   // a field editor
        XCTAssertFalse(toggles(firstResponder: NSText()))
    }

    func testAFocusedButtonPressesItself() {
        XCTAssertFalse(toggles(firstResponder: NSButton()))
    }

    func testModifiedSpaceIsAnotherShortcut() {
        for flag: NSEvent.ModifierFlags in [.command, .option, .control, .shift] {
            XCTAssertFalse(toggles(modifiers: flag))
        }
    }

    func testHeldKeyDoesNotRepeat() {
        XCTAssertFalse(toggles(isRepeat: true))
    }

    func testNothingPlayingMeansNoTogglingAndNoKeyTaken() {
        XCTAssertFalse(toggles(hasTrack: false))
    }

    func testOnlyTheAppWindowCounts() {
        XCTAssertFalse(toggles(inWindow: false))
    }
}
#endif
