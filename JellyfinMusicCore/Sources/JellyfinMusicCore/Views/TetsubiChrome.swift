// MARK: - TetsubiChrome — page header, section header, tabs, list row, banner, empty state (docs/redesign.md section 4)

import SwiftUI

// MARK: - Buttons

/// A 40 pt round touch target for header icons (SF Symbol).
public struct HeaderIconButton: View {
    let label: String
    let systemImage: String
    let action: () -> Void

    public init(_ label: String, systemImage: String, action: @escaping () -> Void) {
        self.label = label
        self.systemImage = systemImage
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18.scaled, weight: .regular))
                .foregroundStyle(TetsubiColors.textPrimary)
                .frame(width: 40.scaled, height: 40.scaled)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .help(label)
    }
}

/// A pill, 32 high, 13 medium: the secondary action.
public struct ActionChip: View {
    let title: String
    var color: Color = TetsubiColors.textPrimary
    let action: () -> Void

    public init(_ title: String, color: Color = TetsubiColors.textPrimary, action: @escaping () -> Void) {
        self.title = title
        self.color = color
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(title)
                .font(TetsubiType.control)
                .foregroundStyle(color)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(height: 32.scaled)
                .overlay(Capsule().strokeBorder(color.opacity(0.4), lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// The primary button: 40 high pill, blue fill, white label. `working`
/// shows a spinner while the title keeps the button's width.
public struct PrimaryButton: View {
    let title: String
    var enabled = true
    var working = false
    var color: Color = TetsubiColors.blue
    /// Fills the width it is given (a form's Sign in).
    var fullWidth = false
    let action: () -> Void

    public init(_ title: String, enabled: Bool = true, working: Bool = false,
                color: Color = TetsubiColors.blue, fullWidth: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.working = working
        self.color = color
        self.fullWidth = fullWidth
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(title)
                .font(TetsubiType.rowTitle.weight(.semibold))
                .foregroundStyle(.white.opacity(working ? 0 : 1))
                .lineLimit(1)
                .padding(.horizontal, Spacing.xl)
                .frame(maxWidth: fullWidth ? .infinity : nil)
                .frame(height: 40.scaled)
                .background(color.opacity(enabled ? 1 : 0.5), in: Capsule())
                .overlay { if working { ProgressView().controlSize(.small).tint(.white) } }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled || working)
    }
}

/// Favourite is a star: outline when off, filled pink when on (replaces the heart).
public struct FavouriteStar: View {
    let favourite: Bool
    var size: CGFloat = 20
    let onToggle: () -> Void

    public init(favourite: Bool, size: CGFloat = 20, onToggle: @escaping () -> Void) {
        self.favourite = favourite
        self.size = size
        self.onToggle = onToggle
    }

    public var body: some View {
        Button(action: onToggle) {
            Image(systemName: favourite ? "star.fill" : "star")
                .font(.system(size: size.scaled))
                .foregroundStyle(favourite ? TetsubiColors.pink : TetsubiColors.textPrimary)
                .frame(width: 40.scaled, height: 40.scaled)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(favourite ? "Remove from favourites" : "Add to favourites")
    }
}

// MARK: - Headers

/// The top of a page: `title` (28 bold) on the left, `actions` on the right.
/// With `onBack` (detail pages) a back control comes first: chevron and "Back"
/// from medium up, the chevron alone on compact; the title is then usually
/// nil, because the big title sits in the content.
public struct PageHeader<Actions: View>: View {
    let title: String?
    let onBack: (() -> Void)?
    let actions: Actions

    @Environment(\.windowWidthClass) private var windowClass

    public init(_ title: String?, onBack: (() -> Void)? = nil, @ViewBuilder actions: () -> Actions) {
        self.title = title
        self.onBack = onBack
        self.actions = actions()
    }

    public var body: some View {
        HStack(spacing: Spacing.s) {
            if let onBack {
                Button(action: onBack) {
                    HStack(spacing: 0) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 18.scaled, weight: .semibold))
                            .frame(width: 40.scaled, height: 40.scaled)
                        if windowClass.isWide {
                            Text("Back").font(TetsubiType.rowTitle).padding(.trailing, Spacing.m)
                        }
                    }
                    .foregroundStyle(TetsubiColors.blue)
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back")
            }
            if let title {
                Text(title)
                    .font(TetsubiType.pageTitle)
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
            } else {
                Spacer(minLength: 0)
            }
            HStack(spacing: Spacing.s) { actions }
        }
        .padding(.horizontal, windowClass.pageMargin)
        .padding(.vertical, Spacing.s)
        .frame(minHeight: 56.scaled)
    }
}

public extension PageHeader where Actions == EmptyView {
    init(_ title: String?, onBack: (() -> Void)? = nil) {
        self.init(title, onBack: onBack) { EmptyView() }
    }
}

/// A detail page's big title (28 bold) in the content, under the header's back control.
public struct PageTitle: View {
    let title: String
    var subtitle: String?
    @Environment(\.windowWidthClass) private var windowClass

    public init(_ title: String, subtitle: String? = nil) {
        self.title = title
        self.subtitle = subtitle
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(TetsubiType.pageTitle).foregroundStyle(TetsubiColors.textPrimary).lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).font(TetsubiType.body).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, windowClass.pageMargin)
        .padding(.bottom, Spacing.xs)
    }
}

