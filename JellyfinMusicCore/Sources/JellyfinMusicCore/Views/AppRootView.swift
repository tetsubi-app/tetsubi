// MARK: - AppRootView — platform-agnostic root with tab navigation

import SwiftUI
import Observation

/// A minimal root view used by both iOS and macOS targets.
/// Shows the `ServerConfigView` login sheet when no credentials are stored.
/// When a `JellyfinAPI` is available, shows the tab bar with live home data
/// and the persistent `NowPlayingBarView` (Phase 3 onwards).
public struct AppRootView: View {

    // MARK: State

    @State private var showLogin = false
    @State private var selectedTab: AppTab = .home
    /// A Library tab to show next (Home's Playlists ›).
    @State private var libraryTabRequest: LibraryTab?

    /// Shared with CarPlay (and the Mac window); see `AppSession`.
    private var session: AppSession { AppSession.shared }
    private var api: JellyfinAPI? { session.api }
    private var player: PlayerManager? { session.player }
    private var downloadManager: DownloadManager? { session.downloadManager }

    // MARK: Init

    public init() {}

    // MARK: Body

    public var body: some View {
        ZStack {
            AppBackgroundView()
            // Main tab bar — only shown when API is available
            if let activeAPI = api {
                mainTabs(activeAPI: activeAPI)
            } else {
                // No API yet — an empty view that triggers login.
                Color.clear
                    .onAppear {
                        // Restore the saved login without a network round trip,
                        // so it works offline and survives server-side quirks.
                        // A rejected token surfaces later as a 401.
                        if !session.restore() {
                            DispatchQueue.main.async { self.showLogin = true }
                        }
                    }
            }

            // Login sheet
            if showLogin {
                ServerConfigView(
                    onConnected: { api in
                        // Fresh player + downloads for the new login.
                        session.signIn(api)
                        self.showLogin = false
                    },
                    onCancel: {
                        self.showLogin = false
                    }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .appTheme()
        // Mac: covers, text and spacing grow with the window (`UIScale`).
        .measuresUIScale()
    }

    // MARK: - Tabs

    private func mainTabs(
        activeAPI: JellyfinAPI
    ) -> some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                HomeView(api: activeAPI, player: player, downloadManager: downloadManager,
                         onShowPlaylists: {
                             libraryTabRequest = .playlists
                             selectedTab = .library
                         })
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
            .tabItem { Label("Home", systemImage: "house") }
            .tag(AppTab.home)

            NavigationStack {
                LibraryView(
                    api: activeAPI,
                    player: player,
                    downloadManager: downloadManager,
                    tabRequest: $libraryTabRequest
                )
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
            .tabItem { Label("Library", systemImage: "square.grid.2x2") }
            .tag(AppTab.library)

            NavigationStack {
                SearchView(
                    api: activeAPI,
                    player: player,
                    downloadManager: downloadManager
                )
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
            .tabItem { Label("Search", systemImage: "magnifyingglass") }
            .tag(AppTab.search)

            NavigationStack {
                DownloadsView(downloadManager: downloadManager, player: player)
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
            .tabItem { Label("Downloads", systemImage: "arrow.down.circle") }
            .tag(AppTab.downloads)

            NavigationStack {
                SettingsView(
                    onSignOut: {
                        DispatchQueue.main.async {
                            session.signOut()
                            self.showLogin = true
                        }
                    },
                    downloadManager: downloadManager,
                    player: player
                )
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(AppTab.settings)
        }
        .nowPlayingAccessory(player: player)
        .nowPlayingSheet(player: player)
        // "Add to Playlist…", the Mac's "New Playlist…", and their notices
        // (Now Playing shows its own while it's in front).
        .playlistEventsHost(isActive: !(player?.isNowPlayingPresented ?? false)) {
            libraryTabRequest = .playlists
            selectedTab = .library
        }
        .sidebarTabsOnMac()
        // Lets the Mac menu bar (AppCommands) reach the player and tabs.
        .focusedSceneValue(\.playerManager, player)
        .focusedSceneValue(\.selectedAppTab, $selectedTab)
        .focusedSceneValue(\.libraryTabRequest, $libraryTabRequest)
        // Mac: Space plays or pauses (not while typing).
        .spaceTogglesPlayback(player)
        // Tapping the home-screen widget with nothing playing opens Home.
        .onReceive(NotificationCenter.default.publisher(for: AppSession.showHome)) { _ in
            selectedTab = .home
        }
    }

    private struct AlbumDestination: ViewModifier {
        let api: JellyfinAPI
        let player: PlayerManager?
        let downloadManager: DownloadManager?
        func body(content: Content) -> some View {
            content
                .navigationDestination(for: JellyfinAlbum.self) { album in
                    AlbumDetailView(api: api, album: album, player: player, downloadManager: downloadManager)
                        .appTheme()
                }
                .navigationDestination(for: JellyfinPlaylist.self) { playlist in
                    PlaylistDetailView(api: api, playlist: playlist, player: player,
                                       downloadManager: downloadManager)
                        .appTheme()
                }
        }
    }
}

// MARK: - Now-playing placement

private extension View {
    /// Mac: show the tabs as a sidebar (macOS 15+); macOS 14 keeps tabs.
    @ViewBuilder
    func sidebarTabsOnMac() -> some View {
        #if os(macOS)
        if #available(macOS 15.0, *) {
            self.tabViewStyle(.sidebarAdaptable)
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// The single full-screen Now Playing sheet, opened by the bar or by
    /// playing an album (`PlayerManager.isNowPlayingPresented`).
    func nowPlayingSheet(player: PlayerManager?) -> some View {
        sheet(isPresented: Binding(
            get: { player?.isNowPlayingPresented ?? false },
            set: { player?.isNowPlayingPresented = $0 }
        )) {
            if let player {
                NowPlayingView(manager: player) {
                    player.isNowPlayingPresented = false
                }
                .presentationCornerRadius(20)
                #if os(macOS)
                .nowPlayingSheetFrame()
                #endif
            }
        }
    }

    /// iOS 26+: the lozenge is the tab-bar accessory, so the tab bar never moves.
    @ViewBuilder
    func nowPlayingAccessory(player: PlayerManager?) -> some View {
        #if os(iOS)
        if #available(iOS 26.1, *) {
            self.tabViewBottomAccessory(isEnabled: player?.currentTrack != nil) {
                if let player { NowPlayingBarView(manager: player, style: .accessory) }
            }
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// Everywhere else (iOS < 26, macOS): a floating lozenge inset at the
    /// bottom of each tab's content, which sits above the tab bar.
    @ViewBuilder
    func nowPlayingInset(player: PlayerManager?) -> some View {
        #if os(iOS)
        if #available(iOS 26.1, *) {
            self
        } else {
            self.safeAreaInset(edge: .bottom, spacing: 0) {
                if let player { NowPlayingBarView(manager: player) }
            }
        }
        #else
        self.safeAreaInset(edge: .bottom, spacing: 0) {
            if let player { NowPlayingBarView(manager: player) }
        }
        #endif
    }
}
