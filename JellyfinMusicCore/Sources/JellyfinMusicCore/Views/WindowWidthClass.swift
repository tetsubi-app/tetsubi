// MARK: - WindowWidthClass — one place that says how wide the window is

import SwiftUI

/// The window's width class, shared with the Linux and Android apps
/// (compact under 600 pt, medium 600–1023, expanded 1024 and up).
///
/// The Mac follows its window live: compact gets the phone layout (tab bar,
/// phone-sized cards, no zoom buttons), medium and expanded the wide one.
public enum WindowWidthClass: Int, Comparable, Sendable {
    case compact, medium, expanded

    public static let mediumMinimum: CGFloat = 600
    public static let expandedMinimum: CGFloat = 1024

    public static func of(width: CGFloat) -> WindowWidthClass {
        if width >= expandedMinimum { return .expanded }
        if width >= mediumMinimum { return .medium }
        return .compact
    }

    public static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }

    /// Cards sized and counted for a wide screen, and the zoom buttons.
    public var usesWideGrids: Bool { self != .compact }
    /// Tabs shown as a bar (compact) rather than the sidebar the Mac's
    /// medium and expanded windows get.
    public var usesTabBar: Bool { self == .compact }
}

public extension EnvironmentValues {
    /// The window's width class; set at the root on the Mac.
    @Entry var windowWidthClass: WindowWidthClass = .expanded
}
