// MARK: - PlayQueueController — the listening queue driving an audio engine
//
// Owns the `PlayQueue` and tells an engine what to hold, so the rules
// (play order, what follows the current entry, Maniac Mode, the end of the
// queue, restore) are testable without AVFoundation. `PlayerManager` is
// the engine in the app (an AVQueuePlayer); tests use a fake. The player
// does the rest from the returned `QueueTransition`: server reports, Now
// Playing, its published state. The Kotlin `PlayerController` follows the
// same rules.

import Foundation
import Observation

// MARK: - Engine

/// What the controller needs from the thing that makes the sound. Entries
/// are given by entry id, in play order; the engine keeps its own mapping
/// from its items to entry ids.
@MainActor
public protocol QueueEngine: AnyObject {
    /// Drops everything and holds `entryIds` (the first is the one to play
    /// now, the rest follow without gaps), ready at `position` seconds into
    /// the first; playing when `play`, else paused. Empty: holds nothing.
    func load(entryIds: [String], position: Double, play: Bool)
    /// Replaces what follows the playing entry with `entryIds`, never
    /// interrupting the playing entry.
    func replaceAfterCurrent(_ entryIds: [String])
}

// MARK: - Transition

/// What a queue operation did, for the player to follow up.
public enum QueueTransition: Equatable, Sendable {
    /// Nothing changed (refused, unknown ids, nothing to add).
    case none
    /// Only what follows the current entry changed; the engine was given
    /// the new upcoming list and the playing track carries on.
    case upcomingChanged
    /// A new current entry, loaded from its start; playing when `play`.
    case started(play: Bool)
    /// The engine moved on by itself (gapless) to the new current entry.
    case advanced
    /// The queue ended: the current entry is the first in play order,
    /// loaded paused at 0.
    case ended
    /// The queue is empty.
    case emptied
}

// MARK: - Controller

@MainActor
@Observable
public final class PlayQueueController {

    public private(set) var queue = PlayQueue()
    /// Maniac Mode: no move, remove, clear, skip, shuffle or repeat
    /// (`PlayQueue.canEdit`); adding still works.
    public var maniacMode = false

    @ObservationIgnored public weak var engine: QueueEngine?
    /// Whether an entry can play now (a file here, or a connection).
    @ObservationIgnored public var isPlayable: (QueueEntry) -> Bool = { _ in true }
    /// Saves the queue after each change; nil saves nothing (tests, previews).
    @ObservationIgnored public var saver: PlayQueueSaver?
    /// The playing position, for the saved queue.
    @ObservationIgnored public var position: () -> Double = { 0 }
    @ObservationIgnored public var serverId: String?
    @ObservationIgnored public var userId: String?
    @ObservationIgnored var rng: any RandomNumberGenerator = SystemRandomNumberGenerator()

    public init() {}

    // MARK: Lookups

    /// Entry ids that follow the current one in play order, as the engine
    /// holds them (no wrap: Repeat All goes round when the end is reached).
    public var engineUpcoming: [String] {
        guard let ci = queue.currentPlayIndex else { return [] }
        return Array(queue.playOrder[(ci + 1)...])
    }

    private var engineFromCurrent: [String] {
        guard let current = queue.currentEntryId else { return [] }
        return [current] + engineUpcoming
    }

    // MARK: Adding

    /// Replaces the queue with `tracks` and loads it from `startIndex`
    /// (see `PlayQueue.replace`). `register` sees the new entries before
    /// the engine is asked for them.
    @discardableResult
    public func replace(group: QueueGroup, tracks: [QueueTrack], startIndex: Int? = nil, play: Bool = true,
                        register: ([QueueEntry]) -> Void = { _ in }) -> QueueTransition {
        queue.replace(group: group, tracks: tracks, startIndex: startIndex, using: &rng)
        register(queue.entries)
        scheduleSave(at: 0)
        guard !queue.isEmpty else {
            saveEmptied()
            engine?.load(entryIds: [], position: 0, play: false)
            return .emptied
        }
        engine?.load(entryIds: engineFromCurrent, position: 0, play: play)
        return .started(play: play)
    }

    /// Queues `tracks` after the current album or playlist. Into an empty
    /// queue it starts playing them.
    @discardableResult
    public func playNext(group: QueueGroup, tracks: [QueueTrack],
                         register: ([QueueEntry]) -> Void = { _ in }) -> QueueTransition {
        add(tracks, { queue.playNext(group: group, tracks: tracks, using: &rng) }, register: register)
    }

