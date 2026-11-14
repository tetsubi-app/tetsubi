import Foundation

/// Cleans track names that are really file names, e.g. "01 - BrokenMan - Follow".
public enum TrackTitle {
    /// Strips a leading "NN - " track number, then a leading "<artist> - " when it
    /// matches one of `artists`. Never returns an empty title.
    public static func clean(_ raw: String, artists: [String]) -> (title: String, number: Int?) {
        var title = raw.trimmingCharacters(in: .whitespaces)
        var number: Int?

        if let r = title.range(of: #"^\d{1,3}\s*[-–.]\s+"#, options: .regularExpression) {
            number = Int(title[..<r.upperBound].filter(\.isNumber))
            title.removeSubrange(r)
        }
        for artist in artists where !artist.isEmpty {
            for sep in [" - ", " – "] {
                let prefix = artist + sep
                if title.range(of: prefix, options: [.anchored, .caseInsensitive]) != nil {
                    title.removeFirst(prefix.count)
                    break
                }
            }
        }
        title = title.trimmingCharacters(in: .whitespaces)
        return title.isEmpty ? (raw, nil) : (title, number)
    }
}
