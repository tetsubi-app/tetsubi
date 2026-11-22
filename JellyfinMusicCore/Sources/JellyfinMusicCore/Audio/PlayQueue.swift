// MARK: - PlayQueue — the listening queue (pure model)
//
// A queue of entries from several albums, playlists or single tracks, with
// shuffle across the whole queue and repeat. Pure value types: no player, no
// network, no disk (see PlayQueueStore for saving). The Kotlin side
// (`gg.dekiru.musicplayer.playback`) mirrors these names and rules.
//
// Two orders:
//  - natural order (`entries`): the order things were queued in, which the
//    queue returns to when shuffle goes off;
//  - play order (`playOrder`, entry ids): what plays. Equal to the natural
//    order while shuffle is off; a shuffle of it while on.
//
// Invariants: entry ids are unique; `playOrder` holds every entry id once;
// `currentEntryId` is nil exactly when the queue is empty.

import Foundation

// MARK: - QueueTrack

/// What the queue keeps about a track: enough to show it offline and to
/// rebuild its stream or find its download later. No URLs (server
/// addresses and container paths change between launches).
public struct QueueTrack: Codable, Equatable, Hashable, Sendable {
    /// Jellyfin item id; the stream URL and the download are found by it.
    public var id: String
    public var title: String
    /// Album artist, as `PlaybackTrack.artist`.
    public var artist: String
    public var artistId: String?
    public var album: String
    public var albumId: String
    /// The item whose picture is shown (the track's own, else the album).
    public var artItemId: String
    public var durationSeconds: Double
    public var trackNumber: Int
    public var discNumber: Int?
    /// The source file's container ("flac", "m4a"…): decides direct play.
    public var container: String?
    /// The source file's audio codec, lower-case ("aac", "alac", "flac"…),
    /// so AAC and ALAC in an .m4a can be told apart after a restore.
    public var codec: String?
    /// Bits per second of the source file.
    public var bitrate: Int?
    public var sampleRate: Int?
    public var bitDepth: Int?
    /// Loudness correction in dB.
    public var normalizationGain: Double?

    public init(id: String, title: String, artist: String, artistId: String? = nil,
                album: String, albumId: String, artItemId: String? = nil,
                durationSeconds: Double, trackNumber: Int = 0, discNumber: Int? = nil,
                container: String? = nil, codec: String? = nil, bitrate: Int? = nil, sampleRate: Int? = nil,
                bitDepth: Int? = nil, normalizationGain: Double? = nil) {
        self.id = id
        self.title = title
        self.artist = artist
        self.artistId = artistId
        self.album = album
        self.albumId = albumId
        self.artItemId = artItemId ?? albumId
        self.durationSeconds = durationSeconds
        self.trackNumber = trackNumber
        self.discNumber = discNumber
        self.container = container
        self.codec = codec?.lowercased()
        self.bitrate = bitrate
        self.sampleRate = sampleRate
        self.bitDepth = bitDepth
        self.normalizationGain = normalizationGain
    }

    /// From a server track, with its album's names (as `PlaybackTrack`).
    public init(track: JellyfinTrack, album: JellyfinAlbum) {
        let pt = PlaybackTrack(track: track, album: album)
        self.init(id: pt.id, title: pt.title, artist: pt.artist, artistId: pt.albumArtistId,
                  album: pt.album, albumId: pt.albumId, artItemId: pt.artItemId,
                  durationSeconds: pt.duration, trackNumber: pt.trackNumber,
                  discNumber: track.discNumber, container: track.mediaSource?.container,
                  codec: track.mediaSource?.codecs,
                  bitrate: track.mediaSource?.bitrate, sampleRate: pt.sampleRate,
                  bitDepth: pt.bitDepth, normalizationGain: pt.normalizationGain)
    }

    /// From a track already in the player (no container or bitrate there;
    /// the codec is the source's, from `PlaybackTrack.codec`).
    public init(playbackTrack pt: PlaybackTrack) {
        self.init(id: pt.id, title: pt.title, artist: pt.artist, artistId: pt.albumArtistId,
                  album: pt.album, albumId: pt.albumId, artItemId: pt.artItemId,
                  durationSeconds: pt.duration, trackNumber: pt.trackNumber,
                  codec: pt.codec, sampleRate: pt.sampleRate, bitDepth: pt.bitDepth,
                  normalizationGain: pt.normalizationGain)
    }
}

// MARK: - QueueGroup

