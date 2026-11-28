// MARK: - TetsubiTokens — the redesign's colours, spacing, radii and type (docs/redesign.md section 6)

import SwiftUI

/// Colours (dark only). The same names as Kotlin's `TetsubiColors`. The three
/// brand colours reuse `AppColors.swift`; the page background picture is still
/// `AppBackgroundView` (navy shows under it).
public enum TetsubiColors {
    /// Page background, under the background picture: #000B25.
    public static let navy = Color.appBackground
    /// Actions, links, selected navigation, progress, primary buttons: #039FD6.
    public static let blue = Color.appBlue
    /// "Current": the playing queue track, the selected tab underline, favourites: #AA5CC3.
    public static let pink = Color.appPink
    /// Status: connected, downloaded (iOS system green, dark).
    public static let green = Color(red: 0x30 / 255, green: 0xD1 / 255, blue: 0x58 / 255)
    /// Status: failed, offline (iOS system red, dark).
    public static let red = Color(red: 1, green: 0x45 / 255, blue: 0x3A / 255)

    /// Cards, bars, groups, the mini player and the sidebar: white 8%.
    public static let surface = Color.white.opacity(0.08)
    /// Art placeholder: white 16%.
    public static let art = Color.white.opacity(0.16)
    /// Selected row, segment or navigation item: blue 20%.
    public static let blueTint = Color.appBlue.opacity(0.20)
    /// The current track: pink 25%.
    public static let pinkTint = Color.appPink.opacity(0.25)
    /// Dividers and outlines: white 12%.
    public static let hairline = Color.white.opacity(0.12)

    public static let textPrimary = Color.white
    public static let textSecondary = Color.white.opacity(0.60)
    public static let textTertiary = Color.white.opacity(0.40)
}

/// Spacing steps: 4, 8, 12, 16, 24, 32 only.
public enum Spacing {
    public static let xs: CGFloat = 4
    public static let s: CGFloat = 8
    public static let m: CGFloat = 12
    public static let l: CGFloat = 16
    public static let xl: CGFloat = 24
    public static let xxl: CGFloat = 32
}

/// Corner radii: 6 thumbnails and track highlights, 10 card art, 16 panels,
/// groups and banners. Pills and round buttons use `Capsule` / `Circle`.
public enum Radii {
    public static let thumb: CGFloat = 6
    public static let card: CGFloat = 10
    public static let panel: CGFloat = 16
}

/// The type scale in DEKIRU Sans. Sizes are multiplied by `UIScale` on the
/// Mac (like `Font.plex(size:)`), so read these in `body`.
public enum TetsubiType {
    /// Page title: 28 bold.
    public static var pageTitle: Font { .plex(size: 28, weight: .bold) }
    /// Section title: 20 semibold.
    public static var sectionTitle: Font { .plex(size: 20, weight: .semibold) }
    /// Row title: 15 medium.
    public static var rowTitle: Font { .plex(size: 15, weight: .medium) }
    /// Body: 14 regular.
    public static var body: Font { .plex(size: 14) }
    /// A card's title: 14 semibold (its subtitle is `caption` in secondary).
    public static var cardTitle: Font { .plex(size: 14, weight: .semibold) }
    /// Trailing meta in rows and small controls: 13 medium.
    public static var control: Font { .plex(size: 13, weight: .medium) }
    /// Caption: 12 regular.
    public static var caption: Font { .plex(size: 12) }
    /// Navigation label: 11 medium.
    public static var navLabel: Font { .plex(size: 11, weight: .medium) }
    /// Now Playing title 22 bold, artist 17 medium.
    public static var nowPlayingTitle: Font { .plex(size: 22, weight: .bold) }
    public static var nowPlayingArtist: Font { .plex(size: 17, weight: .medium) }
}
