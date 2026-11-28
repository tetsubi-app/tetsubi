// MARK: - CardGrid — the one grid: albums, artists, playlists, shelves (docs/redesign.md section 3)

import SwiftUI

// MARK: Pure rules

/// The grid's maths, pure so it can be tested. Same rules as Kotlin's `CardGrid.kt`.
public enum CardGridLayout {
    /// Columns for a content width (inside the page margins): `max(2, floor((w + g) / (min + g)))`.
    public static func columns(contentWidth: CGFloat, density: Density, gutter: CGFloat) -> Int {
        max(2, Int(((contentWidth + gutter) / (density.minCard + gutter)).rounded(.down)))
    }

    /// The width the columns share: the pane less the start margin and the end
    /// margin, which is `endInset` (the A to Z strip) where that is wider.
    public static func contentWidth(paneWidth: CGFloat, margin: CGFloat, endInset: CGFloat = 0) -> CGFloat {
        paneWidth - margin - max(margin, endInset)
    }

    /// Width of one card: the columns and their gutters fill the content width exactly.
    public static func cardWidth(contentWidth: CGFloat, columns: Int, gutter: CGFloat) -> CGFloat {
        (contentWidth - CGFloat(columns - 1) * gutter) / CGFloat(columns)
    }

    /// Share of a card left showing at a shelf's right edge on compact.
    public static let shelfPeek: CGFloat = 0.35

    /// A shelf's card width. Wider than compact: exactly the grid's card.
    /// Compact: narrowed a little so `columns` whole cards and `shelfPeek` of
    /// the next fit between the left margin and the window's right edge.
    public static func shelfCardWidth(contentWidth: CGFloat, columns: Int, gutter: CGFloat,
                                      pageMargin: CGFloat, peek: Bool) -> CGFloat {
        if !peek { return cardWidth(contentWidth: contentWidth, columns: columns, gutter: gutter) }
        return (contentWidth + pageMargin - CGFloat(columns) * gutter) / (CGFloat(columns) + shelfPeek)
    }

    /// A list view flows into two columns from this pane width (margins included).
    public static let listTwoColumnsFrom: CGFloat = 700

    public static func listColumns(paneWidth: CGFloat) -> Int {
        paneWidth >= listTwoColumnsFrom ? 2 : 1
    }
}

/// Free-function spelling of the rule, as in the redesign notes.
public func gridColumns(contentWidth: CGFloat, density: Density, gutter: CGFloat) -> Int {
    CardGridLayout.columns(contentWidth: contentWidth, density: density, gutter: gutter)
}

/// What a grid or shelf cell needs to draw itself.
public struct CardMetrics: Equatable, Sendable {
    public var columns: Int
    public var cardWidth: CGFloat
    public var gutter: CGFloat
    public var margin: CGFloat
    /// Width the columns share (inside the margins).
    public var contentWidth: CGFloat
    /// Columns a list of rows in the same pane would use.
    public var listColumns: Int

    /// The grid for a pane `paneWidth` wide (floored at 1 pt).
    public init(paneWidth: CGFloat, windowClass: WindowWidthClass, density: Density, endInset: CGFloat = 0) {
        let pane = max(1, paneWidth)
        margin = windowClass.pageMargin
        gutter = windowClass.gutter
        contentWidth = max(1, CardGridLayout.contentWidth(paneWidth: pane, margin: margin, endInset: endInset))
        columns = CardGridLayout.columns(contentWidth: contentWidth, density: density, gutter: gutter)
        cardWidth = max(1, CardGridLayout.cardWidth(contentWidth: contentWidth, columns: columns, gutter: gutter))
        listColumns = CardGridLayout.listColumns(paneWidth: pane - endInset)
    }

    /// Row gap: gutter + 8.
    public var rowSpacing: CGFloat { gutter + 8 }

    /// `columns` equal, flexible grid items a gutter apart.
    public var gridItems: [GridItem] {
        Array(repeating: GridItem(.flexible(minimum: 0), spacing: gutter, alignment: .top), count: columns)
    }
}

// MARK: CardGrid

/// The grid for albums, artists, playlists and everything else with cards:
/// a fixed column count from the density and the window class, gutters from the
/// class, row gap = gutter + 8, margin all round. It scrolls on its own.
/// `endInset` is room kept at the right for something over the margin (the A to
/// Z strip): it narrows the content, so columns are counted without it. Cells
/// fill their column; `content` gets the metrics (`cardWidth` for art sizes).
///
/// To put headers or banners above the cards, use `CardGridContent` inside
/// your own `ScrollView`, or `CardMetrics` with a `LazyVGrid`.
public struct CardGrid<Content: View>: View {
    private let endInset: CGFloat
    private let header: AnyView?
    private let footer: AnyView?
    private let content: (CardMetrics) -> Content

    @Environment(\.windowWidthClass) private var windowClass
    @State private var paneWidth: CGFloat = 0