    /// Queues `tracks` at the end. Into an empty queue it starts playing them.
    @discardableResult
    public func addToQueue(group: QueueGroup, tracks: [QueueTrack],
                           register: ([QueueEntry]) -> Void = { _ in }) -> QueueTransition {
        add(tracks, { queue.addToQueue(group: group, tracks: tracks, using: &rng) }, register: register)
    }

    private func add(_ tracks: [QueueTrack], _ op: () -> [QueueEntry],
                     register: ([QueueEntry]) -> Void) -> QueueTransition {
        guard !tracks.isEmpty else { return .none }
        let wasEmpty = queue.currentEntryId == nil
        let added = op()
        register(added)
        scheduleSave(at: wasEmpty ? 0 : nil)
        if wasEmpty {
            engine?.load(entryIds: engineFromCurrent, position: 0, play: true)
            return .started(play: true)
        }
        engine?.replaceAfterCurrent(engineUpcoming)
        return .upcomingChanged
    }

    // MARK: Editing

    @discardableResult
    public func move(entryIds: [String], toIndex: Int) -> QueueTransition {
        guard PlayQueue.canEdit(.move, maniacMode: maniacMode),
              queue.move(entryIds: entryIds, toIndex: toIndex) else { return .none }
        return upcomingChanged()
    }

    /// Removes entries. The current one going: the next playable entry
    /// starts from 0 (playing if `playing`), or with none left the queue
    /// ends (paused at the first remaining entry).
    @discardableResult
    public func remove(entryIds: [String], playing: Bool) -> QueueTransition {
        guard PlayQueue.canEdit(.remove, maniacMode: maniacMode) else { return .none }
        let ids = Set(entryIds)
        guard queue.entries.contains(where: { ids.contains($0.id) }) else { return .none }
        switch queue.remove(entryIds: entryIds, isPlayable: isPlayable) {
        case .unchanged:
            return upcomingChanged()
        case .moved:
            scheduleSave(at: 0)
            engine?.load(entryIds: engineFromCurrent, position: 0, play: playing)
            return .started(play: playing)
        case .ended:
            scheduleSave(at: 0)
            guard !queue.isEmpty else {
                saveEmptied()
                engine?.load(entryIds: [], position: 0, play: false)
                return .emptied
            }
            engine?.load(entryIds: engineFromCurrent, position: 0, play: false)
            return .ended
        }
    }

    @discardableResult
    public func clearUpcoming() -> QueueTransition {
        guard PlayQueue.canEdit(.clearUpcoming, maniacMode: maniacMode), queue.entries.count > 1 else {
            return .none
        }
        queue.clearUpcoming()
        return upcomingChanged()
    }

    /// Plays `entryId` now.
    @discardableResult
    public func skipTo(entryId: String) -> QueueTransition {
        guard PlayQueue.canEdit(.skipTo, maniacMode: maniacMode),
              queue.skipTo(entryId: entryId) else { return .none }
        return startCurrent()
    }

    /// Shuffle across the whole queue; off puts it back in queue order,
    /// the current track carrying on. In Maniac Mode only off is allowed.
    @discardableResult
    public func setShuffle(_ on: Bool) -> QueueTransition {
        guard on != queue.shuffle else { return .none }
        guard !on || PlayQueue.canEdit(.shuffle, maniacMode: maniacMode) else { return .none }
        queue.setShuffle(on, using: &rng)
        return upcomingChanged()
    }

    /// In Maniac Mode only `.off` is allowed.
    @discardableResult
    public func setRepeatMode(_ mode: RepeatMode) -> QueueTransition {
        guard mode != queue.repeatMode else { return .none }
        guard mode == .off || PlayQueue.canEdit(.repeatMode, maniacMode: maniacMode) else { return .none }
        queue.repeatMode = mode
        return upcomingChanged()
    }

    /// Maniac Mode on: shuffle and repeat go off (the rest of the queue
    /// back in order, the playing track carrying on).
    public func setManiacMode(_ on: Bool) {
        maniacMode = on
        guard on else { return }
        queue.repeatMode = .off
        if queue.shuffle {
            queue.setShuffle(false, using: &rng)
            _ = upcomingChanged()
        } else {
            scheduleSave()
        }
    }

    // MARK: Moving through the queue

    /// Next (a skip): the next playable entry; Repeat All wraps.
    @discardableResult
    public func next() -> QueueTransition {
        guard PlayQueue.canEdit(.skipTo, maniacMode: maniacMode),
              queue.next(trackEnded: false, isPlayable: isPlayable) != nil else { return .none }
        return startCurrent()
    }

    /// Previous (the player restarts the track itself when it's > 3 s in).
    @discardableResult
    public func previous() -> QueueTransition {
        guard PlayQueue.canEdit(.skipTo, maniacMode: maniacMode),
              queue.previous(isPlayable: isPlayable) != nil else { return .none }
        return startCurrent()
    }