/// What an entry was queued from, for the album/playlist headers.
public struct QueueGroup: Codable, Equatable, Hashable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case album, playlist, tracks
    }
    /// The album's or playlist's server id; for `.tracks`, the (first) track's.
    public var id: String
    public var kind: Kind
    public var title: String
    public var artist: String?
    public var artItemId: String?

    public init(id: String, kind: Kind, title: String, artist: String? = nil, artItemId: String? = nil) {
        self.id = id
        self.kind = kind
        self.title = title
        self.artist = artist
        self.artItemId = artItemId
    }
}

// MARK: - QueueEntry

/// One place in the queue. The same track can be queued twice; each has
/// its own entry id.
public struct QueueEntry: Codable, Equatable, Hashable, Sendable, Identifiable {
    /// Stable id of this entry (a UUID string), unique in the queue.
    public var id: String
    /// Shared by the entries added by one operation (one replace, Play
    /// Next or Add to Queue). Consecutive entries with the same batch form
    /// a display group, so the same album queued twice shows twice.
    public var batchId: String
    public var track: QueueTrack
    public var group: QueueGroup

    public init(id: String = UUID().uuidString, batchId: String, track: QueueTrack, group: QueueGroup) {
        self.id = id
        self.batchId = batchId
        self.track = track
        self.group = group
    }
}

// MARK: - Display group

/// A run of consecutive entries (in play order) from the same batch.
public struct QueueDisplayGroup: Equatable, Sendable, Identifiable {
    /// The first entry's id.
    public var id: String { entries.first?.id ?? batchId }
    public var batchId: String
    public var group: QueueGroup
    public var entries: [QueueEntry]
    public var durationSeconds: Double { entries.reduce(0) { $0 + $1.track.durationSeconds } }
}

// MARK: - Edits and Maniac Mode

/// The kinds of change, for `PlayQueue.canEdit`.
public enum QueueEdit: String, CaseIterable, Sendable {
    case replace, playNext, addToQueue, move, remove, clearUpcoming, skipTo, shuffle, repeatMode
}

/// What `remove` did to the current entry.
public enum QueueCurrentChange: String, Sendable {
    /// The current entry was not removed.
    case unchanged
    /// The current entry was removed; current is now the next playable
    /// entry in play order (wrapping with Repeat All).
    case moved
    /// The current entry was removed and nothing playable follows it:
    /// current is now the first remaining entry in play order (nil if the
    /// queue is empty). The player should stop, as at the end of the queue.
    case ended
}

// MARK: - PlayQueue

public struct PlayQueue: Equatable, Sendable {

    /// Natural order.
    public private(set) var entries: [QueueEntry] = []
    public private(set) var currentEntryId: String?
    public private(set) var shuffle: Bool = false
    /// Entry ids in the order they play. Natural order while shuffle is off.
    public private(set) var playOrder: [String] = []
    public var repeatMode: RepeatMode = .off

    public init() {}

    // MARK: Lookups

    public var isEmpty: Bool { entries.isEmpty }

    public func entry(id: String) -> QueueEntry? { entries.first { $0.id == id } }

    public var currentEntry: QueueEntry? { currentEntryId.flatMap(entry(id:)) }

    /// Entries in play order.
    public var entriesInPlayOrder: [QueueEntry] {
        let byId = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        return playOrder.compactMap { byId[$0] }
    }

    /// The current entry's place in `playOrder`.
    public var currentPlayIndex: Int? { currentEntryId.flatMap { playOrder.firstIndex(of: $0) } }

    private var currentNaturalIndex: Int? { currentEntryId.flatMap { id in entries.firstIndex { $0.id == id } } }

    // MARK: Replace / add

    /// Replaces the whole queue with `tracks` from `group` (one new batch).
    /// Current is `tracks[startIndex]` (an index, not a track id, because a
    /// playlist can hold the same song twice); when `startIndex` is nil or
    /// out of range, the first track, or with shuffle on a random one.
    /// Shuffle on: current first in play order, the rest shuffled. Shuffle
    /// and repeat settings are kept. Empty `tracks` empties the queue.
    public mutating func replace<R: RandomNumberGenerator>(
        group: QueueGroup, tracks: [QueueTrack], startIndex: Int? = nil, using rng: inout R
    ) {
        entries = Self.makeEntries(group: group, tracks: tracks)
        guard !entries.isEmpty else {
            playOrder = []
            currentEntryId = nil
            return
        }
        let start = startIndex.flatMap { entries.indices.contains($0) ? $0 : nil }
        if shuffle {
            if let start {
                currentEntryId = entries[start].id
                var rest = entries.map(\.id)
                rest.remove(at: start)
                rest.shuffle(using: &rng)
                playOrder = [entries[start].id] + rest
            } else {
                playOrder = entries.map(\.id)
                playOrder.shuffle(using: &rng)
                currentEntryId = playOrder[0]
            }
        } else {
            playOrder = entries.map(\.id)
            currentEntryId = entries[start ?? 0].id
        }
    }

