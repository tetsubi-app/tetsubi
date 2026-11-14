// MARK: - PlaylistEditing — rules and wording for making and changing playlists

import Foundation
import Observation

// MARK: - Names

public enum PlaylistName {
    /// A name as it's saved: spaces and line breaks at the ends removed.
    /// Nil when nothing is left (the server would take an empty name).
    public static func cleaned(_ raw: String) -> String? {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : name
    }
}

// MARK: - Adding songs

/// What to add when some of the songs may already be in the playlist.
public enum PlaylistAdd {

    /// The answer to "Already in <name>. Add again?".
    public enum Choice: Sendable {
        /// Add every song, including the ones already there.
        case add
        /// Leave out the songs already there.
        case skip
    }

    public struct Plan: Equatable, Sendable {
        /// Every song asked for, in order.
        public let all: [String]
        /// Those not in the playlist yet, in order.
        public let notThereYet: [String]

        /// Ask only when at least one song is already there.
        public var needsQuestion: Bool { notThereYet.count < all.count }

        /// The songs to send. Nothing is sent when this is empty (one song
        /// that's already there, skipped; or an album all of which is).
        public func trackIds(for choice: Choice) -> [String] {
            switch choice {
            case .add:  all
            case .skip: notThereYet
            }
        }
    }

    /// `adding`: the songs chosen; `existing`: the playlist's songs now.
    public static func plan(adding: [String], existing: [String]) -> Plan {
        let there = Set(existing)
        return Plan(all: adding, notThereYet: adding.filter { !there.contains($0) })
    }
}

// MARK: - Wording

public enum PlaylistWording {
    public static func duplicateQuestion(playlist name: String) -> String {
        "Already in \(name). Add again?"
    }

    /// "Added to <name>." for one song, "Added N songs to <name>." for more.
    public static func added(count: Int, to name: String) -> String {
        count == 1 ? "Added to \(name)." : "Added \(count) songs to \(name)."
    }

    public static func addFailed(to name: String, error: String) -> String {
        "Couldn't add to \(name). \(error)"
    }

    public static func createFailed(error: String) -> String {
        "Couldn't create the playlist. \(error)"
    }

    public static func saveFailed(error: String) -> String {
        "Couldn't save your changes. \(error)"
    }

    public static func deleteFailed(error: String) -> String {
        "Couldn't delete the playlist. \(error)"
    }

    public static func deleteTitle(playlist name: String) -> String {
        "Delete “\(name)”?"
    }

    public static let deleteMessage = "This removes the playlist from your server. The songs stay in your library."
}

// MARK: - Sending edits

/// Sends a playlist's edits to the server one at a time. While one is on
/// its way, only the newest order (and the newest name) waits: a newer
/// one replaces it, since each order is the whole list. A failure drops
/// what's waiting and is reported, so the page can reload.
@MainActor
final class PlaylistSaveQueue {

    enum Change: Equatable {
        /// The playlist's songs, all of them, in their new order.
        case order([String])
        case name(String)
    }

    /// Sends one change; calls back (on the main actor) with nil on success.
    typealias Send = @MainActor (Change, @escaping @MainActor (JellyfinError?) -> Void) -> Void

    private let send: Send
    /// A change reached the server.
    var onSaved: @MainActor (Change) -> Void = { _ in }
    /// A change failed; anything waiting was dropped.
    var onFailure: @MainActor (JellyfinError) -> Void = { _ in }

    private var isSending = false
    private var waitingName: String?
    private var waitingOrder: [String]?
    /// Ignores the answer of a call made before `cancelAll`.
    private var generation = 0

    init(send: @escaping Send) {
        self.send = send
    }

    /// Nothing on its way and nothing waiting.
    var isIdle: Bool { !isSending && waitingName == nil && waitingOrder == nil }

    func submit(_ change: Change) {
        switch change {
        case .order(let ids): waitingOrder = ids
        case .name(let name): waitingName = name
        }
        sendNext()
    }

    /// Forget what's waiting and ignore the call on its way (the playlist
    /// is being deleted).
    func cancelAll() {
        waitingName = nil
        waitingOrder = nil
        isSending = false
        generation += 1
    }

