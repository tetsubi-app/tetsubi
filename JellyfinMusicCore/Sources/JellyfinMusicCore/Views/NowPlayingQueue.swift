// MARK: - NowPlayingQueue — Now Playing's queue editor

import SwiftUI

/// Now Playing's queue, as rows of the `List` it sits in (the single
/// column's list, or the wide layout's right column).
///
///  - Header: "Queue", the time left in the whole queue, "Clear queue"
///    (asks first; the playing track carries on).
///  - The playing track, pinned at the top (not draggable).
///  - Unshuffled: a header per album or playlist (small cover, title,
///    artist, its time) with its tracks under it; shuffled: one flat list
///    in play order, each row showing its album.
///  - Tap a track to play it. Drag a track, or a header to move its whole
///    group (`QueueEditor`). Swipe to remove (iOS); a remove button on each
///    row and header on the Mac. VoiceOver: Move up, Move down, Remove.
///  - Maniac Mode: read-only, with a note.
struct NowPlayingQueue: View {
    let manager: PlayerManager
    /// Now Playing's scale.
    let s: CGFloat
    /// Horizontal inset of the rows.
    var inset: CGFloat = 20

    @State private var isConfirmingClear = false

    // Wording (shared with Android/Linux).
    static let title = "Queue"
    static let clearButton = "Clear queue"
    static let clearTitle = "Clear the queue?"
    static let clearMessage = "The current track keeps playing."
    static let emptyNote = "Nothing queued. Use Play Next or Add to Queue on any album."
    static let maniacNote = "Maniac Mode: the queue plays in order."
    static let nowPlayingLabel = "Now playing"

    var body: some View {
        if let current = manager.playQueue.currentEntry {
            let editor = QueueEditor(queue: manager.playQueue)
            let readOnly = QueueEditor.isReadOnly(maniacMode: manager.maniacMode)

            header(readOnly: readOnly)
                .queueRow(s, inset: inset, top: 12)

            if readOnly {
                Text(Self.maniacNote)
                    .font(.plex(.caption, scale: s))
                    .foregroundStyle(.white.opacity(0.6))
                    .queueRow(s, inset: inset)
            }

            currentRow(current)
                .queueRow(s, inset: inset)
                .moveDisabled(true)

            if editor.isEmpty {
                Text(Self.emptyNote)
                    .font(.plex(.subheadline, scale: s))
                    .foregroundStyle(.white.opacity(0.5))
                    .padding(.vertical, 6 * s)
                    .queueRow(s, inset: inset)
            } else {
                ForEach(Array(editor.rows.enumerated()), id: \.element.id) { index, row in
                    rowView(row, index: index, editor: editor, readOnly: readOnly)
                        .queueRow(s, inset: inset)
                        .moveDisabled(readOnly)
                }
                .onMove(perform: readOnly ? nil : { source, destination in
                    guard let move = editor.move(fromRows: source, toRow: destination) else { return }
                    manager.moveQueue(entryIds: move.entryIds, toIndex: move.toIndex)
                })
            }

            if let note = repeatNote(editor) {
                Text(note)
                    .font(.plex(.caption, scale: s))
                    .foregroundStyle(.white.opacity(0.5))
                    .padding(.top, 6 * s)
                    .queueRow(s, inset: inset)
            }
        }
    }

    // MARK: - Header

