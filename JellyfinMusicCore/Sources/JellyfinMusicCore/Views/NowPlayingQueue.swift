// MARK: - NowPlayingQueue — Now Playing's queue editor

import SwiftUI

/// The queue's heading: "Queue", the time left in the whole queue, and
/// "Clear queue" (asks first; the playing track carries on). A row of the
/// compact list, pinned above the list in the wide panel.
struct NowPlayingQueueHeader: View {
    let manager: PlayerManager
    @State private var isConfirmingClear = false

    var body: some View {
        let readOnly = QueueEditor.isReadOnly(maniacMode: manager.maniacMode)
        HStack(alignment: .center, spacing: Spacing.m) {
            Text(NowPlayingQueue.title)
                .font(TetsubiType.sectionTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(NowPlayingView.formatAlbumDuration(manager.queueRemaining))
                .font(TetsubiType.caption.monospacedDigit())
                .foregroundStyle(TetsubiColors.textSecondary)
                .accessibilityLabel("\(NowPlayingView.formatAlbumDuration(manager.queueRemaining)) left")
            Spacer(minLength: Spacing.s)
            if !readOnly, manager.playQueue.entries.count > 1 {
                ActionChip(NowPlayingQueue.clearButton) { isConfirmingClear = true }
                    .accessibilityIdentifier("NowPlayingView.clearQueue")
                    .confirmationDialog(NowPlayingQueue.clearTitle, isPresented: $isConfirmingClear,
                                        titleVisibility: .visible) {
                        Button("Clear", role: .destructive) { manager.clearUpcoming() }
                        Button("Cancel", role: .cancel) {}
                    } message: {
                        Text(NowPlayingQueue.clearMessage)
                    }
            }
        }
        .accessibilityIdentifier("NowPlayingView.upNext")
    }
}

/// Now Playing's queue, as rows of the `List` it sits in (the single
/// column's list, or the wide layout's panel).
///
///  - Header (`showsHeader`): see `NowPlayingQueueHeader`.
///  - The playing track, pinned at the top (not draggable), in the pink tint.
///  - Unshuffled: a header per album or playlist (36 pt cover, title,
///    artist, its time) with its tracks under it; shuffled: one flat list
///    in play order, each row showing its album.
///  - Tap a track to play it. Drag a track, or a header to move its whole
///    group (`QueueEditor`). Swipe to remove (iOS); a remove button on each
///    row and header on the Mac. VoiceOver: Move up, Move down, Remove.
///  - Maniac Mode: read-only, with a note.
struct NowPlayingQueue: View {
    let manager: PlayerManager
    /// Put the "Queue" heading first (the compact list); the wide panel pins its own.
    var showsHeader = true

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

            if showsHeader {
                NowPlayingQueueHeader(manager: manager)
                    .queueRow(top: Spacing.m)
            }

            if readOnly {
                note(Self.maniacNote).queueRow()
            }

            currentRow(current)
                .queueRow()
                .moveDisabled(true)

            if editor.isEmpty {
                Text(Self.emptyNote)
                    .font(TetsubiType.body)
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .padding(.vertical, 6)
                    .queueRow()
            } else {
                ForEach(Array(editor.rows.enumerated()), id: \.element.id) { index, row in
                    rowView(row, index: index, editor: editor, readOnly: readOnly)
                        .queueRow()
                        .moveDisabled(readOnly)
                }
                .onMove(perform: readOnly ? nil : { source, destination in
                    guard let move = editor.move(fromRows: source, toRow: destination) else { return }
                    manager.moveQueue(entryIds: move.entryIds, toIndex: move.toIndex)
                })
            }

            if let text = repeatNote(editor) {
                note(text)
                    .padding(.top, 6)
                    .queueRow()
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(TetsubiType.caption)
            .foregroundStyle(TetsubiColors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func repeatNote(_ editor: QueueEditor) -> String? {
        switch manager.repeatMode {
        case .one: "Repeating this track"
        case .all: manager.playQueue.entries.count > 1 ? "Then repeats from the start" : "Repeating this track"
        case .off: nil
        }
    }

    // MARK: - Rows

    /// The playing track, pinned at the top, on the pink band.
    private func currentRow(_ entry: QueueEntry) -> some View {
        let track = manager.track(forEntry: entry)
        return HStack(spacing: Spacing.m) {
            Image(systemName: manager.isPlaying ? "waveform" : "pause.fill")
                .font(TetsubiType.caption)
                .foregroundStyle(TetsubiColors.pink)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 0) {
                Text(track.title)
                    .font(TetsubiType.rowTitle)
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .lineLimit(1)
                Text(Self.nowPlayingLabel + (track.album.isEmpty ? "" : " · \(track.album)"))
                    .font(TetsubiType.caption)
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: Spacing.s)
        }
        .padding(.vertical, Spacing.s)
        .padding(.horizontal, Spacing.s)
        .background(TetsubiColors.pinkTint, in: RoundedRectangle(cornerRadius: Radii.thumb))
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
        return HStack(spacing: Spacing.m) {
            AlbumArtView(request: artRequest(for: group), size: 36, cornerRadius: Radii.thumb,
                         placeholderColor: TetsubiColors.art)
            VStack(alignment: .leading, spacing: 0) {
                Text(group.group.title.isEmpty ? "Album" : group.group.title)
                    .font(TetsubiType.cardTitle)
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .lineLimit(1)
                if let artist = group.group.artist, !artist.isEmpty {
                    Text(artist)
                        .font(TetsubiType.caption)
                        .foregroundStyle(TetsubiColors.textSecondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: Spacing.s)
            if seconds > 0 {
                Text(NowPlayingView.formatAlbumDuration(seconds))
                    .font(TetsubiType.caption.monospacedDigit())
                    .foregroundStyle(TetsubiColors.textSecondary)
            }
            macControls(readOnly: readOnly, label: "Remove \(group.group.title)") {
                manager.removeFromQueue(entryIds: group.entryIds)
            }
        }
        .padding(.horizontal, Spacing.s)
        .padding(.top, Spacing.m)
        .padding(.bottom, Spacing.xs)
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
        return HStack(spacing: Spacing.m) {
            VStack(alignment: .leading, spacing: 0) {
                Text(track.title)
                    .font(TetsubiType.rowTitle)
                    .foregroundStyle(TetsubiColors.textPrimary)
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(TetsubiType.caption)
                        .foregroundStyle(TetsubiColors.textSecondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: Spacing.s)
            if track.duration > 0 {
                Text(NowPlayingView.format(track.duration))
                    .font(TetsubiType.control.monospacedDigit())
                    .foregroundStyle(TetsubiColors.textSecondary)
            }
            macControls(readOnly: readOnly, label: "Remove \(track.title)") {
                manager.removeFromQueue(entryIds: [entry.id])
            }
        }
        .padding(.vertical, Spacing.s)
        .padding(.horizontal, Spacing.s)
        // Under an album header the rows start at its text (36 cover + 12 gap).
        .padding(.leading, editor.isShuffled ? 0 : 48)
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
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(TetsubiColors.textSecondary)
                    .frame(width: 24, height: 24)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .help("Remove from the queue")
            .accessibilityLabel(label)
            Image(systemName: "line.3.horizontal")
                .font(TetsubiType.body)
                .foregroundStyle(TetsubiColors.textTertiary)
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
    /// A queue row in Now Playing's list: clear, no separators. The 8 pt row
    /// inset plus the row's own 8 pt padding put its content 16 pt from the
    /// edge, with the pink band 8 pt wider.
    func queueRow(top: CGFloat = 0) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: Spacing.s, bottom: 0, trailing: Spacing.s))
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
