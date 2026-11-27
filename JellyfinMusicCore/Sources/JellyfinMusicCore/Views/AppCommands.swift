// MARK: - AppCommands — Mac menu bar: Playback menu, View tabs and zoom, Settings

import SwiftUI

/// The app's main sections (tabs on iPhone, sidebar on Mac).
public enum AppTab: String, CaseIterable, Hashable, Sendable {
    case home, library, search, downloads, settings

    public var title: String {
        switch self {
        case .home:      "Home"
        case .library:   "Library"
        case .search:    "Search"
        case .downloads: "Downloads"
        case .settings:  "Settings"
        }
    }

    /// The tab's SF Symbol, shared by the tab items and the compact bar.
    public var systemImage: String {
        switch self {
        case .home:      "house"
        case .library:   "square.grid.2x2"
        case .search:    "magnifyingglass"
        case .downloads: "arrow.down.circle"
        case .settings:  "gearshape"
        }
    }

    /// ⌘1 … ⌘4 in the View menu (Settings uses ⌘,).
    var shortcut: KeyEquivalent? {
        switch self {
        case .home:      "1"
        case .library:   "2"
        case .search:    "3"
        case .downloads: "4"
        case .settings:  nil
        }
    }
}

// The main window publishes its player and tab selection so the menu bar
// (which lives outside any view) can drive them.
public extension FocusedValues {
    @Entry var playerManager: PlayerManager?
    @Entry var selectedAppTab: Binding<AppTab>?
    @Entry var libraryTabRequest: Binding<LibraryTab?>?
}

/// Menu bar commands for the Mac app. Add with `.commands { AppCommands() }`.
public struct AppCommands: Commands {
    @FocusedValue(\.playerManager) private var player
    @FocusedValue(\.selectedAppTab) private var tab
    @FocusedValue(\.libraryTabRequest) private var libraryTab

    /// View ▸ Playlists: ⌘5, after the tabs' ⌘1 … ⌘4.
    static let playlistsShortcut: KeyEquivalent = "5"

    public init() {}

    public var body: some Commands {
        // Single-window app: no File ▸ New Window; ⌘N makes a playlist.
        CommandGroup(replacing: .newItem) {
            Button("New Playlist…") { PlaylistEvents.shared.isAskingForNewPlaylist = true }
                .keyboardShortcut("n", modifiers: .command)
                .disabled(tab == nil)
        }

        CommandGroup(replacing: .appSettings) {
            Button("Settings…") { tab?.wrappedValue = .settings }
                .keyboardShortcut(",", modifiers: .command)
                .disabled(tab == nil)
        }

        CommandGroup(before: .sidebar) {
            ForEach(AppTab.allCases.filter { $0 != .settings }, id: \.self) { item in
                let button = Button(item.title) { tab?.wrappedValue = item }
                    .disabled(tab == nil)
                if let key = item.shortcut {
                    button.keyboardShortcut(key, modifiers: .command)
                } else {
                    button
                }
            }
            // Straight to Library ▸ Playlists.
            Button(LibraryTab.playlists.rawValue) {
                libraryTab?.wrappedValue = .playlists
                tab?.wrappedValue = .library
            }
            .keyboardShortcut(Self.playlistsShortcut, modifiers: .command)
            .disabled(tab == nil || libraryTab == nil)
            Divider()
            // Cards per row in the album and artist grids and Home's rows,
            // as the title bar's + and − buttons; off when none is showing.
            let zoom = GridZoom.shared
            Button("Zoom In") { zoom.zoomIn() }
                .keyboardShortcut("+", modifiers: .command)
                .disabled(!zoom.canZoomIn)
            Button("Zoom Out") { zoom.zoomOut() }
                .keyboardShortcut("-", modifiers: .command)
                .disabled(!zoom.canZoomOut)
            Divider()
        }

        CommandMenu("Playback") {
            let hasTrack = player?.currentTrack != nil
            // ⌘P here; the Space bar also works, through `SpaceKeyMonitor`. A bare
            // Space menu shortcut would stop spaces being typed in the Search box.
            Button(player?.isPlaying == true ? "Pause" : "Play") { player?.togglePlayPause() }
                .keyboardShortcut("p", modifiers: .command)
                .disabled(!hasTrack)
            let maniac = player?.maniacMode == true
            if !maniac {
            Button("Next Track") { player?.skipToNext() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(!hasTrack)
            Button("Previous Track") { player?.skipToPrevious() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(!hasTrack)
            }

            if !maniac {
            Divider()

            Toggle("Shuffle", isOn: Binding(
                get: { player?.shuffle ?? false },
                set: { player?.setShuffle($0) }
            ))
            .keyboardShortcut("s", modifiers: [.command, .option])
            .disabled(player == nil)

            Picker("Repeat", selection: Binding(
                get: { player?.repeatMode ?? .off },
                set: { player?.setRepeatMode($0) }
            )) {
                Text("Off").tag(RepeatMode.off)
                Text("All").tag(RepeatMode.all)
                Text("One").tag(RepeatMode.one)
            }
            .disabled(player == nil)
            Button("Cycle Repeat") { player?.cycleRepeatMode() }
                .keyboardShortcut("r", modifiers: [.command, .option])
                .disabled(player == nil)
            }

            Divider()

            // A song played from a download has no server ID to add.
            Button("Add to Playlist…") {
                if let track = player?.currentTrack { PlaylistEvents.shared.add(trackIds: [track.id]) }
            }
            .disabled(player?.currentTrack == nil || player?.currentTrack?.localURL != nil)

            Button("Show Now Playing") { player?.isNowPlayingPresented = true }
                .keyboardShortcut("l", modifiers: .command)
                .disabled(!hasTrack)
        }
    }
}