    public mutating func replace(group: QueueGroup, tracks: [QueueTrack], startIndex: Int? = nil) {
        var rng = SystemRandomNumberGenerator()
        replace(group: group, tracks: tracks, startIndex: startIndex, using: &rng)
    }

    /// Inserts `tracks` (one new batch) right after the current group: after
    /// the last entry of the run of entries, in natural order, that shares
    /// the current entry's batch — after the playing album, not mid-album.
    /// A second Play Next therefore goes before the first. While shuffled,
    /// the new entries also go immediately after the current entry in play
    /// order, in their given order (they play next). Into an empty queue it
    /// acts as `replace(startIndex: nil)`. Returns the new entries.
    @discardableResult
    public mutating func playNext<R: RandomNumberGenerator>(
        group: QueueGroup, tracks: [QueueTrack], using rng: inout R
    ) -> [QueueEntry] {
        guard let ci = currentNaturalIndex else {
            replace(group: group, tracks: tracks, startIndex: nil, using: &rng)
            return entries
        }
        let batch = entries[ci].batchId
        var end = ci
        while end + 1 < entries.count, entries[end + 1].batchId == batch { end += 1 }
        let new = Self.makeEntries(group: group, tracks: tracks)
        insert(new, atNaturalIndex: end + 1, shuffledRightAfterCurrent: true, using: &rng)
        return new
    }

    @discardableResult
    public mutating func playNext(group: QueueGroup, tracks: [QueueTrack]) -> [QueueEntry] {
        var rng = SystemRandomNumberGenerator()
        return playNext(group: group, tracks: tracks, using: &rng)
    }

    /// Appends `tracks` (one new batch) at the end of the natural order.
    /// Into an empty queue it acts as `replace(startIndex: nil)`. While
    /// shuffled, each new entry goes to a random place after the current
    /// one in play order (see `insert`). Returns the new entries.
    @discardableResult
    public mutating func addToQueue<R: RandomNumberGenerator>(
        group: QueueGroup, tracks: [QueueTrack], using rng: inout R
    ) -> [QueueEntry] {
        guard currentEntryId != nil else {
            replace(group: group, tracks: tracks, startIndex: nil, using: &rng)
            return entries
        }
        let new = Self.makeEntries(group: group, tracks: tracks)
        insert(new, atNaturalIndex: entries.count, shuffledRightAfterCurrent: false, using: &rng)
        return new
    }

    @discardableResult
    public mutating func addToQueue(group: QueueGroup, tracks: [QueueTrack]) -> [QueueEntry] {
        var rng = SystemRandomNumberGenerator()
        return addToQueue(group: group, tracks: tracks, using: &rng)
    }

    /// Puts `new` into the natural order at `index`. Shuffle off: the play
    /// order is the natural order. Shuffle on: with `shuffledRightAfterCurrent`
    /// (Play Next) the new entries go immediately after the current entry
    /// in play order, in their given order; otherwise (Add to Queue) each
    /// goes to a random place after the current entry (uniform over the
    /// gaps after it, end included), one at a time in natural order.
    private mutating func insert<R: RandomNumberGenerator>(
        _ new: [QueueEntry], atNaturalIndex index: Int, shuffledRightAfterCurrent: Bool,
        using rng: inout R
    ) {
        guard !new.isEmpty else { return }
        entries.insert(contentsOf: new, at: index)
        if shuffle, let ci = currentPlayIndex {
            if shuffledRightAfterCurrent {
                playOrder.insert(contentsOf: new.map(\.id), at: ci + 1)
                return
            }
            for e in new {
                let at = Int.random(in: (ci + 1)...playOrder.count, using: &rng)
                playOrder.insert(e.id, at: at)
            }
        } else {
            playOrder = entries.map(\.id)
        }
    }

    // MARK: Move / remove / clear

