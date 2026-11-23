// MARK: - QueueAddOrder — Play Next / Add to Queue in tap order

import Foundation

/// Keeps queue additions in the order they were asked for. Each Play Next
/// or Add to Queue takes a turn when tapped (`reserve`); when its tracks
/// are ready it hands over the change (`finish`), which runs once every
/// earlier turn has run or been dropped. A failed fetch finishes with nil:
/// its turn is dropped and later ones carry on. (A streamed album's tracks
/// are fetched first, so without this two quick adds landed in the order
/// their fetches finished.) The Kotlin side has the same rule.
@MainActor
final class QueueAddOrder {
    typealias Change = () -> Void

    private var nextTurn = 0
    /// Turns taken and not yet run or dropped, oldest first.
    private var waiting: [Int] = []
    /// Finished turns waiting for an earlier one: the change, or nil (dropped).
    private var ready: [Int: Change?] = [:]

    /// Takes the next turn.
    func reserve() -> Int {
        defer { nextTurn += 1 }
        waiting.append(nextTurn)
        return nextTurn
    }

    /// Play (replace the queue): every outstanding turn is dropped, without
    /// a notice, and the replace takes the next turn, so adds tapped after
    /// it wait for it and land in the new queue.
    func replace() -> Int {
        cancelAll()
        return reserve()
    }

    /// Drops every outstanding turn; their later `finish` calls are ignored.
    func cancelAll() {
        waiting.removeAll()
        ready.removeAll()
    }

    /// `turn` is ready: `change` runs now if no earlier turn is pending,
    /// else after them. Nil drops the turn. Unknown, finished or cancelled
    /// turns are ignored. Returns whether the turn was still live (a
    /// cancelled add shows no notice).
    @discardableResult
    func finish(_ turn: Int, _ change: Change?) -> Bool {
        guard waiting.contains(turn), ready[turn] == nil else { return false }
        ready[turn] = .some(change)
        while let first = waiting.first, let entry = ready[first] {
            waiting.removeFirst()
            ready[first] = nil
            entry?()
        }
        return true
    }

    /// Whether `turn` is still waiting (not run, dropped or cancelled).
    func isPending(_ turn: Int) -> Bool { waiting.contains(turn) && ready[turn] == nil }

    /// Turns taken and not yet run or dropped.
    var pendingCount: Int { waiting.count }
}
