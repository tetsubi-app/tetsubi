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
    /// Each tab's pushed pages, so Now Playing's artist line can push an
    /// artist onto the tab in front.
    @State private var paths: [AppTab: NavigationPath] = [:]
    /// Now Playing's artist line: shown once the sheet has closed.
    @State private var artistAfterNowPlaying: JellyfinArtist?
    /// The window's width class, live while it is resized (an iPhone is always compact).
    @State private var widthClass: WindowWidthClass = AppRootView.isPhone ? .compact : .expanded
    @State private var windowWidth: CGFloat = 1024
    /// The first few playlists, for the sidebar's Playlists section.
    @State private var sidebarPlaylists: PlaylistsViewModel?

    /// Shared with CarPlay (and the Mac window); see `AppSession`.
    private var session: AppSession { AppSession.shared }
    private var api: JellyfinAPI? { session.api }
    private var player: PlayerManager? { session.player }
    private var downloadManager: DownloadManager? { session.downloadManager }

    // MARK: Init

    public init() {}

    /// How many playlists the sidebar lists before the rest (Library ▸ Playlists).
    static let sidebarPlaylistCount = 6

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
        .followsWindowWidth($widthClass, width: $windowWidth)
    }

    // MARK: - Tabs

    private func mainTabs(
        activeAPI: JellyfinAPI
    ) -> some View {
        Group {
            if isMac && widthClass.usesTabBar {
                compactTabs(activeAPI: activeAPI)
            } else if usesSidebarShell, #available(iOS 18.0, macOS 15.0, *) {
                sidebarTabs(activeAPI: activeAPI)
            } else {
                phoneTabs(activeAPI: activeAPI)
            }
        }
        .tint(TetsubiColors.blue)
        .task(id: ObjectIdentifier(activeAPI)) { reloadSidebarPlaylists(activeAPI) }
        .onChange(of: selectedTab) { reloadSidebarPlaylists(activeAPI) }
        .onChange(of: NetworkMonitor.shared.isConnected) { reloadSidebarPlaylists(activeAPI) }
        .nowPlayingAccessory(player: player)
        .nowPlayingSheet(player: player, onShowArtist: { artist in
            // Close Now Playing, then push the artist on the tab in front.
            artistAfterNowPlaying = artist
            player?.isNowPlayingPresented = false
        }, onDismiss: {
            guard let artist = artistAfterNowPlaying else { return }
            artistAfterNowPlaying = nil
            paths[selectedTab, default: NavigationPath()].append(artist)
        })
        // "Add to Playlist…", the Mac's "New Playlist…", and their notices
        // (Now Playing shows its own while it's in front).
        .playlistEventsHost(isActive: !(player?.isNowPlayingPresented ?? false)) {
            libraryTabRequest = .playlists
            selectedTab = .library
        }
        // Play on an album or playlist with more queued: Replace / Play Next / Add to Queue.
        .queuePromptHost()
        // Mac, narrow window: the phone-style bar below the mini player; the
        // native top tab bar (and its » overflow) is hidden.
        .compactTabBarOnMac(isOn: widthClass.usesTabBar, selection: $selectedTab) { tab in
            paths[tab] = NavigationPath()
        }
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

    private var isMac: Bool {
        #if os(macOS)
        true
        #else
        false
        #endif
    }

    /// Mac, narrow window: no TabView (its native bar can't be hidden on the
    /// Mac and collapses into an overflow menu), so the tab in front is shown
    /// with `CompactTabBar` below it. Only that tab is built: a tab kept alive
    /// out of sight would leak its toolbar (Search's field) into the others.
    /// The selection and every tab's pushed pages live here, so they survive.
    private func compactTabs(activeAPI: JellyfinAPI) -> some View {
        tabContent(selectedTab, activeAPI: activeAPI)
    }

    /// iPhone (and macOS 14 / iOS 17): the native bottom `TabView`.
    private func phoneTabs(activeAPI: JellyfinAPI) -> some View {
        TabView(selection: $selectedTab) {
            tabContent(.home, activeAPI: activeAPI)
                .tabItem { Label("Home", systemImage: AppTab.home.systemImage) }.tag(AppTab.home)
            tabContent(.library, activeAPI: activeAPI)
                .tabItem { Label("Library", systemImage: AppTab.library.systemImage) }.tag(AppTab.library)
            tabContent(.search, activeAPI: activeAPI)
                .tabItem { Label("Search", systemImage: AppTab.search.systemImage) }.tag(AppTab.search)
            tabContent(.downloads, activeAPI: activeAPI)
                .tabItem { Label("Downloads", systemImage: AppTab.downloads.systemImage) }.tag(AppTab.downloads)
            tabContent(.settings, activeAPI: activeAPI)
                .tabItem { Label("Settings", systemImage: AppTab.settings.systemImage) }.tag(AppTab.settings)
        }
    }

    /// Mac (medium and expanded) and iPad: the system's sidebar-adaptable
    /// `TabView`. Its sidebar has a brand row (header), the five destinations,
    /// a Playlists section (the first few; tapping one opens it on the
    /// Library's stack) and a connection row (bottom bar). Narrow iPad windows
    /// get the system's bottom tab bar from the same view.
    @available(iOS 18.0, macOS 15.0, *)
    private func sidebarTabs(activeAPI: JellyfinAPI) -> some View {
        TabView(selection: shellSelection) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: ShellItem.tab(tab)) {
                    tabContent(tab, activeAPI: activeAPI)
                }
            }
            TabSection("Playlists") {
                ForEach(sidebarPlaylistItems) { playlist in
                    Tab(playlist.name, systemImage: "music.note.list", value: ShellItem.playlist(playlist.id)) {
                        // Selecting it opens the playlist on Library and moves on: never shown.
                        Color.clear
                    }
                }
            }
            .defaultVisibility(.hidden, for: .tabBar)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewSidebarHeader { SidebarBrandRow() }
        .tabViewSidebarBottomBar {
            SidebarConnectionRow(serverName: serverName, online: NetworkMonitor.shared.isConnected)
        }
    }

    /// What the sidebar can select: a destination, or a playlist to open.
    enum ShellItem: Hashable {
        case tab(AppTab)
        case playlist(String)
    }

    /// Selecting a destination selects the tab; selecting a playlist opens it
    /// on Library's stack, and Library stays selected.
    private var shellSelection: Binding<ShellItem> {
        Binding(
            get: { .tab(selectedTab) },
            set: { item in
                switch item {
                case .tab(let tab):
                    selectedTab = tab
                case .playlist(let id):
                    guard let playlist = sidebarPlaylists?.playlists.first(where: { $0.id == id }) else { return }
                    paths[.library] = NavigationPath([playlist])
                    selectedTab = .library
                }
            })
    }

    private var sidebarPlaylistItems: [JellyfinPlaylist] {
        Array((sidebarPlaylists?.playlists ?? []).prefix(Self.sidebarPlaylistCount))
    }

    /// The server's address as the sidebar's connection row shows it.
    private var serverName: String {
        guard let url = api?.baseURL else { return "" }
        let host = url.host(percentEncoded: false) ?? url.absoluteString
        return url.port.map { "\(host):\($0)" } ?? host
    }

    private func reloadSidebarPlaylists(_ activeAPI: JellyfinAPI) {
        guard usesSidebarShell else { return }
        let model = sidebarPlaylists ?? PlaylistsViewModel(api: activeAPI, downloads: downloadManager?.playlists)
        sidebarPlaylists = model
        model.load(online: NetworkMonitor.shared.isConnected)
    }

    /// The Mac and iPad get the sidebar and the docked mini player; the iPhone
    /// keeps the native bottom tabs and the floating bar / accessory.
    private var usesSidebarShell: Bool { !Self.isPhone }

    static var isPhone: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .phone
        #else
        false
        #endif
    }

    /// A tab's navigation stack, centred and limited to 1600 pt on the Mac and iPad.
    @ViewBuilder
    private func tabContent(_ tab: AppTab, activeAPI: JellyfinAPI) -> some View {
        if usesSidebarShell {
            tabStack(tab, activeAPI: activeAPI).tetsubiMaxContentWidth()
        } else {
            tabStack(tab, activeAPI: activeAPI)
        }
    }

    @ViewBuilder
    private func tabStack(_ tab: AppTab, activeAPI: JellyfinAPI) -> some View {
        switch tab {
        case .home:
            NavigationStack(path: path(.home)) {
                HomeView(api: activeAPI, player: player, downloadManager: downloadManager,
                         onShowPlaylists: {
                             libraryTabRequest = .playlists
                             selectedTab = .library
                         })
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
        case .library:
            NavigationStack(path: path(.library)) {
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
        case .search:
            NavigationStack(path: path(.search)) {
                SearchView(
                    api: activeAPI,
                    player: player,
                    downloadManager: downloadManager
                )
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
        case .downloads:
            NavigationStack(path: path(.downloads)) {
                DownloadsView(downloadManager: downloadManager, player: player)
                .appTheme()
                .modifier(AlbumDestination(api: activeAPI, player: player, downloadManager: downloadManager))
            }
            .nowPlayingInset(player: player)
        case .settings:
            NavigationStack(path: path(.settings)) {
                SettingsView(
                    onSignOut: {
                        DispatchQueue.main.async {
                            session.signOut()
                            self.paths = [:]   // no pages from the old server
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
        }
    }

    /// A tab's navigation path.
    private func path(_ tab: AppTab) -> Binding<NavigationPath> {
        Binding(get: { paths[tab] ?? NavigationPath() },
                set: { paths[tab] = $0 })
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
                // Library's Artists, and the artist line on an album page and in Now Playing.
                .navigationDestination(for: JellyfinArtist.self) { artist in
                    ArtistAlbumsView(api: api, artist: artist)
                        .appTheme()
                }
        }
    }
}

// MARK: - Window width

private extension View {
    /// Tracks the window's width class and passes it down. The Mac and iPad
    /// follow the width; an iPhone is always compact. The width itself is passed
    /// down too (page controls go inline from 720 pt).
    @ViewBuilder
    func followsWindowWidth(_ widthClass: Binding<WindowWidthClass>, width: Binding<CGFloat>) -> some View {
        self
            .onGeometryChange(for: WindowWidthClass.self) {
                WindowWidthClass.resolve(width: $0.size.width, isPhone: AppRootView.isPhone)
            } action: {
                widthClass.wrappedValue = $0
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width.wrappedValue = $0 }
            .environment(\.windowWidthClass, widthClass.wrappedValue)
            .environment(\.windowWidth, width.wrappedValue)
    }
}

// MARK: - Now-playing placement

private extension View {
    /// Mac, compact width: hides the native tab bar and docks `CompactTabBar`
    /// under the content (and so under the mini player). The TabView stays, so
    /// each tab keeps its state when the window crosses 600 pt.
    @ViewBuilder
    func compactTabBarOnMac(isOn: Bool, selection: Binding<AppTab>,
                            popToRoot: @escaping (AppTab) -> Void) -> some View {
        #if os(macOS)
        if isOn {
            self.safeAreaInset(edge: .bottom, spacing: 0) {
                CompactTabBar(selection: selection, onReselect: popToRoot)
            }
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// The single full-screen Now Playing sheet, opened by the bar or by
    /// playing an album (`PlayerManager.isNowPlayingPresented`). Its artist
    /// line calls `onShowArtist`; `onDismiss` runs once the sheet has gone.
    ///
    /// iPad: a full-screen cover, as a sheet there is a form about 580 pt
    /// wide and Now Playing's two columns need 640 (`NowPlayingView.isWide`).
    /// iPhone and Mac keep the sheet.
    @ViewBuilder
    func nowPlayingSheet(player: PlayerManager?,
                         onShowArtist: @escaping (JellyfinArtist) -> Void,
                         onDismiss: @escaping () -> Void) -> some View {
        let isPresented = Binding(
            get: { player?.isNowPlayingPresented ?? false },
            set: { player?.isNowPlayingPresented = $0 }
        )
        #if os(iOS)
        if NowPlayingPresentation.usesFullScreenCover(idiom: UIDevice.current.userInterfaceIdiom) {
            fullScreenCover(isPresented: isPresented, onDismiss: onDismiss) {
                nowPlayingContent(player: player, onShowArtist: onShowArtist)
            }
        } else {
            sheet(isPresented: isPresented, onDismiss: onDismiss) {
                nowPlayingContent(player: player, onShowArtist: onShowArtist)
            }
        }
        #else
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            nowPlayingContent(player: player, onShowArtist: onShowArtist)
        }
        #endif
    }

    @ViewBuilder
    private func nowPlayingContent(player: PlayerManager?,
                                   onShowArtist: @escaping (JellyfinArtist) -> Void) -> some View {
        if let player {
            NowPlayingView(manager: player, onShowArtist: onShowArtist) {
                player.isNowPlayingPresented = false
            }
            .presentationCornerRadius(20)
            #if os(macOS)
            .nowPlayingSheetFrame()
            #endif
        }
    }

    /// iOS 26+: the lozenge is the tab-bar accessory, so the tab bar never moves.
    @ViewBuilder
    func nowPlayingAccessory(player: PlayerManager?) -> some View {
        #if os(iOS)
        if #available(iOS 26.1, *), AppRootView.isPhone {
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

    /// iPhone before iOS 26.1: a floating lozenge inset at the bottom of each
    /// tab's content, above the tab bar. Mac and iPad: the docked, full-width
    /// `DockedMiniPlayer` at the bottom of the content area (above the compact
    /// bar on a narrow Mac window).
    @ViewBuilder
    func nowPlayingInset(player: PlayerManager?) -> some View {
        #if os(iOS)
        if AppRootView.isPhone {
            if #available(iOS 26.1, *) {
                self
            } else {
                self.safeAreaInset(edge: .bottom, spacing: 0) {
                    if let player { NowPlayingBarView(manager: player) }
                }
            }
        } else {
            self.safeAreaInset(edge: .bottom, spacing: 0) {
                if let player { DockedMiniPlayer(manager: player) }
            }
        }
        #else
        self.safeAreaInset(edge: .bottom, spacing: 0) {
            if let player { DockedMiniPlayer(manager: player) }
        }
        #endif
    }
}

// MARK: - Sidebar pieces

/// The sidebar's top row: the disc logo and the name.
struct SidebarBrandRow: View {
    var body: some View {
        HStack(spacing: Spacing.s) {
            Image("DiscLogo")
                .resizable().scaledToFill()
                .frame(width: 24, height: 24)
                .clipShape(Circle())
            Text("Tetsubi").font(TetsubiType.rowTitle.weight(.semibold)).foregroundStyle(TetsubiColors.textPrimary)
                .lineLimit(1).minimumScaleFactor(0.8)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Spacing.xs)
        .padding(.vertical, Spacing.s)
        .accessibilityElement(children: .combine)
    }
}

/// The sidebar's bottom row: a green dot and the server's address, or a red
/// dot and "Offline".
struct SidebarConnectionRow: View {
    let serverName: String
    let online: Bool

    var body: some View {
        HStack(spacing: Spacing.s) {
            Circle().fill(online ? TetsubiColors.green : TetsubiColors.red).frame(width: 8, height: 8)
            Text(online ? serverName : "Offline")
                .font(TetsubiType.caption).foregroundStyle(TetsubiColors.textSecondary)
                .lineLimit(1).truncationMode(.middle)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(online ? "Connected to \(serverName)" : "Offline")
    }
}

#if os(iOS)
/// How Now Playing is presented on iOS.
enum NowPlayingPresentation {
    /// iPad covers the screen (the two-column layout needs ≥ 640 pt, more
    /// than an iPad sheet's width); iPhone keeps the sheet, even on its side
    /// where a Max is regular width.
    static func usesFullScreenCover(idiom: UIUserInterfaceIdiom) -> Bool {
        idiom == .pad
    }
}
#endif

