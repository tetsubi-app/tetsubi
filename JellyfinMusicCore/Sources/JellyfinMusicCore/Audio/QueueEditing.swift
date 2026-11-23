// MARK: - Queue editing — the queue screen's rows and the rules around it
//
// Pure helpers for the queue UI (Now Playing's queue, the Play prompt, the
// end times), so the index maths and wording are unit-tested. The Kotlin
// side (`gg.dekiru.musicplayer.playback.QueueEditor`) mirrors these rules.

import Foundation

// MARK: - The Play prompt

public extension PlayQueue {
    /// Pressing Play on an album or playlist (or a track in one) asks
    /// Replace queue / Play Next / Add to Queue when the queue holds
    /// anything beyond the current group: some upcoming entry was queued
    /// in another batch. Otherwise Play just replaces the queue.
    var needsPlayPrompt: Bool {
        guard let current = currentEntry else { return false }
        return upcomingEntries.contains { $0.batchId != current.batchId }
    }

    /// Something is queued after the current album or playlist (in play
    /// order, no wrap) and the queue isn't shuffled: Now Playing shows the
    /// album's end and the queue's end. Shuffled, the "album" is broken up,
    /// so only the queue's end shows.
    var hasMoreThanCurrentGroup: Bool {
        guard !shuffle, let ci = currentPlayIndex, let current = currentEntry else { return false }
        return entriesInPlayOrder[(ci + 1)...].contains { $0.batchId != current.batchId }
    }

    /// How many entries of the current group are left, the current one
    /// included (the run in play order).
    var currentGroupCount: Int {
        guard let ci = currentPlayIndex, let current = currentEntry else { return 0 }
        let order = entriesInPlayOrder
        var i = ci + 1
        while i < order.count, order[i].batchId == current.batchId { i += 1 }
        return i - ci
    }
}

// MARK: - End times

/// The end-times part of Now Playing's info line.
public enum QueueEndTimes {

    /// The label for the current group's end: "Album ends", "Playlist
    /// ends", "Song ends" (one queued song) or "Songs end".
    public static func groupLabel(kind: QueueGroup.Kind, count: Int) -> String {
        switch kind {
        case .album: "Album ends"
        case .playlist: "Playlist ends"
        case .tracks: count > 1 ? "Songs end" : "Song ends"
        }
    }

    /// What to show, widest first: the view shows the first that fits on
    /// one line. With more than the current group queued: "Album ends
    /// 13:02 · Queue ends 15:40", else (narrow) "Queue ends 15:40".
    /// Otherwise "Ends: 13:02" (the queue's end, which is the album's).
    public static func variants(groupLabel: String, groupEnd: String, queueEnd: String,
                                moreQueued: Bool) -> [String] {
        guard moreQueued else { return ["Ends: \(queueEnd)"] }
        return ["\(groupLabel) \(groupEnd) · Queue ends \(queueEnd)", "Queue ends \(queueEnd)"]
    }
}

// MARK: - The queue screen

/// The queue as Now Playing's queue editor shows it: the playing entry
/// pinned at the top (not part of `rows`), then what follows it in play
/// order (no wrap round for Repeat All). Unshuffled, the entries sit under
/// a header per album or playlist (a run of one batch); shuffled, a flat
/// list. Turns list positions into `PlayQueue.move(entryIds:toIndex:)`.
public struct QueueEditor: Equatable, Sendable {

    public struct Group: Equatable, Sendable, Identifiable {
        public var id: String { "group-" + (entries.first?.id ?? batchId) }
        public var batchId: String
        public var group: QueueGroup
        public var entries: [QueueEntry]
        /// Where its first entry is in `upcoming`.
        public var start: Int
        public var durationSeconds: Double { entries.reduce(0) { $0 + $1.track.durationSeconds } }
        public var entryIds: [String] { entries.map(\.id) }
    }

    public enum Row: Equatable, Sendable, Identifiable {
        /// A group header (album or playlist); `index` into `groups`.
        case header(index: Int, id: String)
        /// An entry; `position` is its place in `upcoming`.
        case entry(QueueEntry, position: Int)

        public var id: String {
            switch self {
            case .header(_, let id): id
            case .entry(let entry, _): entry.id
            }
        }
    }

    /// A move to hand to `PlayerManager.moveQueue(entryIds:toIndex:)`.
    public struct Move: Equatable, Sendable {
        public var entryIds: [String]
        public var toIndex: Int
    }

    public let current: QueueEntry?
    /// Entries after the current one in play order.
    public let upcoming: [QueueEntry]
    /// Empty while shuffled (a flat list).
    public let groups: [Group]
    public let rows: [Row]
    public let isShuffled: Bool
    /// The current entry's place in the play order.
    private let currentIndex: Int

