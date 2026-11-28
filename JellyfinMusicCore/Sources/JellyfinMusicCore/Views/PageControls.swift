// MARK: - PageControls — Sort, Filter, Grid / List and density in a page header (docs/redesign.md sections 3 and 4)

import SwiftUI

/// One option of a `ControlMenu`.
public struct ControlChoice: Identifiable {
    public let title: String
    public let selected: Bool
    public let onSelect: () -> Void
    public var id: String { title }
}

/// A menu of exclusive options: `title` ("Sort", "Filter"), the current `value`, and the `choices`.
public struct ControlMenu {
    public let title: String
    public let value: String
    public let choices: [ControlChoice]
}

/// A `ControlMenu` over `options`, with `selected` ticked.
public func controlMenu<T: Equatable>(_ title: String, options: [T], selected: T,
                                      label: (T) -> String, onSelect: @escaping (T) -> Void) -> ControlMenu {
    ControlMenu(title: title, value: label(selected),
                choices: options.map { option in
                    ControlChoice(title: label(option), selected: option == selected) { onSelect(option) }
                })
}

/// Windows at least this wide (points) show the page controls inline in the
/// header; narrower ones use one "Sort & view" button and sheet. Same as Kotlin.
public enum PageControlsRule {
    public static let inlineFrom: CGFloat = 720
    public static func showsInline(windowWidth: CGFloat) -> Bool { windowWidth >= inlineFrom }
}

/// The controls a page offers (pass only what it has): `sort`, `filter`, a
/// Grid / List choice (`view`, with `onView`), the global card size (`density`)
/// and a "Shuffle again" action. Put it in a `PageHeader`'s actions: the inline
/// controls, or on a narrow window one Sort and view button.
public struct PageControls: View {
    var sort: ControlMenu?
    var filter: ControlMenu?
    var view: LibraryDisplayMode?
    var onView: (LibraryDisplayMode) -> Void
    var density: Bool
    var shuffle: (() -> Void)?

    @Environment(\.windowWidth) private var windowWidth
    @State private var showsSheet = false

    public init(sort: ControlMenu? = nil, filter: ControlMenu? = nil, view: LibraryDisplayMode? = nil,
                onView: @escaping (LibraryDisplayMode) -> Void = { _ in }, density: Bool = false,
                shuffle: (() -> Void)? = nil) {
        self.sort = sort
        self.filter = filter
        self.view = view
        self.onView = onView
        self.density = density
        self.shuffle = shuffle
    }

    public var body: some View {
        if sort == nil, filter == nil, view == nil, !density, shuffle == nil {
            EmptyView()
        } else if PageControlsRule.showsInline(windowWidth: windowWidth) {
            HStack(spacing: Spacing.s) {
                if let shuffle { ActionChip("Shuffle again", action: shuffle) }
                if let sort { MenuPill(menu: sort) }
                if let filter { MenuPill(menu: filter) }
                if let view { ViewControl(view: view, onView: onView) }
                if density { DensityControl() }
            }
        } else {
            SortAndViewButton { showsSheet = true }
                .sheet(isPresented: $showsSheet) { sheet }
        }
    }

    private var sheet: some View {
        SortAndViewSheet {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    if let view {
                        SheetSection("View") { ViewControl(view: view, onView: onView, stretch: true) }
                    }
                    if let sort {
                        SheetSection(sort.title) { SheetChoices(menu: sort) { showsSheet = false } }
                    }
                    if let filter {
                        SheetSection(filter.title) { SheetChoices(menu: filter) { showsSheet = false } }
                    }
                    if density {
                        SheetSection("Card size") { DensityControl() }
                    }
                    if let shuffle {
                        ActionChip("Shuffle again") { shuffle(); showsSheet = false }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
        }
        #if os(macOS)
        .frame(minWidth: 320, minHeight: 360)
        #endif
    }
}

private struct SheetSection<Content: View>: View {
    let title: String
    let content: Content
    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text(title).font(TetsubiType.rowTitle).foregroundStyle(TetsubiColors.textSecondary)
            content
        }
    }
}

/// The options of a menu as rows in a rounded surface, the chosen one ticked.
private struct SheetChoices: View {
    let menu: ControlMenu
    let close: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            ForEach(menu.choices) { choice in
                Button {
                    choice.onSelect()
                    close()
                } label: {
                    HStack {
                        Text(choice.title)
                            .font(TetsubiType.rowTitle.weight(.regular))
                            .foregroundStyle(TetsubiColors.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if choice.selected {
                            Image(systemName: "checkmark").font(.system(size: 14.scaled, weight: .semibold))
                                .foregroundStyle(TetsubiColors.blue)
                        }
                    }
                    .padding(.horizontal, Spacing.l)
                    .frame(minHeight: 48.scaled)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(choice.selected ? .isSelected : [])
            }
        }
        .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
    }
}