    private func header(readOnly: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10 * s) {
            Text(Self.title)
                .font(.plex(.headline, scale: s))
                .accessibilityAddTraits(.isHeader)
            Text(NowPlayingView.formatAlbumDuration(manager.queueRemaining))
                .font(.plex(.caption, scale: s).monospacedDigit())
                .foregroundStyle(.white.opacity(0.5))
                .accessibilityLabel("\(NowPlayingView.formatAlbumDuration(manager.queueRemaining)) left")
            Spacer(minLength: 8 * s)
            if !readOnly, manager.playQueue.entries.count > 1 {
                Button(Self.clearButton) { isConfirmingClear = true }
                    .font(.plex(.caption, scale: s))
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.appBlue)
                    .accessibilityIdentifier("NowPlayingView.clearQueue")
                    .confirmationDialog(Self.clearTitle, isPresented: $isConfirmingClear,
                                        titleVisibility: .visible) {
                        Button("Clear", role: .destructive) { manager.clearUpcoming() }
                        Button("Cancel", role: .cancel) {}
                    } message: {
                        Text(Self.clearMessage)
                    }
            }
        }
        .padding(.bottom, 4 * s)
        .accessibilityIdentifier("NowPlayingView.upNext")
    }

    private func repeatNote(_ editor: QueueEditor) -> String? {
        switch manager.repeatMode {
        case .one: "Repeating this track"
        case .all: manager.playQueue.entries.count > 1 ? "Then repeats from the start" : "Repeating this track"
        case .off: nil
        }
    }

    // MARK: - Rows

    /// The playing track, pinned at the top.
    private func currentRow(_ entry: QueueEntry) -> some View {
        let track = manager.track(forEntry: entry)
        return HStack(spacing: 10 * s) {
            Image(systemName: manager.isPlaying ? "waveform" : "pause.fill")
                .font(.plex(.caption, scale: s))
                .foregroundStyle(Color.appPink)
                .frame(width: 18 * s)
            VStack(alignment: .leading, spacing: 2 * s) {
                Text(track.title)
                    .font(.plex(.subheadline, scale: s).weight(.semibold))
                    .lineLimit(1)
                Text(Self.nowPlayingLabel + (track.album.isEmpty ? "" : " · \(track.album)"))
                    .font(.plex(.caption, scale: s))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
            }
            Spacer(minLength: 8 * s)
        }
        .padding(.vertical, 8 * s)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func rowView(_ row: QueueEditor.Row, index: Int, editor: QueueEditor, readOnly: Bool) -> some View {
        switch row {
        case .header(let gi, _):
            groupHeader(editor.groups[gi], index: index, editor: editor, readOnly: readOnly)
        case .entry(let entry, _):
            entryRow(entry, index: index, editor: editor, readOnly: readOnly)
        }
    }

    private func groupHeader(_ group: QueueEditor.Group, index: Int, editor: QueueEditor,
                             readOnly: Bool) -> some View {
        let seconds = group.entries.reduce(0) { $0 + manager.track(forEntry: $1).duration }
        return HStack(spacing: 10 * s) {
            AlbumArtView(request: artRequest(for: group), size: 36 * s, cornerRadius: 4 * s)
            VStack(alignment: .leading, spacing: 2 * s) {
                Text(group.group.title.isEmpty ? "Album" : group.group.title)
                    .font(.plex(.subheadline, scale: s).bold())
                    .lineLimit(1)
                if let artist = group.group.artist, !artist.isEmpty {
                    Text(artist)
                        .font(.plex(.caption, scale: s))
                        .foregroundStyle(Color.albumArtistText)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8 * s)
            if seconds > 0 {
                Text(NowPlayingView.formatAlbumDuration(seconds))
                    .font(.plex(.caption, scale: s).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.5))
            }
            macControls(readOnly: readOnly, label: "Remove \(group.group.title)") {
                manager.removeFromQueue(entryIds: group.entryIds)
            }
        }
        .padding(.top, 10 * s)
        .padding(.bottom, 4 * s)
        .contentShape(Rectangle())
        .queueEditing(readOnly: readOnly, editor: editor, index: index, manager: manager,
                      remove: group.entryIds, onTap: nil)
        .accessibilityAddTraits(.isHeader)
    }

    private func entryRow(_ entry: QueueEntry, index: Int, editor: QueueEditor, readOnly: Bool) -> some View {
        let track = manager.track(forEntry: entry)
        let subtitle = editor.isShuffled
            ? [track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " · ")
            : (track.artist != (entry.group.artist ?? "") ? track.artist : "")
        return HStack(spacing: 12 * s) {
            VStack(alignment: .leading, spacing: 2 * s) {
                Text(track.title)
                    .font(.plex(.subheadline, scale: s))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.plex(.caption, scale: s))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8 * s)
            if track.duration > 0 {
                Text(NowPlayingView.format(track.duration))
                    .font(.plex(.caption, scale: s).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.5))
            }
            macControls(readOnly: readOnly, label: "Remove \(track.title)") {
                manager.removeFromQueue(entryIds: [entry.id])
            }
        }
        .padding(.vertical, 8 * s)
        .padding(.leading, editor.isShuffled ? 0 : 46 * s)
        .contentShape(Rectangle())
        .queueEditing(readOnly: readOnly, editor: editor, index: index, manager: manager,
                      remove: [entry.id], onTap: { manager.skipTo(entryId: entry.id) })
    }

    /// Mac: a remove button and a drag handle (the row drags anywhere).
    @ViewBuilder
    private func macControls(readOnly: Bool, label: String, remove: @escaping () -> Void) -> some View {
        #if os(macOS)
        if !readOnly {
            Button(action: remove) {
                Image(systemName: "xmark.circle")
                    .font(.plex(.subheadline, scale: s))
                    .foregroundStyle(.white.opacity(0.6))
            }
            .buttonStyle(.plain)
            .help("Remove from the queue")
            .accessibilityLabel(label)
            Image(systemName: "line.3.horizontal")
                .font(.plex(.subheadline, scale: s))
                .foregroundStyle(.white.opacity(0.4))
                .help("Drag to move")
                .accessibilityHidden(true)
        }
        #endif
    }

    /// A group's picture: a playlist's own, else its first track's.
    private func artRequest(for group: QueueEditor.Group) -> URLRequest? {
        if group.group.kind == .playlist, let id = group.group.artItemId {
            return manager.imageRequest(forAlbumId: id, size: 100)
        }
        guard let first = group.entries.first else { return nil }
        return manager.artRequest(for: manager.track(forEntry: first), size: 100)
    }
}

// MARK: - Row helpers

private extension View {
    /// A queue row in Now Playing's list: clear, no separators, Now
    /// Playing's inset.
    func queueRow(_ s: CGFloat, inset: CGFloat, top: CGFloat = 0) -> some View {
        listRowInsets(EdgeInsets(top: top * s, leading: inset * s, bottom: 0, trailing: inset * s))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    /// Tap to play, swipe to remove, and VoiceOver's Move up / Move down /
    /// Remove; none of it when read-only (Maniac Mode).
    @ViewBuilder
    func queueEditing(readOnly: Bool, editor: QueueEditor, index: Int, manager: PlayerManager,
                      remove ids: [String], onTap: (() -> Void)?) -> some View {
        if readOnly {
            accessibilityElement(children: .combine)
        } else {
            self
                .onTapGesture { onTap?() }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        manager.removeFromQueue(entryIds: ids)
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(onTap == nil ? [] : .isButton)
                .accessibilityHint(onTap == nil ? "" : "Plays this track")
                .accessibilityAction { onTap?() }
                .accessibilityAction(named: "Move up") {
                    if let move = editor.moveUp(row: index) {
                        manager.moveQueue(entryIds: move.entryIds, toIndex: move.toIndex)
                    }
                }
                .accessibilityAction(named: "Move down") {
                    if let move = editor.moveDown(row: index) {
                        manager.moveQueue(entryIds: move.entryIds, toIndex: move.toIndex)
                    }
                }
                .accessibilityAction(named: "Remove") {
                    manager.removeFromQueue(entryIds: ids)
                }
        }
    }
}
