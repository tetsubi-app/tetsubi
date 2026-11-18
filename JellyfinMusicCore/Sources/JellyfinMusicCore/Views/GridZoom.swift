// MARK: - GridZoom — cards per row in the album and artist grids and Home (wide layouts)

import SwiftUI
import Observation

/// How many cards a row holds in every album and artist grid, and in
/// Home's rows, on a wide layout (Mac, iPad full width). One setting for
/// all of them, remembered with the Library's other choices; nil means
/// automatic (as many of today's covers as fit, 3…10). The maths is
/// `LibraryView.gridMetrics` and `LibraryView.homeRowMetrics`.
@MainActor
@Observable
public final class GridZoom {
    public static let shared = GridZoom()

    /// Same store and naming as the Library's sort and view.
    static let columnsKey = "library.gridColumns"

    /// The chosen count, or nil for automatic.
    public private(set) var columns: Int? {
        didSet {
            if let columns { defaults.set(columns, forKey: Self.columnsKey) }
            else { defaults.removeObject(forKey: Self.columnsKey) }
        }
    }

    /// The count the grid on screen is showing, so the first press starts
    /// from there and never jumps. Reported by the grids as they lay out.
    private(set) var shown: Int?
    /// Zoomable grids on screen (a pushed page appears before the one
    /// under it disappears, so this is a count rather than a flag).
    private(set) var visibleGrids = 0

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // Out-of-range saved values (another version's) count as automatic.
        let saved = defaults.object(forKey: Self.columnsKey) as? Int
        columns = saved.flatMap { LibraryView.gridColumnRange.contains($0) ? $0 : nil }
    }

    /// The count now, if a grid is on screen.
    private var current: Int? { visibleGrids > 0 ? (columns ?? shown) : nil }

    public var canZoomIn: Bool { current.flatMap { LibraryView.zoomed(from: $0, in: true) } != nil }
    public var canZoomOut: Bool { current.flatMap { LibraryView.zoomed(from: $0, in: false) } != nil }

    /// Bigger cards: one fewer per row.
    public func zoomIn() { zoom(in: true) }
    /// Smaller cards: one more per row.
    public func zoomOut() { zoom(in: false) }

    private func zoom(in zoomIn: Bool) {
        guard let current, let next = LibraryView.zoomed(from: current, in: zoomIn) else { return }
        columns = next
    }

    // MARK: Grids report in

    func gridAppeared() { visibleGrids += 1 }
    func gridDisappeared() { visibleGrids = max(0, visibleGrids - 1) }
    func gridShows(columns count: Int) { if shown != count { shown = count } }
}

// MARK: - Wide layouts

public extension EnvironmentValues {
    /// Wide enough for bigger cards and the +/− buttons: always on the Mac;
    /// on iPad (and big iPhones on their side) when the width is regular.
    /// `AppRootView` sets it from the size class.
    @Entry var isWideLayout: Bool = GridZoom.platformIsAlwaysWide
}

extension GridZoom {
    nonisolated static var platformIsAlwaysWide: Bool {
        #if os(macOS)
        true
        #else
        false
        #endif
    }

    /// iPad puts the buttons in the Library's tab row (it has no top bar
    /// there); the Mac puts them in the window's title bar.
    nonisolated static func buttonsInTabRow(wide: Bool) -> Bool {
        wide && !platformIsAlwaysWide
    }
}

public extension View {
    /// iOS: reads the size class into `isWideLayout`. Set once, at the root.
    @ViewBuilder
    func measuresWideLayout() -> some View {
        #if os(iOS)
        modifier(WideLayoutReader())
        #else
        self
        #endif
    }
}

#if os(iOS)
private struct WideLayoutReader: ViewModifier {
    @Environment(\.horizontalSizeClass) private var sizeClass
    func body(content: Content) -> some View {
        content.environment(\.isWideLayout, sizeClass == .regular)
    }
}
#endif

// MARK: - Buttons

/// − (smaller cards) then + (bigger cards), for the Library's tab row.
/// Dimmed at the limits.
struct GridZoomButtons: View {
    var body: some View {
        let zoom = GridZoom.shared
        HStack(spacing: 0) {
            button(GridZoomCommand.zoomOut, enabled: zoom.canZoomOut, action: zoom.zoomOut)
            button(GridZoomCommand.zoomIn, enabled: zoom.canZoomIn, action: zoom.zoomIn)
        }
    }

    private func button(_ command: GridZoomCommand, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: command.symbol)
                .font(.plex(.title3))
                .foregroundStyle(.white.opacity(enabled ? 1 : 0.35))
                .frame(width: libraryTabAccessoryWidth.scaled, height: 32.scaled)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(command.label)
        .help(command.label)
    }
}

/// The two zoom actions' symbols and wording, shared by the buttons, the
/// toolbar and the Mac's View menu.
enum GridZoomCommand {
    case zoomIn, zoomOut

    var symbol: String { self == .zoomIn ? "plus.magnifyingglass" : "minus.magnifyingglass" }
    var label: String { self == .zoomIn ? "Bigger album cards" : "Smaller album cards" }
}

public extension View {
    /// The +/− buttons at the trailing end of the top bar (the Mac's title
    /// bar), on wide layouts only, when `shown`.
    func gridZoomToolbar(_ shown: Bool = true) -> some View {
        modifier(GridZoomToolbar(shown: shown))
    }
}

private struct GridZoomToolbar: ViewModifier {
    let shown: Bool
    @Environment(\.isWideLayout) private var isWide

    func body(content: Content) -> some View {
        content.toolbar {
            if shown, isWide {
                ToolbarItemGroup(placement: .primaryAction) {
                    let zoom = GridZoom.shared
                    item(.zoomOut, enabled: zoom.canZoomOut, action: zoom.zoomOut)
                    item(.zoomIn, enabled: zoom.canZoomIn, action: zoom.zoomIn)
                }
            }
        }
    }

    private func item(_ command: GridZoomCommand, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(command.label, systemImage: command.symbol)
        }
        .disabled(!enabled)
        .help(command.label)
    }
}

// MARK: - Grids report in

extension View {
    /// A zoomable grid: tells `GridZoom` it's on screen and how many
    /// columns it shows. Does nothing when `active` is false (phones,
    /// Random Albums).
    @ViewBuilder
    func reportsGridColumns(_ columns: Int, active: Bool) -> some View {
        if active {
            self
                // Again on each appearance: another grid may have reported
                // since (Home and the Library show different counts).
                .onAppear {
                    GridZoom.shared.gridAppeared()
                    GridZoom.shared.gridShows(columns: columns)
                }
                .onDisappear { GridZoom.shared.gridDisappeared() }
                .onChange(of: columns, initial: true) { _, count in GridZoom.shared.gridShows(columns: count) }
        } else {
            self
        }
    }
}
