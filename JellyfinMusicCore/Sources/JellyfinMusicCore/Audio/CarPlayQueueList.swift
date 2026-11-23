// MARK: - CarPlayQueueList — the queue as CarPlay's "Up Next" list shows it
//
// Pure: sections and rows built from a `PlayQueue`, within CarPlay's list
// limits (`CPListTemplate.maximumItemCount` across all sections and
// `maximumSectionCount`), so the CarPlay scene only turns them into
// `CPListSection`s and `CPListItem`s. Same contents as Now Playing's queue
// screen (`QueueEditor`): what follows the playing entry in play order, no
// Repeat All wrap, played entries not shown.

import Foundation

public struct CarPlayQueueList: Equatable, Sendable {

    public struct Row: Equatable, Sendable {
        public enum Kind: Equatable, Sendable {
            /// A queued track; tapping it skips to `entryId` (unless read-only).
            case entry(entryId: String)
            /// "…and N more": the tracks past CarPlay's limits.
            case more(count: Int)
            /// Maniac Mode's read-only note.
            case note
        }
        public var kind: Kind
        public var text: String
        public var detail: String?
    }

    public struct Section: Equatable, Sendable {
        /// "Album · Artist" (or "Shuffled"); nil for the note's section.
        public var header: String?
        /// The group's time ("47m 12s").
        public var headerDetail: String?
        public var rows: [Row]
    }

    // Wording (the phone's queue screen uses the same).
    public static let title = "Queue"
    public static let maniacNote = "Maniac Mode: the queue plays in order."
    public static let shuffledHeader = "Shuffled"
    public static let emptyTitle = "Nothing queued"
    public static let emptySubtitle = "Use Play Next or Add to Queue on your iPhone."

    public let sections: [Section]
    /// Maniac Mode: rows show but tapping does nothing.
    public let isReadOnly: Bool
    /// Upcoming tracks not shown (counted in the "…and N more" row).
    public let hiddenCount: Int
    /// Nothing after the playing track.
    public let isEmpty: Bool

    /// - Parameters:
    ///   - maxItems: items allowed across all sections (`CPListTemplate.maximumItemCount`).
    ///   - maxSections: sections allowed (`CPListTemplate.maximumSectionCount`).
    public init(queue: PlayQueue, maniacMode: Bool, maxItems: Int, maxSections: Int) {
        let editor = QueueEditor(queue: queue)
        let readOnly = QueueEditor.isReadOnly(maniacMode: maniacMode)
        isReadOnly = readOnly
        isEmpty = editor.isEmpty

        // One block per album/playlist group, or one block in play order while shuffled.
        var blocks: [Section] = []
        if editor.isShuffled {
            if !editor.upcoming.isEmpty {
                let seconds = editor.upcoming.reduce(0) { $0 + $1.track.durationSeconds }
                blocks.append(Section(header: Self.shuffledHeader, headerDetail: Self.groupTime(seconds),
                                      rows: editor.upcoming.map { Self.row($0, shuffled: true) }))
            }
        } else {
            for g in editor.groups {
                blocks.append(Section(header: Self.header(g.group), headerDetail: Self.groupTime(g.durationSeconds),
                                      rows: g.entries.map { Self.row($0, shuffled: false) }))
            }
        }

        var sections: [Section] = []
        var items = max(1, maxItems)
        var sectionsLeft = max(1, maxSections)
        // The note gets its own section, and only when there's a queue to explain.
        if readOnly, !editor.isEmpty, items > 1, sectionsLeft > 1 {
            sections.append(Section(header: nil, headerDetail: nil,
                                    rows: [Row(kind: .note, text: Self.maniacNote, detail: nil)]))
            items -= 1
            sectionsLeft -= 1
        }

        let total = editor.upcoming.count
        if total <= items, blocks.count <= sectionsLeft {
            sections += blocks
            hiddenCount = 0
        } else {
            // Leave one item for "…and N more".
            var budget = items - 1
            var shown = 0
            for block in blocks where sectionsLeft > 0 && budget > 0 {
                let take = min(block.rows.count, budget)
                var cut = block
                cut.rows = Array(block.rows.prefix(take))
                sections.append(cut)
                budget -= take
                sectionsLeft -= 1
                shown += take
            }
            let hidden = total - shown
            hiddenCount = hidden
            let more = Row(kind: .more(count: hidden), text: "…and \(hidden) more", detail: nil)
            if shown > 0 {
                sections[sections.count - 1].rows.append(more)
            } else {
                sections.append(Section(header: nil, headerDetail: nil, rows: [more]))
            }
        }
        self.sections = sections
    }

    /// Items across all sections.
    public var itemCount: Int { sections.reduce(0) { $0 + $1.rows.count } }

    // MARK: Text

    /// "Album · Artist" ("Album" with no title, as the phone's header).
    static func header(_ group: QueueGroup) -> String {
        let title = group.title.isEmpty ? "Album" : group.title
        guard let artist = group.artist, !artist.isEmpty else { return title }
        return "\(title) · \(artist)"
    }

    /// Unshuffled: the artist when it isn't the group's, and the length.
    /// Shuffled: "Artist · Album" (the headers can't say which album).
    static func row(_ entry: QueueEntry, shuffled: Bool) -> Row {
        let t = entry.track
        let parts: [String]
        if shuffled {
            parts = [t.artist, t.album]
        } else {
            parts = [t.artist != (entry.group.artist ?? "") ? t.artist : "", trackTime(t.durationSeconds) ?? ""]
        }
        let detail = parts.filter { !$0.isEmpty }.joined(separator: " · ")
        return Row(kind: .entry(entryId: entry.id), text: t.title.isEmpty ? "Track" : t.title,
                   detail: detail.isEmpty ? nil : detail)
    }

    /// "3:45"; nil without a length.
    static func trackTime(_ seconds: Double) -> String? {
        guard seconds > 0 else { return nil }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// "47m 12s", or "1h 45m 05s" from an hour up (Now Playing's album length); nil without a length.
    static func groupTime(_ seconds: Double) -> String? {
        guard seconds > 0 else { return nil }
        let total = Int(seconds.rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%dh %02dm %02ds", h, m, s) : String(format: "%dm %02ds", m, s)
    }
}