/// A pill showing "Sort Name" that opens the menu of options.
private struct MenuPill: View {
    let menu: ControlMenu
    var body: some View {
        Menu {
            ForEach(menu.choices) { choice in
                Button(action: choice.onSelect) {
                    if choice.selected {
                        Label(choice.title, systemImage: "checkmark")
                    } else {
                        Text(choice.title)
                    }
                }
            }
        } label: {
            HStack(spacing: Spacing.xs) {
                Text(menu.title).foregroundStyle(TetsubiColors.textSecondary)
                Text(menu.value).foregroundStyle(TetsubiColors.textPrimary)
                Image(systemName: "chevron.down").font(.system(size: 10.scaled, weight: .semibold))
                    .foregroundStyle(TetsubiColors.textSecondary)
            }
            .font(TetsubiType.control)
            .lineLimit(1)
            .padding(.leading, Spacing.m)
            .padding(.trailing, Spacing.s)
            .frame(height: 32.scaled)
            .background(TetsubiColors.surface, in: Capsule())
            .contentShape(Capsule())
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .fixedSize()
        .accessibilityLabel("\(menu.title), \(menu.value)")
    }
}

/// Grid | List, segmented like `DensityControl`; `stretch` fills the width (the sheet).
struct ViewControl: View {
    let view: LibraryDisplayMode
    let onView: (LibraryDisplayMode) -> Void
    var stretch = false

    var body: some View {
        HStack(spacing: 0) {
            ForEach(LibraryDisplayMode.allCases) { mode in
                let selected = mode == view
                let tint = selected ? TetsubiColors.blue : TetsubiColors.textSecondary
                Button { onView(mode) } label: {
                    HStack(spacing: 6) {
                        Image(systemName: LibraryView.symbol(for: mode)).font(.system(size: 14.scaled))
                        if stretch { Text(mode.rawValue).font(TetsubiType.control) }
                    }
                    .foregroundStyle(tint)
                    .frame(width: stretch ? nil : 44.scaled, height: 28.scaled)
                    .frame(maxWidth: stretch ? .infinity : nil)
                    .background(selected ? TetsubiColors.blueTint : Color.clear, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(mode.rawValue)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(2)
        .frame(height: 32.scaled)
        .background(TetsubiColors.surface, in: Capsule())
    }
}

/// Filter chips in a row (Search: All, Albums, Artists, Playlists): the selected one is blue-tinted.
public struct FilterChips<Option: Hashable>: View {
    let options: [Option]
    let selected: Option
    let label: (Option) -> String
    let onSelect: (Option) -> Void
    @Environment(\.windowWidthClass) private var windowClass

    public init(_ options: [Option], selected: Option, label: @escaping (Option) -> String,
                onSelect: @escaping (Option) -> Void) {
        self.options = options
        self.selected = selected
        self.label = label
        self.onSelect = onSelect
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.s) {
                ForEach(options, id: \.self) { option in
                    let isSelected = option == selected
                    Button { onSelect(option) } label: {
                        Text(label(option))
                            .font(TetsubiType.control)
                            .foregroundStyle(isSelected ? TetsubiColors.blue : TetsubiColors.textPrimary)
                            .lineLimit(1)
                            .padding(.horizontal, 14)
                            .frame(height: 32.scaled)
                            .background(isSelected ? TetsubiColors.blueTint : TetsubiColors.surface, in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
            .padding(.horizontal, windowClass.pageMargin)
        }
    }
}

// MARK: - Detail pages

public extension View {
    /// A page that draws its own `PageHeader`: no native bar. iOS hides the
    /// navigation bar; the Mac hides the native Back button, and `detail` pages keep the swipe-back gesture
    /// the hidden bar would take away.
    @ViewBuilder
    func ownsItsHeader(isDetail: Bool = false) -> some View {
        #if os(iOS)
        self
            .toolbar(.hidden, for: .navigationBar)
            .background { if isDetail { PopGestureEnabler() } }
        #else
        // The window's own bar stays (traffic lights, sidebar button); only its Back goes.
        if isDetail {
            self.navigationBarBackButtonHidden(true)
        } else {
            self
        }
        #endif
    }
}

#if os(iOS)
/// A hidden navigation bar also turns off the edge-swipe back gesture. This puts it back.
private struct PopGestureEnabler: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Host { Host() }
    func updateUIViewController(_ controller: Host, context: Context) {}

    final class Host: UIViewController, UIGestureRecognizerDelegate {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let nav = navigationController else { return }
            nav.interactivePopGestureRecognizer?.delegate = self
            nav.interactivePopGestureRecognizer?.isEnabled = true
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }
    }
}
#endif
