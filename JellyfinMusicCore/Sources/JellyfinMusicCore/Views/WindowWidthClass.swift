// MARK: - WindowWidthClass — one place that says how wide the window is

import SwiftUI

/// The window's width class, shared with the Linux and Android apps
/// (compact under 600 pt, medium 600–1023, expanded 1024 and up). It carries
/// the page margin and gutter of docs/redesign.md section 1.
///
/// The Mac follows its window live: compact gets the phone layout (tab bar,
/// phone-sized cards), medium and expanded the wide one. iPad follows the
/// width it is given (split view, Stage Manager); an iPhone is always compact.
public enum WindowWidthClass: Int, Comparable, Sendable {
    case compact, medium, expanded

    public static let mediumMinimum: CGFloat = 600
    public static let expandedMinimum: CGFloat = 1024
    /// Content never grows wider than this; it is centred beyond it.
    public static let maxContentWidth: CGFloat = 1600

    public static func of(width: CGFloat) -> WindowWidthClass {
        if width >= expandedMinimum { return .expanded }
        if width >= mediumMinimum { return .medium }
        return .compact
    }

    /// The class for a window `width` wide. An iPhone is compact in every
    /// orientation (a Max on its side is 932 pt wide but still a phone).
    public static func resolve(width: CGFloat, isPhone: Bool) -> WindowWidthClass {
        isPhone ? .compact : of(width: width)
    }

    public static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }

    /// Space at the left and right of a page: 16, 24, 32.
    public var pageMargin: CGFloat {
        switch self {
        case .compact: 16
        case .medium: 24
        case .expanded: 32
        }
    }

    /// Space between cards and columns: 12, 16, 20.
    public var gutter: CGFloat {
        switch self {
        case .compact: 12
        case .medium: 16
        case .expanded: 20
        }
    }

    /// Cards sized and counted for a wide screen, and the header actions.
    public var usesWideGrids: Bool { self != .compact }
    /// Same as `usesWideGrids`, the redesign's name for it.
    public var isWide: Bool { self != .compact }
    /// Tabs shown as a bar (compact) rather than the sidebar the Mac's
    /// medium and expanded windows get.
    public var usesTabBar: Bool { self == .compact }
    /// The docked mini player's height: 64 compact, 72 otherwise.
    public var miniPlayerHeight: CGFloat { self == .compact ? 64 : 72 }
}

public extension EnvironmentValues {
    /// The window's width class; set at the root on every platform.
    @Entry var windowWidthClass: WindowWidthClass = .expanded
    /// The window's width in points (page controls go inline from 720).
    @Entry var windowWidth: CGFloat = 1024
}

public extension View {
    /// Page-margin and gutter helpers read from the environment.
    func pageMargins(_ edges: Edge.Set = .horizontal) -> some View {
        modifier(PageMarginModifier(edges: edges))
    }

    /// Centres this view and limits it to `WindowWidthClass.maxContentWidth`.
    func tetsubiMaxContentWidth() -> some View {
        frame(maxWidth: WindowWidthClass.maxContentWidth).frame(maxWidth: .infinity)
    }
}

private struct PageMarginModifier: ViewModifier {
    let edges: Edge.Set
    @Environment(\.windowWidthClass) private var windowClass
    func body(content: Content) -> some View {
        content.padding(edges, windowClass.pageMargin)
    }
}