    /// The playing entry played to its end; `engineNext` is the entry the
    /// engine went on to by itself (nil: none). Repeat One plays it again;
    /// the next entry the engine already holds just becomes current
    /// (gapless, also across albums); anything else (Repeat All going
    /// round, skipping what can't play) is loaded; nothing left ends the
    /// queue.
    @discardableResult
    public func trackEnded(engineNext: String?) -> QueueTransition {
        guard let current = queue.currentEntryId else { return .none }
        guard let next = queue.next(trackEnded: true, isPlayable: isPlayable) else {
            return finish()
        }
        if next != current, next == engineNext {
            scheduleSave(at: 0)
            return .advanced
        }
        return startCurrent()
    }

    /// The end of the queue: back to the first entry in play order, paused
    /// at 0 (as at the end of an album).
    @discardableResult
    public func finish() -> QueueTransition {
        guard let first = queue.playOrder.first else { return .emptied }
        queue.skipTo(entryId: first)
        scheduleSave(at: 0)
        engine?.load(entryIds: engineFromCurrent, position: 0, play: false)
        return .ended
    }

    /// Loads the current entry again from its start (Play after a failed
    /// track).
    @discardableResult
    public func reloadCurrent(play: Bool) -> QueueTransition {
        guard !queue.isEmpty else { return .none }
        engine?.load(entryIds: engineFromCurrent, position: 0, play: play)
        return .started(play: play)
    }

    // MARK: Saved queue

    /// Brings back a saved queue, paused at its position. A current entry
    /// that can't play gives way to the next one that can (from 0); with
    /// none, nothing is restored. In Maniac Mode it comes back unshuffled,
    /// without repeat. Returns the position loaded, or nil. `register`
    /// sees the entries before `isPlayable` is asked about any of them
    /// (the player finds their files there), also when nothing is restored.
    @discardableResult
    public func restore(_ snapshot: PlayQueueSnapshot,
                        register: ([QueueEntry]) -> Void = { _ in }) -> Double? {
        var restored = PlayQueue(snapshot: snapshot)
        guard !restored.isEmpty else { return nil }
        if maniacMode {
            restored.setShuffle(false, using: &rng)
            restored.repeatMode = .off
        }
        register(restored.entries)
        var position = snapshot.positionSeconds.isFinite ? max(0, snapshot.positionSeconds) : 0
        if let current = restored.currentEntry, !isPlayable(current) {
            position = 0
            if restored.next(trackEnded: false, isPlayable: isPlayable) == nil {
                guard let first = restored.entriesInPlayOrder.first(where: isPlayable) else { return nil }
                restored.skipTo(entryId: first.id)
            }
        }
        queue = restored
        engine?.load(entryIds: engineFromCurrent, position: position, play: false)
        return position
    }

    /// Empties the queue (the engine too). `forget` also removes the saved
    /// queue (signing out); otherwise the latest state is saved first.
    public func clear(forget: Bool) {
        if forget {
            queue = PlayQueue()
            saver?.schedule(queue.snapshot(positionSeconds: 0, serverId: serverId, userId: userId))
        } else {
            scheduleSave()
            queue = PlayQueue()
        }
        saver?.flush()
        engine?.load(entryIds: [], position: 0, play: false)
    }

    /// Saves the queue with the playing position (debounced). `at` gives
    /// the position instead: 0 when a new entry has just become current
    /// (the player's position is still the old track's).
    public func scheduleSave(at position: Double? = nil) {
        guard let saver, !queue.isEmpty else { return }
        saver.schedule(queue.snapshot(positionSeconds: position ?? self.position(),
                                      serverId: serverId, userId: userId))
    }

    /// The queue was emptied (its last entries removed, or replaced by
    /// nothing): the saved one goes too, or it would come back at the next
    /// launch. `scheduleSave` leaves an empty queue alone, as a queue that
    /// was never loaded must not remove the saved one.
    private func saveEmptied() {
        saver?.schedule(queue.snapshot(positionSeconds: 0, serverId: serverId, userId: userId))
    }

    /// Writes a pending save now (going to the background, quitting).
    public func flushSave() {
        scheduleSave()
        saver?.flush()
    }

    // MARK: Helpers

    private func upcomingChanged() -> QueueTransition {
        scheduleSave()
        engine?.replaceAfterCurrent(engineUpcoming)
        return .upcomingChanged
    }

    private func startCurrent() -> QueueTransition {
        scheduleSave(at: 0)
        engine?.load(entryIds: engineFromCurrent, position: 0, play: true)
        return .started(play: true)
    }
}