/// A section's title (20 semibold, sentence case) with an optional blue
/// "See all" on the right: a button (`onSeeAll`) or a `NavigationLink(value:)`
/// (`seeAllValue`).
public struct SectionHeader: View {
    let title: String
    let seeAll: AnyView?
    /// Side room; the page margin unless the header sits inside something that already has it.
    var horizontalPadding: CGFloat?
    @Environment(\.windowWidthClass) private var windowClass

    public init(_ title: String, horizontalPadding: CGFloat? = nil, onSeeAll: (() -> Void)? = nil) {
        self.title = title
        self.horizontalPadding = horizontalPadding
        if let onSeeAll {
            seeAll = AnyView(Button(action: onSeeAll) { Self.seeAllLabel }.buttonStyle(.plain)
                .accessibilityLabel("See all \(title)"))
        } else {
            seeAll = nil
        }
    }

    public init<Value: Hashable>(_ title: String, horizontalPadding: CGFloat? = nil, seeAllValue: Value) {
        self.title = title
        self.horizontalPadding = horizontalPadding
        seeAll = AnyView(NavigationLink(value: seeAllValue) { Self.seeAllLabel }.buttonStyle(.plain)
            .accessibilityLabel("See all \(title)"))
    }

    private static var seeAllLabel: some View {
        HStack(spacing: 2) {
            Text("See all").font(TetsubiType.body)
            Image(systemName: "chevron.right").font(.system(size: 12.scaled, weight: .semibold))
        }
        .foregroundStyle(TetsubiColors.blue)
        .padding(.leading, Spacing.s)
        .padding(.vertical, Spacing.xs)
        .contentShape(Rectangle())
    }

    public var body: some View {
        HStack {
            Text(title)
                .font(TetsubiType.sectionTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            if let seeAll { seeAll }
        }
        .padding(.horizontal, horizontalPadding ?? windowClass.pageMargin)
        .padding(.vertical, Spacing.s)
    }
}

// MARK: - Tabs

/// Library-style tabs: 15 pt, the selected one white bold with a 3 pt pink
/// underline, left aligned, a hairline under the row.
public struct TetsubiTabs: View {
    let titles: [String]
    @Binding var selection: Int
    @Environment(\.windowWidthClass) private var windowClass

    public init(_ titles: [String], selection: Binding<Int>) {
        self.titles = titles
        _selection = selection
    }

