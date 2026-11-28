// MARK: - NowPlayingLayout — Now Playing's size rules (docs/redesign.md section 5)

import SwiftUI

/// Now Playing's layout rules in points, with no scaling. The width it is shown
/// at picks the layout through `WindowWidthClass`: compact is one column (the
/// queue below the controls), medium and expanded two (the player left, the
/// queue panel right). An iPhone is always one column, even on its side. Same
/// numbers as Kotlin's `NowPlayingLayout`.
public enum NowPlayingLayout {
    /// The cover on one column (a phone): at most 300.
    public static let compactMaxArt: CGFloat = 300
    /// The cover in two columns: at most 420.
    public static let wideMaxArt: CGFloat = 420
    public static let minArt: CGFloat = 140
    /// Side room of the single column.
    public static let compactMargin: CGFloat = 16

    /// Two columns from a medium width (600), never on an iPhone.
    public static func usesTwoColumns(width: CGFloat, isPhone: Bool) -> Bool {
        WindowWidthClass.resolve(width: width, isPhone: isPhone).isWide
    }

    /// One column: as wide as fits between the margins, up to 300, and no more than 40% of the height.
    public static func compactArtSize(width: CGFloat, height: CGFloat) -> CGFloat {
        max(minArt, min(width - 2 * compactMargin, compactMaxArt, height * 0.4))
    }

    /// The left column's width in two columns: 46% of the width, from 360 to 560.
    public static func leftColumnWidth(width: CGFloat) -> CGFloat {
        min(max(width * 0.46, 360), 560)
    }

    /// The cover in two columns: as big as fits, so the title, progress and
    /// buttons below it are all on screen without scrolling. The rest of that
    /// column is about 470 (550 with the visualiser).
    public static func wideArtSize(columnWidth: CGFloat, height: CGFloat, visualiser: Bool) -> CGFloat {
        let others: CGFloat = visualiser ? 550 : 470
        let byWidth = min(columnWidth - 64, wideMaxArt)
        return max(minArt, min(byWidth, height - others))
    }

    /// Mac sheet: how wide it opens. The two columns when the window has room
    /// (leaving a margin each side), else the narrow card it always was.
    public static func macSheetIdealWidth(windowWidth: CGFloat) -> CGFloat {
        let room = min(windowWidth - 160, 1100)
        return room >= 700 ? room : 460
    }

    /// Mac sheet: how tall it opens. Most of the window, up to 880, never under 700.
    public static func macSheetIdealHeight(windowHeight: CGFloat) -> CGFloat {
        max(min(windowHeight - 80, 880), 700)
    }
}
