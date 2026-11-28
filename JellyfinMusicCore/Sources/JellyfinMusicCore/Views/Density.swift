// MARK: - Density — the one card size for every grid and shelf (docs/redesign.md section 3)

import SwiftUI
import Observation

/// The smallest a card may be, in points: S 104, M 148 (default), L 200.
/// Same names and storage as Kotlin's `Density` (`ui.density`, "S" / "M" / "L").
public enum Density: String, CaseIterable, Identifiable, Sendable {
    case s = "S", m = "M", l = "L"

    public var id: String { rawValue }
    public var label: String { rawValue }
    public var minCard: CGFloat {
        switch self {
        case .s: 104
        case .m: 148
        case .l: 200
        }
    }

    public static let `default`: Density = .m

    /// Unknown or missing values are the default.
    public static func parse(_ value: String?) -> Density {
        value.flatMap(Density.init(rawValue:)) ?? .default
    }
}

/// The one remembered density, for every grid. Stored as "S", "M" or "L"
/// under `ui.density`. The old `library.gridColumns` (`GridZoom`) value is not
/// read: a column count has no equivalent, so everyone starts at M.
@MainActor
@Observable
public final class DensityStore {
    public static let shared = DensityStore()
    public static let key = "ui.density"

    public var density: Density {
        didSet { defaults.set(density.rawValue, forKey: Self.key) }
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        density = Density.parse(defaults.string(forKey: Self.key))
    }
}