    public var body: some View {
        HStack(alignment: .top, spacing: windowClass == .compact ? Spacing.m : Spacing.xl) {
            ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                let selected = index == selection
                Button { selection = index } label: {
                    VStack(spacing: 10) {
                        Text(title)
                            .font(TetsubiType.rowTitle.weight(selected ? .bold : .medium))
                            .foregroundStyle(selected ? TetsubiColors.textPrimary : TetsubiColors.textSecondary)
                            .lineLimit(1)
                            .fixedSize()
                        UnevenRoundedRectangle(topLeadingRadius: 2, topTrailingRadius: 2)
                            .fill(selected ? TetsubiColors.pink : Color.clear)
                            .frame(height: 3)
                    }
                    .padding(.top, Spacing.m)
                    .fixedSize(horizontal: true, vertical: false)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, windowClass.pageMargin)
        .overlay(alignment: .bottom) { Hairline() }
    }
}

/// A 0.5 pt (hairline token) horizontal rule.
public struct Hairline: View {
    public init() {}
    public var body: some View {
        Rectangle().fill(TetsubiColors.hairline).frame(height: 0.5)
    }
}

// MARK: - List row

/// One list row: 56 high, an optional 40 pt `art` (radius 6) or `leadingNumber`
/// (tracks), title 15 medium, subtitle 12 secondary, `trailingMeta` 13 secondary
/// and a `trailing` slot (the ellipsis menu), and a hairline inset to the text.
/// `current` tints the row pink (the playing track).
public struct ListRow<Art: View, Trailing: View>: View {
    let title: String
    var subtitle: String?
    var leadingNumber: String?
    var trailingMeta: String?
    var current = false
    var divider = true
    /// Artists: the picture is a circle.
    var artCircle = false
    var horizontalPadding: CGFloat?
    var onTap: (() -> Void)?
    let art: Art?
    let trailing: Trailing?

    @Environment(\.windowWidthClass) private var windowClass

    public init(title: String, subtitle: String? = nil, leadingNumber: String? = nil, trailingMeta: String? = nil,
                current: Bool = false, divider: Bool = true, artCircle: Bool = false,
                horizontalPadding: CGFloat? = nil, onTap: (() -> Void)? = nil,
                @ViewBuilder art: () -> Art, @ViewBuilder trailing: () -> Trailing) {
        self.title = title; self.subtitle = subtitle; self.leadingNumber = leadingNumber
        self.trailingMeta = trailingMeta; self.current = current; self.divider = divider
        self.artCircle = artCircle; self.horizontalPadding = horizontalPadding; self.onTap = onTap
        self.art = art(); self.trailing = trailing()
    }

