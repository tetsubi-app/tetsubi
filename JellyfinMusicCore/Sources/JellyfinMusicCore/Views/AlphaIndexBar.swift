import SwiftUI

/// Buckets titles into the A-Z index: `#` (digits and symbols) first, then
/// A–Z, then `あ` for every other script (Japanese, Cyrillic, Greek…).
public enum AlphaIndex {
    public static let other = "あ"
    public static let labels: [String] =
        ["#"] + "ABCDEFGHIJKLMNOPQRSTUVWXYZ".map(String.init) + [other]

    /// Lower-cased and accent-stripped, so "Émile" files under E.
    public static func folded(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
    }

    public static func bucket(for title: String) -> String {
        guard let first = folded(title).first else { return "#" }
        if let ascii = first.asciiValue, first.isLetter {
            return String(UnicodeScalar(ascii)).uppercased()
        }
        return first.isLetter ? other : "#"
    }

    /// Position of the title's bucket in `labels`; used to keep sort order
    /// aligned with the index.
    public static func rank(for title: String) -> Int {
        labels.firstIndex(of: bucket(for: title)) ?? 0
    }
}

public extension AlphaIndex {
    /// The first item's ID under each letter of the strip (`key` is the text it is filed by).
    static func firstIDs<T>(_ items: [T], key: (T) -> String, id: (T) -> String) -> [String: String] {
        var result: [String: String] = [:]
        for item in items {
            let bucket = bucket(for: key(item))
            if result[bucket] == nil { result[bucket] = id(item) }
        }
        return result
    }

    /// Where a tap on `label` goes: that letter's first item, or the next
    /// letter's that has one, or the last one there is.
    static func target(for label: String, in firstIDs: [String: String]) -> String? {
        let start = labels.firstIndex(of: label) ?? 0
        let letter = labels[start...].first { firstIDs[$0] != nil } ?? labels.last { firstIDs[$0] != nil }
        return letter.flatMap { firstIDs[$0] }
    }
}

/// A vertical A–Z strip. Tap or drag to jump; letters with no albums are dimmed.
struct AlphaIndexBar: View {
    let available: Set<String>
    let onSelect: (String) -> Void

    /// 22pt, grown on a big Mac window (`UIScale`).
    static var width: CGFloat { 22.scaled }

    @State private var lastSelected: String?

    var body: some View {
        GeometryReader { geo in
            let count = AlphaIndex.labels.count
            let cell = geo.size.height / CGFloat(count)
            // `.plex(size:)` scales by the factor itself, so size the
            // letters against the cell in unscaled points.
            let factor = UIScale.shared.factor
            VStack(spacing: 0) {
                ForEach(AlphaIndex.labels, id: \.self) { label in
                    Text(label)
                        .font(.plex(size: min(11, max(7, cell * 0.7 / factor)), weight: .semibold))
                        .foregroundStyle(available.contains(label) ? Color.appBlue : Color.white.opacity(0.25))
                        .frame(maxWidth: .infinity, minHeight: cell, maxHeight: cell)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let i = min(max(Int(value.location.y / cell), 0), count - 1)
                        let label = AlphaIndex.labels[i]
                        guard label != lastSelected else { return }
                        lastSelected = label
                        onSelect(label)
                    }
                    .onEnded { _ in lastSelected = nil }
            )
        }
        .frame(width: Self.width)
        .padding(.vertical, 8.scaled)
        .accessibilityLabel("Alphabetical index")
    }
}