    /// Moves `entryIds` (one entry or a whole group's) so the first of
    /// them lands at `toIndex` in the resulting list, keeping their
    /// relative order. The list is the displayed one: the play order while
    /// shuffled (the natural order is then untouched), else the natural
    /// order. `toIndex` counts in the list without the moved entries and is
    /// clamped to it. Unknown ids are ignored; current stays current.
    /// Returns whether anything changed.
    @discardableResult
    public mutating func move(entryIds: [String], toIndex: Int) -> Bool {
        let ids = Set(entryIds)
        let order = shuffle ? playOrder : entries.map(\.id)
        let moving = order.filter { ids.contains($0) }
        guard !moving.isEmpty else { return false }
        var rest = order.filter { !ids.contains($0) }
        let at = min(max(0, toIndex), rest.count)
        rest.insert(contentsOf: moving, at: at)
        guard rest != order else { return false }
        if shuffle {
            playOrder = rest
        } else {
            let byId = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
            entries = rest.compactMap { byId[$0] }
            playOrder = rest
        }
        return true
    }

    /// Removes `entryIds` (unknown ids ignored). If the current entry is
    /// removed, current moves to the next entry after it in play order that
    /// stays and `isPlayable`, wrapping round with Repeat All (`.moved`);
    /// with none, to the first remaining entry in play order (`.ended`, or
    /// nil current when the queue is now empty).
    @discardableResult
    public mutating func remove(
        entryIds: [String], isPlayable: (QueueEntry) -> Bool = { _ in true }
    ) -> QueueCurrentChange {
        let ids = Set(entryIds)
        guard entries.contains(where: { ids.contains($0.id) }) else { return .unchanged }
        var change = QueueCurrentChange.unchanged
        if let current = currentEntryId, ids.contains(current), let ci = currentPlayIndex {
            var candidates = Array(playOrder[(ci + 1)...])
            if repeatMode == .all { candidates += playOrder[..<ci] }
            if let next = candidates.first(where: { id in
                !ids.contains(id) && entry(id: id).map(isPlayable) == true
            }) {
                currentEntryId = next
                change = .moved
            } else {
                currentEntryId = playOrder.first { !ids.contains($0) }
                change = .ended
            }
        }
        entries.removeAll { ids.contains($0.id) }
        playOrder.removeAll { ids.contains($0) }
        return change
    }

    /// Removes every entry except the current one (played and upcoming).
    public mutating func clearUpcoming() {
        guard let current = currentEntry else { return }
        entries = [current]
        playOrder = [current.id]
    }

    // MARK: Moving through the queue

    /// Makes `entryId` current (play order unchanged). False if unknown.
    @discardableResult
    public mutating func skipTo(entryId: String) -> Bool {
        guard entries.contains(where: { $0.id == entryId }) else { return false }
        currentEntryId = entryId
        return true
    }

    /// Moves to the next entry in play order and returns its id (the one to
    /// play now), or nil when the queue has ended (current unchanged).
    ///  - `trackEnded` true (the track played to its end) with Repeat One:
    ///    returns the current entry again.
    ///  - Otherwise the next entry after current that `isPlayable`; past the
    ///    end, Repeat All wraps to the start of the play order (the same
    ///    shuffled order; a one-entry queue repeats it). Repeat One with a
    ///    user skip behaves like Repeat Off.
    @discardableResult
    public mutating func next(
        trackEnded: Bool = false, isPlayable: (QueueEntry) -> Bool = { _ in true }
    ) -> String? {
        guard let ci = currentPlayIndex else { return nil }
        if trackEnded && repeatMode == .one { return currentEntryId }
        var candidates = Array(playOrder[(ci + 1)...])
        if repeatMode == .all { candidates += playOrder[...ci] }
        guard let id = candidates.first(where: { id in entry(id: id).map(isPlayable) == true }) else {
            return nil
        }
        currentEntryId = id
        return id
    }

    /// Moves to the entry before current in play order that `isPlayable`
    /// and returns its id; before the start, Repeat All wraps to the end.
    /// Nil (current unchanged) at the start otherwise. ("Restart the track
    /// if more than 3 s in" stays in the player.)
    @discardableResult
    public mutating func previous(isPlayable: (QueueEntry) -> Bool = { _ in true }) -> String? {
        guard let ci = currentPlayIndex else { return nil }
        var candidates = Array(playOrder[..<ci].reversed())
        if repeatMode == .all { candidates += playOrder[ci...].reversed() }
        guard let id = candidates.first(where: { id in entry(id: id).map(isPlayable) == true }) else {
            return nil
        }
        currentEntryId = id
        return id
    }

    // MARK: Shuffle

    /// On: current first in play order, every other entry (played or not)
    /// shuffled after it. Off: play order back to natural, current kept.
    /// Setting the value it already has does nothing (no reshuffle).
    public mutating func setShuffle<R: RandomNumberGenerator>(_ on: Bool, using rng: inout R) {
        guard on != shuffle else { return }
        shuffle = on
        if on, let current = currentEntryId {
            var rest = entries.map(\.id).filter { $0 != current }
            rest.shuffle(using: &rng)
            playOrder = [current] + rest
        } else {
            playOrder = entries.map(\.id)
        }
    }