    private func sendNext() {
        guard !isSending else { return }
        let change: Change
        if let name = waitingName {
            waitingName = nil
            change = .name(name)
        } else if let ids = waitingOrder {
            waitingOrder = nil
            change = .order(ids)
        } else {
            return
        }
        isSending = true
        let sentIn = generation
        send(change) { [weak self] error in
            guard let self, self.generation == sentIn else { return }
            self.isSending = false
            if let error {
                self.waitingName = nil
                self.waitingOrder = nil
                self.onFailure(error)
            } else {
                self.onSaved(change)
                self.sendNext()
            }
        }
    }
}

/// The server calls the playlist page makes, so tests can stand in for
/// the server. Each answers on any thread.
struct PlaylistServer {
    var tracks: (String, @escaping (Result<[PlaylistEntry], JellyfinError>) -> Void) -> Void
    var canEdit: (String, @escaping (Bool) -> Void) -> Void
    var setItems: (String, [String], @escaping (Result<Void, JellyfinError>) -> Void) -> Void
    var rename: (String, String, @escaping (Result<Void, JellyfinError>) -> Void) -> Void
    var delete: (String, @escaping (Result<Void, JellyfinError>) -> Void) -> Void

    init(api: JellyfinAPI) {
        tracks = { api.playlistTracks($0, completion: $1) }
        canEdit = { api.canEditPlaylist($0, completion: $1) }
        setItems = { api.setPlaylistItems($0, trackIds: $1, completion: $2) }
        rename = { api.renamePlaylist($0, name: $1, completion: $2) }
        delete = { api.deletePlaylist($0, completion: $1) }
    }

    init(tracks: @escaping (String, @escaping (Result<[PlaylistEntry], JellyfinError>) -> Void) -> Void,
         canEdit: @escaping (String, @escaping (Bool) -> Void) -> Void,
         setItems: @escaping (String, [String], @escaping (Result<Void, JellyfinError>) -> Void) -> Void,
         rename: @escaping (String, String, @escaping (Result<Void, JellyfinError>) -> Void) -> Void,
         delete: @escaping (String, @escaping (Result<Void, JellyfinError>) -> Void) -> Void) {
        self.tracks = tracks
        self.canEdit = canEdit
        self.setItems = setItems
        self.rename = rename
        self.delete = delete
    }
}

// MARK: - App-wide playlist events

/// Songs waiting for the "Add to Playlist…" sheet.
public struct AddToPlaylistRequest: Identifiable, Sendable {
    public let id = UUID()
    public let trackIds: [String]

    public init(trackIds: [String]) {
        self.trackIds = trackIds
    }
}

/// What the screens share about playlists: the sheet that's wanted, the
/// short notice after adding, and a counter that moves whenever a
/// playlist is made, changed or deleted, so lists know to ask again.
@MainActor
@Observable
public final class PlaylistEvents {

    public static let shared = PlaylistEvents()

    public struct Notice: Equatable, Sendable {
        public let message: String
        public let isError: Bool
        let id = UUID()
    }

    /// Set to open the "Add to Playlist…" sheet with these songs.
    public var addRequest: AddToPlaylistRequest?
    /// Set to ask for a name and make an empty playlist (the Mac's
    /// File ▸ New Playlist…).
    public var isAskingForNewPlaylist = false
    /// Shown briefly over the current screen.
    public private(set) var notice: Notice?
    /// Moves when the server's playlists changed.
    public private(set) var version = 0

    init() {}

    /// Open the sheet for these songs (nothing happens with none).
    public func add(trackIds: [String]) {
        guard !trackIds.isEmpty else { return }
        addRequest = AddToPlaylistRequest(trackIds: trackIds)
    }

    /// A playlist was made, changed or deleted. The server's song counts
    /// lag a moment behind an edit, so lists are told again shortly after.
    public func playlistsChanged() {
        version += 1
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2))
            self?.version += 1
        }
    }

    /// Show a notice; it goes away by itself (errors stay a little longer).
    public func show(_ message: String, isError: Bool = false) {
        let shown = Notice(message: message, isError: isError)
        notice = shown
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(isError ? 6 : 3))
            if self?.notice == shown { self?.notice = nil }
        }
    }

    public func dismissNotice() {
        notice = nil
    }
}
