// MARK: - GridExportSettings — Recently Played ▸ Export grid's remembered choices

import Foundation
import Observation

/// The export's grid size and "Show album and artist", remembered across
/// launches. The keys and values are the same on Android.
@MainActor
@Observable
public final class GridExportSettings {
    public static let shared = GridExportSettings()

    /// 3, 4 or 5.
    static let sizeKey = "recentlyPlayed.exportSize"
    /// Bool.
    static let labelsKey = "recentlyPlayed.exportLabels"

    /// The chosen grid size (3, 4 or 5); 5 until changed.
    public var size: Int {
        didSet { defaults.set(size, forKey: Self.sizeKey) }
    }
    /// Album and artist written on each tile; off until changed.
    public var showsLabels: Bool {
        didSet { defaults.set(showsLabels, forKey: Self.labelsKey) }
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // Out-of-range saved values (another version's) fall back to 5.
        let saved = defaults.object(forKey: Self.sizeKey) as? Int
        size = saved.flatMap { GridExport.sizes.contains($0) ? $0 : nil } ?? GridExport.defaultSize
        showsLabels = defaults.object(forKey: Self.labelsKey) as? Bool ?? false
    }
}