    public mutating func setShuffle(_ on: Bool) {
        var rng = SystemRandomNumberGenerator()
        setShuffle(on, using: &rng)
    }

    // MARK: Derived

    /// What plays after the current entry, in play order; with Repeat All
    /// the entries before it follow (as the old "Up next").
    public var upcomingEntries: [QueueEntry] {
        guard let ci = currentPlayIndex else { return [] }
        var ids = Array(playOrder[(ci + 1)...])
        if repeatMode == .all { ids += playOrder[..<ci] }
        return ids.compactMap(entry(id:))
    }

    /// The queue for display: runs of consecutive entries in play order that
    /// share a batch. Unshuffled that is album/playlist groups in natural
    /// order; shuffled it is mostly one entry per group, with its group info.
    public var displayGroups: [QueueDisplayGroup] {
        var groups: [QueueDisplayGroup] = []
        for e in entriesInPlayOrder {
            if let last = groups.last, last.batchId == e.batchId {
                groups[groups.count - 1].entries.append(e)
            } else {
                groups.append(QueueDisplayGroup(batchId: e.batchId, group: e.group, entries: [e]))
            }
        }
        return groups
    }

    /// Seconds left in the current group: the rest of the current track
    /// (its duration minus `position`, never below 0) plus the entries after
    /// it in its display group (the run in play order). 0 when empty.
    public func currentGroupRemaining(position: Double) -> Double {
        guard let ci = currentPlayIndex, let current = currentEntry else { return 0 }
        let order = entriesInPlayOrder
        var rest = 0.0
        var i = ci + 1
        while i < order.count, order[i].batchId == current.batchId {
            rest += order[i].track.durationSeconds
            i += 1
        }
        return max(0, current.track.durationSeconds - position) + rest
    }

    /// Seconds left in the whole queue: the rest of the current track plus
    /// every entry after it in play order (no wrap for Repeat All).
    public func queueRemaining(position: Double) -> Double {
        guard let ci = currentPlayIndex, let current = currentEntry else { return 0 }
        let rest = entriesInPlayOrder[(ci + 1)...].reduce(0) { $0 + $1.track.durationSeconds }
        return max(0, current.track.durationSeconds - position) + rest
    }

    /// Maniac Mode: the queue still plays on and adding is allowed
    /// (replace, Play Next, Add to Queue); move, remove, clear, skip,
    /// shuffle and repeat are not. Without it everything is allowed.
    public static func canEdit(_ edit: QueueEdit, maniacMode: Bool) -> Bool {
        guard maniacMode else { return true }
        switch edit {
        case .replace, .playNext, .addToQueue: return true
        case .move, .remove, .clearUpcoming, .skipTo, .shuffle, .repeatMode: return false
        }
    }

    // MARK: Snapshot

    /// The queue as saved, with the playing position.
    /// `serverId`/`userId` say whose library the queue's ids belong to.
    public func snapshot(positionSeconds: Double, serverId: String? = nil,
                         userId: String? = nil) -> PlayQueueSnapshot {
        PlayQueueSnapshot(entries: entries, currentEntryId: currentEntryId,
                          positionSeconds: positionSeconds, shuffle: shuffle,
                          playOrder: playOrder, repeatMode: repeatMode,
                          serverId: serverId, userId: userId)
    }

    /// Rebuilds a queue from a snapshot, repairing it: later duplicate
    /// entry ids dropped; a play order that isn't exactly the entry ids
    /// becomes the natural order with shuffle off; an unknown current
    /// becomes the first in play order.
    public init(snapshot s: PlayQueueSnapshot) {
        var seen = Set<String>()
        entries = s.entries.filter { seen.insert($0.id).inserted }
        repeatMode = s.repeatMode
        let natural = entries.map(\.id)
        if s.playOrder.count == natural.count, Set(s.playOrder) == Set(natural) {
            shuffle = s.shuffle
            playOrder = s.shuffle ? s.playOrder : natural
        } else {
            shuffle = false
            playOrder = natural
        }
        if let c = s.currentEntryId, seen.contains(c) {
            currentEntryId = c
        } else {
            currentEntryId = playOrder.first
        }
    }

    // MARK: Helpers

    private static func makeEntries(group: QueueGroup, tracks: [QueueTrack]) -> [QueueEntry] {
        let batch = UUID().uuidString
        return tracks.map { QueueEntry(batchId: batch, track: $0, group: group) }
    }
}
