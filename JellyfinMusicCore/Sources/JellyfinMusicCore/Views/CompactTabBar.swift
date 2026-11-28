// MARK: - CompactTabBar — the phone-style bottom tab bar for a narrow Mac window

import SwiftUI

/// What tapping an item does: pure, so it can be tested.
public enum CompactTabBarLogic {
    /// The tabs in bar order, left to right.
    public static let tabs: [AppTab] = AppTab.allCases

    /// Tapping the selected tab pops it to its root; any other tab selects it.
    public static func tap(_ tab: AppTab, current: AppTab) -> (selection: AppTab, popsToRoot: Bool) {
        (tab, tab == current)
    }
}

/// Five equal items, icon over a small label, the selected one in the accent
/// colour, a hairline above. Used on the Mac in a compact window only (the
/// native tab bar collapses into a toolbar overflow menu there).
public struct CompactTabBar: View {
    @Binding var selection: AppTab
    /// Called when the already-selected tab is tapped.
    var onReselect: (AppTab) -> Void

    public init(selection: Binding<AppTab>, onReselect: @escaping (AppTab) -> Void) {
        _selection = selection
        self.onReselect = onReselect
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(CompactTabBarLogic.tabs, id: \.self) { tab in
                item(tab)
            }
        }
        .frame(height: 56.scaled)
        .background(Color.appBackground.opacity(0.85))
        .overlay(alignment: .top) { Hairline() }
    }

    private func item(_ tab: AppTab) -> some View {
        let selected = tab == selection
        return Button {
            let result = CompactTabBarLogic.tap(tab, current: selection)
            if result.popsToRoot { onReselect(tab) }
            selection = result.selection
        } label: {
            VStack(spacing: 2.scaled) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 22.scaled))
                    .frame(height: 26.scaled)
                Text(LocalizedStringKey(tab.title))
                    .font(TetsubiType.navLabel)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(selected ? TetsubiColors.blue : TetsubiColors.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(tab.title)))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