    public init(queue: PlayQueue) {
        current = queue.currentEntry
        isShuffled = queue.shuffle
        currentIndex = queue.currentPlayIndex ?? -1
        let order = queue.entriesInPlayOrder
        upcoming = currentIndex >= 0 ? Array(order[(currentIndex + 1)...]) : []
        var groups: [Group] = []
        if !queue.shuffle {
            for (i, e) in upcoming.enumerated() {
                if let last = groups.last, last.batchId == e.batchId {
                    groups[groups.count - 1].entries.append(e)
                } else {
                    groups.append(Group(batchId: e.batchId, group: e.group, entries: [e], start: i))
                }
            }
        }
        self.groups = groups
        var rows: [Row] = []
        if queue.shuffle {
            rows = upcoming.enumerated().map { .entry($1, position: $0) }
        } else {
            for (gi, g) in groups.enumerated() {
                rows.append(.header(index: gi, id: g.id))
                for (k, e) in g.entries.enumerated() { rows.append(.entry(e, position: g.start + k)) }
            }
        }
        self.rows = rows
    }

    public var isEmpty: Bool { upcoming.isEmpty }

    /// Maniac Mode: the list is read-only (no drag, swipe, remove, clear or
    /// tap to play).
    public static func isReadOnly(maniacMode: Bool) -> Bool {
        !PlayQueue.canEdit(.move, maniacMode: maniacMode)
    }

    /// The entry ids a row stands for: a header's whole group, or one entry.
    public func entryIds(ofRow row: Int) -> [String] {
        guard rows.indices.contains(row) else { return [] }
        switch rows[row] {
        case .header(let index, _): return groups[index].entryIds
        case .entry(let entry, _): return [entry.id]
        }
    }

    /// The move for a drag in the list (`onMove`'s offsets, counted in
    /// `rows` before the move). A header drags its whole group, and lands
    /// between groups: dropped inside another group, it goes after that
    /// group when dragged down and before it when dragged up. Nil when
    /// nothing would move.
    public func move(fromRows source: IndexSet, toRow destination: Int) -> Move? {
        guard let first = source.first, rows.indices.contains(first) else { return nil }
        var moving: [String] = []
        var movesGroup = false
        for row in source where rows.indices.contains(row) {
            if case .header = rows[row] { movesGroup = true }
            moving.append(contentsOf: entryIds(ofRow: row))
        }
        let ids = Set(moving)
        // Entries above the drop point that stay.
        var p = 0
        for row in rows.indices where row < destination {
            if case .entry(let e, _) = rows[row], !ids.contains(e.id) { p += 1 }
        }
        if movesGroup {
            // The other groups' starts, once the moved entries are out.
            var boundaries: [Int] = [0]
            var count = 0
            for g in groups {
                let stays = g.entries.filter { !ids.contains($0.id) }.count
                guard stays > 0 else { continue }
                count += stays
                boundaries.append(count)
            }
            let down = destination > first
            p = down ? (boundaries.first { $0 >= p } ?? count) : (boundaries.last { $0 <= p } ?? 0)
        }
        let ordered = upcoming.map(\.id).filter { ids.contains($0) }
        return change(ordered, to: p)
    }

    /// "Move up": an entry one place up, a group above the group before it.
    public func moveUp(row: Int) -> Move? {
        guard rows.indices.contains(row) else { return nil }
        switch rows[row] {
        case .entry(let entry, let position):
            guard position > 0 else { return nil }
            return change([entry.id], to: position - 1)
        case .header(let index, _):
            guard index > 0 else { return nil }
            return change(groups[index].entryIds, to: groups[index - 1].start)
        }
    }

    /// "Move down": an entry one place down, a group below the group after it.
    public func moveDown(row: Int) -> Move? {
        guard rows.indices.contains(row) else { return nil }
        switch rows[row] {
        case .entry(let entry, let position):
            guard position + 1 < upcoming.count else { return nil }
            return change([entry.id], to: position + 1)
        case .header(let index, _):
            guard index + 1 < groups.count else { return nil }
            return change(groups[index].entryIds, to: groups[index].start + groups[index + 1].entries.count)
        }
    }

    /// `ids` to place `p` among the upcoming entries that stay, as an index
    /// in the whole play order (the current entry and those before it stay
    /// ahead). Nil if that's where they already are.
    private func change(_ ids: [String], to p: Int) -> Move? {
        guard !ids.isEmpty, currentIndex >= 0 else { return nil }
        let set = Set(ids)
        var rest = upcoming.map(\.id).filter { !set.contains($0) }
        let at = min(max(0, p), rest.count)
        rest.insert(contentsOf: ids, at: at)
        guard rest != upcoming.map(\.id) else { return nil }
        return Move(entryIds: ids, toIndex: currentIndex + 1 + at)
    }
}