    /// `header` sits above the first row and `footer` (centred) under the last,
    /// both scrolling with the grid ("Shake it").
    public init(endInset: CGFloat = 0, header: AnyView? = nil, footer: AnyView? = nil,
                @ViewBuilder content: @escaping (CardMetrics) -> Content) {
        self.endInset = endInset
        self.header = header
        self.footer = footer
        self.content = content
    }

    public var body: some View {
        let metrics = CardMetrics(paneWidth: paneWidth, windowClass: windowClass,
                                  density: DensityStore.shared.density, endInset: endInset)
        ScrollView {
            if let header { header }
            LazyVGrid(columns: metrics.gridItems, spacing: metrics.rowSpacing) {
                content(metrics)
            }
            .padding(.leading, metrics.margin)
            .padding(.trailing, max(metrics.margin, endInset))
            .padding(.vertical, metrics.margin)
            if let footer {
                footer
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, Spacing.xl)
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { paneWidth = $0 }
    }
}

/// The same grid without its own scrolling, for a page that puts other things
/// above or below it in one `ScrollView`. Measures the width it is given.
public struct CardGridContent<Content: View>: View {
    private let endInset: CGFloat
    private let content: (CardMetrics) -> Content

    @Environment(\.windowWidthClass) private var windowClass
    @State private var paneWidth: CGFloat = 0

    public init(endInset: CGFloat = 0, @ViewBuilder content: @escaping (CardMetrics) -> Content) {
        self.endInset = endInset
        self.content = content
    }

    public var body: some View {
        let metrics = CardMetrics(paneWidth: paneWidth, windowClass: windowClass,
                                  density: DensityStore.shared.density, endInset: endInset)
        LazyVGrid(columns: metrics.gridItems, spacing: metrics.rowSpacing) {
            content(metrics)
        }
        .padding(.leading, metrics.margin)
        .padding(.trailing, max(metrics.margin, endInset))
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { paneWidth = $0 }
    }
}

// MARK: CardShelf

/// A horizontal row of the same cards (Home's shelves). On compact the next
/// card peeks in at the right edge; wider windows show whole cards.
/// `card` gets the item and the card's width.
public struct CardShelf<Item: Identifiable, Card: View>: View {
    private let items: [Item]
    private let card: (Item, CGFloat) -> Card

    @Environment(\.windowWidthClass) private var windowClass
    @State private var paneWidth: CGFloat = 0

    public init(items: [Item], @ViewBuilder card: @escaping (Item, CGFloat) -> Card) {
        self.items = items
        self.card = card
    }

    /// Card width for a pane (pure, for tests and for cards laid out by hand).
    public static func cardWidth(paneWidth: CGFloat, windowClass: WindowWidthClass, density: Density) -> CGFloat {
        let margin = windowClass.pageMargin
        let gutter = windowClass.gutter
        let content = max(1, paneWidth - 2 * margin)
        let columns = CardGridLayout.columns(contentWidth: content, density: density, gutter: gutter)
        return max(1, CardGridLayout.shelfCardWidth(contentWidth: content, columns: columns, gutter: gutter,
                                                    pageMargin: margin, peek: windowClass == .compact))
    }

    public var body: some View {
        let width = Self.cardWidth(paneWidth: max(paneWidth, 1), windowClass: windowClass,
                                   density: DensityStore.shared.density)
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: windowClass.gutter) {
                ForEach(items) { item in card(item, width) }
            }
            .padding(.horizontal, windowClass.pageMargin)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { paneWidth = $0 }
    }
}

// MARK: ListGrid

/// Rows (a list view) in the same pane: they fill the width, in two columns
/// from `CardGridLayout.listTwoColumnsFrom` points. Rows run left to right,
/// then down. Rows keep their own page margin (`ListRow`); `endInset` keeps
/// clear of the A to Z strip. Scrolls on its own.
public struct ListGrid<Content: View>: View {
    private let endInset: CGFloat
    private let content: () -> Content

    public init(endInset: CGFloat = 0, @ViewBuilder content: @escaping () -> Content) {
        self.endInset = endInset
        self.content = content
    }

    public var body: some View {
        ScrollView {
            ListGridContent(endInset: endInset, content: content)
                .padding(.bottom, Spacing.l)
        }
    }
}

/// `ListGrid` without its own scrolling, for a page that puts other things
/// above or below it in one `ScrollView` (Search's playlists).
public struct ListGridContent<Content: View>: View {
    private let endInset: CGFloat
    private let content: () -> Content

    @Environment(\.windowWidthClass) private var windowClass
    @State private var paneWidth: CGFloat = 0

    public init(endInset: CGFloat = 0, @ViewBuilder content: @escaping () -> Content) {
        self.endInset = endInset
        self.content = content
    }

    public var body: some View {
        let columns = CardGridLayout.listColumns(paneWidth: paneWidth - endInset)
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(minimum: 0), spacing: 0, alignment: .top),
                                 count: columns), spacing: 0) {
            content()
        }
        // The rows already end a page margin from the edge; the strip needs only what is left over.
        .padding(.trailing, max(0, endInset - windowClass.pageMargin))
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { paneWidth = $0 }
    }
}