    public var body: some View {
        let margin = horizontalPadding ?? windowClass.pageMargin
        // The pink band is 8 wider than the content, so text still lines up with the margin.
        let outer = max(0, margin - 8)
        let inner = margin - outer
        let textInset: CGFloat = inner + (art != nil ? 52.scaled : leadingNumber != nil ? 40.scaled : 0)
        let main = HStack(spacing: Spacing.m) {
            if let art {
                art
                    .frame(width: 40.scaled, height: 40.scaled)
                    .background(TetsubiColors.art)
                    .clipShape(RoundedRectangle(cornerRadius: artCircle ? 20.scaled : Radii.thumb))
            } else if let leadingNumber {
                Text(leadingNumber)
                    .font(TetsubiType.body).foregroundStyle(TetsubiColors.textSecondary)
                    .frame(width: 28.scaled)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(TetsubiType.rowTitle).foregroundStyle(TetsubiColors.textPrimary).lineLimit(1)
                if let subtitle {
                    Text(subtitle).font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let trailingMeta {
                Text(trailingMeta).font(TetsubiType.control).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
            }
        }
        .padding(.vertical, Spacing.s)
        .frame(maxWidth: .infinity, minHeight: 56.scaled)
        .contentShape(Rectangle())
        // The tap covers the row's content only: a trailing menu is a sibling, never inside the button.
        let row = HStack(spacing: Spacing.m) {
            if let onTap {
                Button(action: onTap) { main }.buttonStyle(.plain)
            } else {
                main
            }
            if let trailing { trailing }
        }
        .padding(.horizontal, inner)
        .background(current ? TetsubiColors.pinkTint : Color.clear, in: RoundedRectangle(cornerRadius: Radii.thumb))
        .overlay(alignment: .bottom) {
            if divider && !current { Hairline().padding(.leading, textInset).padding(.trailing, inner) }
        }
        .padding(.horizontal, outer)
        row
    }
}

public extension ListRow where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, leadingNumber: String? = nil, trailingMeta: String? = nil,
         current: Bool = false, divider: Bool = true, artCircle: Bool = false,
         horizontalPadding: CGFloat? = nil, onTap: (() -> Void)? = nil, @ViewBuilder art: () -> Art) {
        self.init(title: title, subtitle: subtitle, leadingNumber: leadingNumber, trailingMeta: trailingMeta,
                  current: current, divider: divider, artCircle: artCircle, horizontalPadding: horizontalPadding,
                  onTap: onTap, art: art) { EmptyView() }
    }
}

public extension ListRow where Art == EmptyView {
    init(title: String, subtitle: String? = nil, leadingNumber: String? = nil, trailingMeta: String? = nil,
         current: Bool = false, divider: Bool = true,
         horizontalPadding: CGFloat? = nil, onTap: (() -> Void)? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title; self.subtitle = subtitle; self.leadingNumber = leadingNumber
        self.trailingMeta = trailingMeta; self.current = current; self.divider = divider
        self.horizontalPadding = horizontalPadding; self.onTap = onTap
        self.art = nil; self.trailing = trailing()
    }
}

public extension ListRow where Art == EmptyView, Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, leadingNumber: String? = nil, trailingMeta: String? = nil,
         current: Bool = false, divider: Bool = true,
         horizontalPadding: CGFloat? = nil, onTap: (() -> Void)? = nil) {
        self.title = title; self.subtitle = subtitle; self.leadingNumber = leadingNumber
        self.trailingMeta = trailingMeta; self.current = current; self.divider = divider
        self.horizontalPadding = horizontalPadding; self.onTap = onTap
        self.art = nil; self.trailing = nil
    }
}

// MARK: - Banner, empty state, spinner

/// What a `Banner` is about; picks the real icon.
public enum BannerKind: Sendable {
    case warning, offline, update

    var systemImage: String {
        switch self {
        case .warning: "exclamationmark.triangle"
        case .offline: "wifi.slash"
        case .update: "arrow.down.circle"
        }
    }
    var tint: Color { self == .update ? TetsubiColors.blue : TetsubiColors.pink }
}

/// The one banner style (update, offline, error): rounded 16 surface, icon,
/// text, an optional outlined action chip.
public struct Banner: View {
    let message: String
    var kind: BannerKind = .warning
    var actionTitle: String?
    var onAction: (() -> Void)?
    var horizontalPadding: CGFloat?
    @Environment(\.windowWidthClass) private var windowClass

    public init(_ message: String, kind: BannerKind = .warning, actionTitle: String? = nil,
                horizontalPadding: CGFloat? = nil, onAction: (() -> Void)? = nil) {
        self.message = message
        self.kind = kind
        self.actionTitle = actionTitle
        self.horizontalPadding = horizontalPadding
        self.onAction = onAction
    }

    public var body: some View {
        HStack(spacing: Spacing.m) {
            Image(systemName: kind.systemImage)
                .font(.system(size: 20.scaled))
                .foregroundStyle(kind.tint)
            Text(message)
                .font(TetsubiType.body)
                .foregroundStyle(TetsubiColors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle, let onAction { ActionChip(actionTitle, action: onAction) }
        }
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
        .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
        .padding(.horizontal, horizontalPadding ?? windowClass.pageMargin)
        .padding(.vertical, Spacing.s)
        .accessibilityElement(children: .combine)
    }
}

extension Banner {
    /// What a page says while the device is offline.
    static let offlineMessage = "You're offline. Downloaded albums still play from the Downloads tab."

    /// Shown on screens that need the server while the device is offline.
    static var offline: Banner { Banner(offlineMessage, kind: .offline) }
}

/// The one empty state: 48 icon, title, message, optional action.
public struct EmptyState: View {
    let title: String
    var message: String?
    var systemImage = "music.note"
    var actionTitle: String?
    var onAction: (() -> Void)?

