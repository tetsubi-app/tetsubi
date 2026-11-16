// MARK: - SpaceKeyPlayPause — Mac: Space plays or pauses

import SwiftUI
#if os(macOS)
import AppKit

/// What decides whether a Space key press plays or pauses. Kept apart from the
/// event handling so it can be tested.
enum SpaceKeyRule {
    static let spaceKeyCode: UInt16 = 49

    /// - Parameters:
    ///   - firstResponder: what has the keyboard in the window the key went to.
    ///   - inMainWindowOrSheet: the key went to the app's window or a sheet on
    ///     it, not to a separate panel.
    static func shouldToggle(keyCode: UInt16,
                             modifiers: NSEvent.ModifierFlags,
                             isRepeat: Bool,
                             hasTrack: Bool,
                             inMainWindowOrSheet: Bool,
                             firstResponder: NSResponder?) -> Bool {
        guard keyCode == spaceKeyCode, !isRepeat, hasTrack, inMainWindowOrSheet else { return false }
        // ⌘Space, ⌥Space, ⌃Space and ⇧Space are other shortcuts or typing.
        guard modifiers.intersection([.command, .option, .control, .shift]).isEmpty else { return false }
        // Typing: a text field's editor, a search field or a text view needs its spaces.
        if firstResponder is NSText || firstResponder is NSTextInputClient { return false }
        // A button with the keyboard (Full Keyboard Access) presses itself on Space.
        if firstResponder is NSButton { return false }
        return true
    }
}

/// Watches key presses for the app and turns a bare Space into play/pause, as in
/// Music and QuickTime. A menu shortcut can't do this: it would also take the
/// spaces typed in the Search box and the playlist name field. This lets those
/// through and only acts when nothing that takes text has the keyboard.
@MainActor
final class SpaceKeyMonitor {
    weak var player: PlayerManager?
    private var monitor: Any?

    func start() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            // Local monitors run on the main thread.
            guard let self, let player = self.player else { return event }
            let window = event.window
            let inWindow = window != nil && (window?.isMainWindow == true || window?.sheetParent != nil)
            guard SpaceKeyRule.shouldToggle(
                keyCode: event.keyCode,
                modifiers: event.modifierFlags,
                isRepeat: event.isARepeat,
                hasTrack: player.currentTrack != nil,
                inMainWindowOrSheet: inWindow,
                firstResponder: window?.firstResponder
            ) else { return event }
            player.togglePlayPause()
            return nil   // handled: no beep
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }

    deinit {
        if let monitor { NSEvent.removeMonitor(monitor) }
    }
}

private struct SpaceTogglesPlayback: ViewModifier {
    let player: PlayerManager?
    @State private var keys = SpaceKeyMonitor()

    func body(content: Content) -> some View {
        content
            .task(id: player.map(ObjectIdentifier.init)) { keys.player = player }
            .onAppear { keys.start() }
            .onDisappear { keys.stop() }
    }
}
#endif

extension View {
    /// Mac: the Space bar plays or pauses the current track while the window has
    /// focus (not while typing). No effect on iOS.
    func spaceTogglesPlayback(_ player: PlayerManager?) -> some View {
        #if os(macOS)
        modifier(SpaceTogglesPlayback(player: player))
        #else
        self
        #endif
    }
}
