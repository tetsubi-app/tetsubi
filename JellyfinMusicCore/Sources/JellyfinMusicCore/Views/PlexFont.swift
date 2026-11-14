// MARK: - PlexFont — IBM Plex Sans as the app's typeface (trial branch)

import SwiftUI
import CoreText

/// IBM Plex Sans (SIL Open Font License, from github.com/IBM/plex), with its
/// line height cut to the iOS system font's (1.193 em, was 1.300 em) so screens keep their
/// vertical rhythm. "Plex" is a Reserved Font Name, so the modified files are
/// named DEKIRU Sans (Resources/Fonts/DekiruSans-README.txt). Registered for
/// this process on first use.
/// `Font.plex(.headline)` stands in for `.headline`: same size and Dynamic
/// Type scaling as the system style, so layouts don't change size.
public enum PlexFont {
    public static let family = "DEKIRU Sans"

    private static let registered: Bool = {
        let urls = Bundle.module.urls(forResourcesWithExtension: "ttf", subdirectory: "Fonts") ?? []
        for url in urls {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
        return !urls.isEmpty
    }()

    /// Registers the fonts (once). Safe to call any number of times.
    @discardableResult
    public static func register() -> Bool { registered }

    /// The system's default point size for a text style, so Plex matches it.
    static func size(for style: Font.TextStyle) -> CGFloat {
        #if os(macOS)
        switch style {
        case .largeTitle: 26
        case .title: 22
        case .title2: 17
        case .title3: 15
        case .headline, .body: 13
        case .callout: 12
        case .subheadline: 11
        default: 10 // footnote, caption, caption2
        }
        #else
        switch style {
        case .largeTitle: 34
        case .title: 28
        case .title2: 22
        case .title3: 20
        case .headline, .body: 17
        case .callout: 16
        case .subheadline: 15
        case .footnote: 13
        case .caption: 12
        default: 11 // caption2
        }
        #endif
    }
}

public extension Font {
    /// IBM Plex Sans at a text style's size, scaling with Dynamic Type (and,
    /// on the Mac, with the window through `UIScale`).
    /// Headline is semibold, as the system's is.
    static func plex(_ style: Font.TextStyle) -> Font {
        PlexFont.register()
        let font = Font.custom(PlexFont.family, size: PlexFont.size(for: style).scaled, relativeTo: style)
        return style == .headline ? font.weight(.semibold) : font
    }

    /// IBM Plex Sans at a fixed size (for `.system(size:weight:design:)` uses),
    /// scaled on the Mac by `UIScale`.
    static func plex(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        PlexFont.register()
        return Font.custom(PlexFont.family, size: size.scaled).weight(weight)
    }
}

#if canImport(UIKit)
import UIKit

public extension PlexFont {
    /// Navigation titles, tab-bar labels and bar buttons are drawn by UIKit,
    /// not SwiftUI: give them Plex too. Call once at launch.
    @MainActor
    static func applyToUIKitBars() {
        guard register() else { return }
        func font(_ style: UIFont.TextStyle, weight: UIFont.Weight) -> UIFont {
            let size = UIFont.preferredFont(forTextStyle: style).pointSize
            let name = switch weight {
            case .bold: "DekiruSans-Bold"
            case .semibold: "DekiruSans-SmBld"
            case .medium: "DekiruSans-Medm"
            default: "DekiruSans"
            }
            let base = UIFont(name: name, size: size) ?? .systemFont(ofSize: size, weight: weight)
            return UIFontMetrics(forTextStyle: style).scaledFont(for: base)
        }
        let nav = UINavigationBar.appearance()
        nav.titleTextAttributes = [.font: font(.headline, weight: .semibold)]
        nav.largeTitleTextAttributes = [.font: font(.largeTitle, weight: .bold)]
        UIBarButtonItem.appearance().setTitleTextAttributes([.font: font(.body, weight: .regular)], for: .normal)
        UITabBarItem.appearance().setTitleTextAttributes([.font: font(.caption2, weight: .medium)], for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes([.font: font(.footnote, weight: .medium)], for: .normal)
    }
}
#endif