    public init(_ title: String, message: String? = nil, systemImage: String = "music.note",
                actionTitle: String? = nil, onAction: (() -> Void)? = nil) {
        self.title = title
        self.message = message
        self.systemImage = systemImage
        self.actionTitle = actionTitle
        self.onAction = onAction
    }

    public var body: some View {
        VStack(spacing: Spacing.s) {
            Image(systemName: systemImage)
                .font(.system(size: 40.scaled))
                .foregroundStyle(TetsubiColors.textTertiary)
                .frame(height: 48.scaled)
            Text(title).font(TetsubiType.sectionTitle).foregroundStyle(TetsubiColors.textPrimary)
                .multilineTextAlignment(.center)
            if let message {
                Text(message).font(TetsubiType.body).foregroundStyle(TetsubiColors.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if let actionTitle, let onAction {
                ActionChip(actionTitle, action: onAction).padding(.top, Spacing.s)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Spacing.xxl)
        .padding(.vertical, Spacing.xxl)
    }
}

/// The one spinner: 24, centred at the top of the space it fills.
public struct LoadingSpinner: View {
    public init() {}
    public var body: some View {
        ProgressView()
            .controlSize(.regular)
            .tint(TetsubiColors.textSecondary)
            .frame(width: 24, height: 24)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.xxl)
    }
}

// MARK: - Density and sort sheet

/// Segmented S / M / L for the card density: the selected segment is blue-tinted with blue text.
public struct DensityControl: View {
    @Binding var density: Density

    public init(density: Binding<Density>) { _density = density }

    /// Bound to the global `DensityStore`.
    public init() {
        _density = Binding(get: { DensityStore.shared.density }, set: { DensityStore.shared.density = $0 })
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(Density.allCases) { option in
                let selected = option == density
                Button { density = option } label: {
                    Text(option.label)
                        .font(TetsubiType.control)
                        .foregroundStyle(selected ? TetsubiColors.blue : TetsubiColors.textSecondary)
                        .frame(width: 36.scaled, height: 28.scaled)
                        .background(selected ? TetsubiColors.blueTint : Color.clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Card size \(option.label)")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(2)
        .background(TetsubiColors.surface, in: Capsule())
    }
}

/// Compact pages put sort, view, density and filter in one sheet. This hosts
/// whatever the page passes in `content`; add a `DensityControl` and the page's
/// own controls there. Present it with `.sheet`.
public struct SortAndViewSheet<Content: View>: View {
    let title: String
    let content: Content
    @Environment(\.dismiss) private var dismiss

    public init(title: String = "Sort & view", @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            HStack {
                Text(title).font(TetsubiType.sectionTitle).foregroundStyle(TetsubiColors.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button("Done") { dismiss() }
                    .font(TetsubiType.rowTitle).foregroundStyle(TetsubiColors.blue).buttonStyle(.plain)
            }
            content
            Spacer(minLength: 0)
        }
        .padding(Spacing.l)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(TetsubiColors.navy)
        .foregroundStyle(TetsubiColors.textPrimary)
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
        #if os(macOS)
        // A Mac sheet takes its content's ideal size, which was wider than a
        // narrow window and cut off both sides.
        .frame(minWidth: 300, idealWidth: 340, maxWidth: 340, minHeight: 440)
        #endif
    }
}

/// The compact header button that opens a `SortAndViewSheet`.
public struct SortAndViewButton: View {
    let action: () -> Void
    public init(action: @escaping () -> Void) { self.action = action }
    public var body: some View {
        HeaderIconButton("Sort and view", systemImage: "slider.horizontal.3", action: action)
    }
}

// MARK: - Settings-style groups

/// A settings-style group: `title` section header, a rounded (16) container of
/// rows, an optional `footer` paragraph under it.
public struct SettingsGroup<Content: View>: View {
    let title: String?
    let footer: Text?
    let content: Content
    @Environment(\.windowWidthClass) private var windowClass

    public init(title: String? = nil, footer: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.footer = footer.map { Text($0) }
        self.content = content()
    }

    /// With a styled footer (About's "QA team" heading).
    public init(title: String? = nil, footerText: Text, @ViewBuilder content: () -> Content) {
        self.title = title
        self.footer = footerText
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title { SectionHeader(title) }
            VStack(spacing: 0) { content }
                .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
                .clipShape(RoundedRectangle(cornerRadius: Radii.panel))
                .padding(.horizontal, windowClass.pageMargin)
            if let footer {
                footer
                    .font(TetsubiType.caption)
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .padding(.horizontal, windowClass.pageMargin + Spacing.l)
                    .padding(.vertical, Spacing.s)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

/// A 52 high settings row: `title` (with an optional `caption` under it) on
/// the left, `trailing` (a value, a switch, a chevron) on the right. `divider`
/// draws the hairline below (leave it off on a group's last row).
public struct SettingsRow<Trailing: View>: View {
    let title: String
    var caption: String?
    var divider = true
    var titleColor = TetsubiColors.textPrimary
    var onTap: (() -> Void)?
    let trailing: Trailing

    public init(_ title: String, caption: String? = nil, divider: Bool = true,
                titleColor: Color = TetsubiColors.textPrimary, onTap: (() -> Void)? = nil,
                @ViewBuilder trailing: () -> Trailing) {
        self.title = title; self.caption = caption; self.divider = divider
        self.titleColor = titleColor; self.onTap = onTap; self.trailing = trailing()
    }

    public var body: some View {
        let row = HStack(spacing: Spacing.m) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(TetsubiType.rowTitle.weight(.regular)).foregroundStyle(titleColor)
                if let caption {
                    Text(caption).font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.s)
        .frame(minHeight: 52.scaled)
        .overlay(alignment: .bottom) { if divider { Hairline().padding(.leading, Spacing.l) } }
        .contentShape(Rectangle())
        if let onTap {
            Button(action: onTap) { row }.buttonStyle(.plain)
        } else {
            row
        }
    }
}

/// A `SettingsRow` with a switch (green when on).
public struct SettingsToggleRow: View {
    let title: String
    @Binding var isOn: Bool
    var caption: String?
    var divider = true

    public init(_ title: String, isOn: Binding<Bool>, caption: String? = nil, divider: Bool = true) {
        self.title = title; _isOn = isOn; self.caption = caption; self.divider = divider
    }

    public var body: some View {
        SettingsRow(title, caption: caption, divider: divider) {
            Toggle(title, isOn: $isOn).labelsHidden().toggleStyle(.switch).tint(TetsubiColors.green)
        }
    }
}

/// A `SettingsRow` that opens a menu of choices (the value and an up/down chevron on the right).
public struct SettingsMenuRow<Value: Hashable>: View {
    let title: String
    let options: [(value: Value, label: String)]
    @Binding var selection: Value
    var divider = true

    public init(_ title: String, selection: Binding<Value>, options: [(value: Value, label: String)], divider: Bool = true) {
        self.title = title; _selection = selection; self.options = options; self.divider = divider
    }

    public var body: some View {
        let current = options.first { $0.value == selection }?.label ?? ""
        SettingsRow(title, divider: divider) {
            Menu {
                ForEach(options.indices, id: \.self) { index in
                    let option = options[index]
                    Button {
                        selection = option.value
                    } label: {
                        if option.value == selection {
                            Label(option.label, systemImage: "checkmark")
                        } else {
                            Text(option.label)
                        }
                    }
                }
            } label: {
                HStack(spacing: Spacing.xs) {
                    SettingsValue(current)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 12.scaled, weight: .semibold))
                        .foregroundStyle(TetsubiColors.textSecondary)
                }
                .contentShape(Rectangle())
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.plain)
            .fixedSize()
            .accessibilityLabel("\(title), \(current)")
        }
    }
}

/// A value on the right of a `SettingsRow`.
public struct SettingsValue: View {
    let text: String
    public init(_ text: String) { self.text = text }
    public var body: some View {
        Text(text).font(TetsubiType.body).foregroundStyle(TetsubiColors.textSecondary).lineLimit(1)
    }
}
