// MARK: - CarPlay — browse and play from the car's screen

import CarPlay
import UIKit
import JellyfinMusicCore

/// CarPlay audio UI: Home / Artists / Playlists / Downloads tabs, album and
/// playlist pages with Play and Shuffle, and the system Now Playing screen.
///
/// Uses `AppSession.shared`, so it shares the phone app's player and queue.
/// CarPlay can launch the app without the phone UI, so it restores the saved
/// login itself.
///
/// Only appears in a car (or Xcode's CarPlay Simulator) when the app is
/// signed with the `com.apple.developer.carplay-audio` entitlement.
@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate, CPTabBarTemplateDelegate {

    private var interfaceController: CPInterfaceController?
    private let homeTemplate = CPListTemplate(title: "Home", sections: [])
    private let artistsTemplate = CPListTemplate(title: "Artists", sections: [])
    private let playlistsTemplate = CPListTemplate(title: "Playlists", sections: [])
    private let downloadsTemplate = CPListTemplate(title: "Downloads", sections: [])

    private var session: AppSession { AppSession.shared }
    private var maniacObserver: NSObjectProtocol?

    // MARK: Connect / disconnect

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        session.restore()

        homeTemplate.tabTitle = "Home"
        homeTemplate.tabImage = UIImage(systemName: "house")
        artistsTemplate.tabTitle = "Artists"
        artistsTemplate.tabImage = UIImage(systemName: "music.mic")
        playlistsTemplate.tabTitle = "Playlists"
        playlistsTemplate.tabImage = UIImage(systemName: "music.note.list")
        downloadsTemplate.tabTitle = "Downloads"
        downloadsTemplate.tabImage = UIImage(systemName: "arrow.down.circle")

        let tabs = CPTabBarTemplate(templates: [homeTemplate, artistsTemplate, playlistsTemplate, downloadsTemplate])
        tabs.delegate = self
        interfaceController.setRootTemplate(tabs, animated: false, completion: nil)
        configureNowPlaying()
        // Maniac Mode switched on the phone: update the buttons here too.
        maniacObserver = NotificationCenter.default.addObserver(
            forName: PlayerManager.maniacModeDidChange, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.configureNowPlaying() }
        }

        reloadHome()
        reloadArtists()
        reloadPlaylists()
        reloadDownloads()
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
        if let maniacObserver { NotificationCenter.default.removeObserver(maniacObserver) }
        maniacObserver = nil
    }

    /// Refresh a tab whenever it's opened (new plays, finished downloads).
    func tabBarTemplate(_ tabBarTemplate: CPTabBarTemplate, didSelect selectedTemplate: CPTemplate) {
        if selectedTemplate === homeTemplate { reloadHome() }
        if selectedTemplate === playlistsTemplate { reloadPlaylists() }
        if selectedTemplate === downloadsTemplate { reloadDownloads() }
    }

    // MARK: Tabs

    private func reloadHome() {
        guard let api = session.api else {
            showSignedOut(homeTemplate)
            return
        }
        var recentlyPlayed: [JellyfinAlbum] = []
        var favourites: [JellyfinAlbum] = []
        var recentlyAdded: [JellyfinAlbum] = []
        var failed = false
        let group = DispatchGroup()

        group.enter()
        api.recentlyPlayedAlbums(limit: 12) { result in
            DispatchQueue.main.async {
                switch result {
                case .success(let albums): recentlyPlayed = DiscGrouping.merge(albums)
                case .failure: failed = true
                }
                group.leave()
            }
        }
        group.enter()
        api.favoriteAlbums { result in
            DispatchQueue.main.async {
                if case .success(let albums) = result {
                    favourites = Array(DiscGrouping.merge(FavoritesStore.ordered(albums)).prefix(12))
                }
                group.leave()
            }
        }
        group.enter()
        api.recentItems(limit: 12, itemTypes: "MusicAlbum") { result in
            DispatchQueue.main.async {
                if case .success(let albums) = result { recentlyAdded = DiscGrouping.merge(albums) }
                group.leave()
            }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            let sections = [("Recently Played", recentlyPlayed), ("Favourites", favourites),
                            ("Recently Added", recentlyAdded)]
                .filter { !$0.1.isEmpty }
                .map { CPListSection(items: $0.1.map(self.albumItem), header: $0.0, sectionIndexTitle: nil) }
            self.homeTemplate.updateSections(sections)
            self.homeTemplate.emptyViewTitleVariants = failed
                ? ["Can't reach your Jellyfin server"]
                : ["Nothing here yet"]
            self.homeTemplate.emptyViewSubtitleVariants = failed ? ["Downloaded albums are in Downloads."] : []
        }
    }

    private func reloadArtists() {
        guard let api = session.api else {
            showSignedOut(artistsTemplate)
            return
        }
        api.albumArtists { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                guard case .success(let artists) = result else {
                    self.artistsTemplate.updateSections([])
                    self.artistsTemplate.emptyViewTitleVariants = ["Can't reach your Jellyfin server"]
                    return
                }
                // Cars cap list length; keep the first N alphabetically.
                let limited = artists
                    .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                    .prefix(CPListTemplate.maximumItemCount)
                let items = limited.map { artist -> CPListItem in
                    let item = CPListItem(text: artist.name, detailText: nil)
                    item.accessoryType = .disclosureIndicator
                    item.handler = { [weak self] _, done in
                        self?.showArtist(artist)
                        done()
                    }
                    return item
                }
                self.artistsTemplate.updateSections([CPListSection(items: Array(items))])
            }
        }
    }

    /// The server's playlists; without it, the downloaded ones.
    private func reloadPlaylists() {
        guard session.api != nil else {
            showSignedOut(playlistsTemplate)
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            let found = await self.session.playlists()
            // Cars cap list length; keep the first N by name.
            let items = found.playlists.prefix(CPListTemplate.maximumItemCount).map { playlist -> CPListItem in
                let item = CPListItem(text: playlist.name, detailText: playlist.summary)
                item.accessoryType = .disclosureIndicator
                self.loadArt(playlist: playlist, fromServer: found.fromServer, into: item)
                item.handler = { [weak self] _, done in
                    self?.showPlaylist(playlist)
                    done()
                }
                return item
            }
            self.playlistsTemplate.updateSections([CPListSection(items: Array(items))])
            self.playlistsTemplate.emptyViewTitleVariants = found.fromServer
                ? ["No playlists yet"]
                : ["No downloaded playlists"]
            self.playlistsTemplate.emptyViewSubtitleVariants = found.fromServer
                ? []
                : ["Download playlists on your iPhone to play them offline."]
        }
    }

    private func reloadDownloads() {
        let ids = DownloadPaths.downloadedAlbumIDs()
        let albums = ids.map { id -> (id: String, title: String, artist: String) in
            let info = DownloadManager.storedAlbumInfo(for: id)
            return (id, info?.title ?? "Album", info?.artist ?? "")
        }
        .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        .prefix(CPListTemplate.maximumItemCount)

        let items = albums.map { album -> CPListItem in
            let item = CPListItem(text: album.title, detailText: album.artist)
            loadArt(albumID: album.id, into: item)
            item.handler = { [weak self] _, done in
                self?.session.player?.playLocalDownloadedAlbum(
                    albumID: album.id, albumTitle: album.title, artistName: album.artist)
                self?.showNowPlaying()
                done()
            }
            return item
        }
        downloadsTemplate.updateSections([CPListSection(items: Array(items))])
        downloadsTemplate.emptyViewTitleVariants = ["No downloaded albums"]
        downloadsTemplate.emptyViewSubtitleVariants = ["Download albums on your iPhone to play them offline."]
    }

    private func showSignedOut(_ template: CPListTemplate) {
        template.updateSections([])
        template.emptyViewTitleVariants = ["Not signed in"]
        template.emptyViewSubtitleVariants = ["Open Tetsubi on your iPhone to sign in."]
    }

    // MARK: Artist and album pages

    private func showArtist(_ artist: JellyfinArtist) {
        guard let api = session.api else { return }
        let template = CPListTemplate(title: artist.name, sections: [])
        template.emptyViewTitleVariants = ["Loading…"]
        interfaceController?.pushTemplate(template, animated: true, completion: nil)
        api.albums(forArtist: artist.id) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                let albums = (try? result.get()).map(DiscGrouping.merge) ?? []
                template.updateSections([CPListSection(items: albums.map(self.albumItem))])
                template.emptyViewTitleVariants = ["No albums"]
            }
        }
    }

    private func albumItem(_ album: JellyfinAlbum) -> CPListItem {
        let item = CPListItem(text: album.displayName, detailText: album.artistName)
        item.accessoryType = .disclosureIndicator
        loadArt(albumID: album.id, into: item)
        item.handler = { [weak self] _, done in
            self?.showAlbum(album)
            done()
        }
        return item
    }

    /// Play / Shuffle, then the tracks (under "Disc N" for multi-disc albums).
    private func showAlbum(_ album: JellyfinAlbum) {
        guard let api = session.api else { return }
        let template = CPListTemplate(title: album.displayName, sections: [])
        template.emptyViewTitleVariants = ["Loading…"]
        interfaceController?.pushTemplate(template, animated: true, completion: nil)

        let discs = album.allDiscs
        var perDisc = [[JellyfinTrack]?](repeating: nil, count: discs.count)
        let group = DispatchGroup()
        for (i, disc) in discs.enumerated() {
            group.enter()
            api.tracksForAlbum(disc.id) { result in
                DispatchQueue.main.async {
                    perDisc[i] = try? result.get()
                    group.leave()
                }
            }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            let play = CPListItem(text: "Play", detailText: nil, image: UIImage(systemName: "play.fill"))
            play.handler = { [weak self] _, done in
                self?.play(album, shuffled: false)
                done()
            }
            let shuffle = CPListItem(text: "Shuffle", detailText: nil, image: UIImage(systemName: "shuffle"))
            shuffle.handler = { [weak self] _, done in
                self?.play(album, shuffled: true)
                done()
            }
            // Maniac Mode: no Shuffle, and track rows play from track 1.
            let maniac = self.session.player?.maniacMode ?? false
            var sections = [CPListSection(items: maniac ? [play] : [play, shuffle])]
            // Sections: one per disc album, or per disc tag inside one album.
            var groups: [(header: String?, tracks: [JellyfinTrack])] = []
            for (i, tracks) in perDisc.enumerated() {
                guard let tracks, !tracks.isEmpty else { continue }
                if discs.count > 1 {
                    groups.append(("Disc \(DiscGrouping.discNumber(of: discs[i]) ?? i + 1)", tracks))
                } else if let byTag = DiscGrouping.byDiscNumber(tracks) {
                    groups += byTag.map { ("Disc \($0.number)", $0.tracks) }
                } else {
                    groups.append((nil, tracks))
                }
            }
            for group in groups {
                let items = group.tracks.map { track -> CPListItem in
                    let item = CPListItem(text: track.displayName,
                                          detailText: Self.duration(track.duration))
                    item.handler = { [weak self] _, done in
                        self?.session.player?.play(discs: discs, as: album, startingAt: track.id)
                        self?.showNowPlaying()
                        done()
                    }
                    return item
                }
                sections.append(CPListSection(items: items, header: group.header, sectionIndexTitle: nil))
            }
            template.updateSections(sections)
            template.emptyViewTitleVariants = ["Can't load this album"]
        }
    }

    // MARK: Playlist page

    /// Play / Shuffle, then the songs in playlist order.
    private func showPlaylist(_ playlist: JellyfinPlaylist) {
        let template = CPListTemplate(title: playlist.name, sections: [])
        template.emptyViewTitleVariants = ["Loading…"]
        interfaceController?.pushTemplate(template, animated: true, completion: nil)
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard let rows = await self.session.playlistRows(playlist.id) else {
                template.emptyViewTitleVariants = ["Can't load this playlist"]
                return
            }
            guard !rows.entries.isEmpty else {
                template.emptyViewTitleVariants = ["This playlist is empty."]
                return
            }
            let play = CPListItem(text: "Play", detailText: nil, image: UIImage(systemName: "play.fill"))
            play.handler = { [weak self] _, done in
                self?.play(playlist, rows: rows, shuffled: false)
                done()
            }
            let shuffle = CPListItem(text: "Shuffle", detailText: nil, image: UIImage(systemName: "shuffle"))
            shuffle.handler = { [weak self] _, done in
                self?.play(playlist, rows: rows, shuffled: true)
                done()
            }
            // Maniac Mode: no Shuffle, and song rows play from the top.
            let maniac = self.session.player?.maniacMode ?? false
            // Cars cap list length; the queue still holds the whole playlist.
            let shown = rows.entries.prefix(max(0, CPListTemplate.maximumItemCount - 2))
            let songs = shown.map { entry -> CPListItem in
                let item = CPListItem(text: entry.track.displayName,
                                      detailText: entry.detailLine.isEmpty
                                          ? Self.duration(entry.track.duration) : entry.detailLine)
                // By its place in the playlist: a song can be there twice.
                item.handler = { [weak self] _, done in
                    self?.play(playlist, rows: rows, startingAt: entry.position, shuffled: nil)
                    done()
                }
                return item
            }
            template.updateSections([CPListSection(items: maniac ? [play] : [play, shuffle]),
                                     CPListSection(items: songs)])
        }
    }

    /// Offline, with the song (or every song) not on this device, nothing
    /// plays and an alert says why.
    private func play(_ playlist: JellyfinPlaylist, rows: AppSession.PlaylistRows,
                      startingAt position: Int? = nil, shuffled: Bool?) {
        if let refusal = session.play(playlist, rows: rows, startingAt: position, shuffled: shuffled) {
            let ok = CPAlertAction(title: "OK", style: .cancel) { [weak self] _ in
                self?.interfaceController?.dismissTemplate(animated: true, completion: nil)
            }
            interfaceController?.presentTemplate(CPAlertTemplate(titleVariants: [refusal.message], actions: [ok]),
                                                 animated: true, completion: nil)
            return
        }
        showNowPlaying()
    }

    private func play(_ album: JellyfinAlbum, shuffled: Bool) {
        guard let player = session.player else { return }
        player.setShuffle(shuffled)
        player.play(discs: album.allDiscs, as: album)
        showNowPlaying()
    }

    // MARK: Now Playing

    /// Shuffle and repeat buttons, or none in Maniac Mode. (Next/previous
    /// follow the remote commands, which Maniac Mode disables.)
    private func configureNowPlaying() {
        if session.player?.maniacMode == true {
            CPNowPlayingTemplate.shared.updateNowPlayingButtons([])
            return
        }
        let shuffle = CPNowPlayingShuffleButton { [weak self] _ in
            self?.session.player?.toggleShuffle()
        }
        let repeatButton = CPNowPlayingRepeatButton { [weak self] _ in
            self?.session.player?.cycleRepeatMode()
        }
        CPNowPlayingTemplate.shared.updateNowPlayingButtons([shuffle, repeatButton])
    }

    private func showNowPlaying() {
        guard let interfaceController,
              interfaceController.topTemplate !== CPNowPlayingTemplate.shared else { return }
        interfaceController.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
    }

    // MARK: Helpers

    private func loadArt(albumID: String, into item: CPListItem) {
        guard let request = session.api?.imageURLRequest(for: albumID, size: 200) else { return }
        Task { @MainActor in
            guard let cgImage = await ImageCache.load(request) else { return }
            item.setImage(UIImage(cgImage: cgImage))
        }
    }

    /// A playlist's own picture (the saved one without the server). One
    /// without its own picture shows none in the list.
    private func loadArt(playlist: JellyfinPlaylist, fromServer: Bool, into item: CPListItem) {
        if !fromServer {
            if let file = PlaylistDownloadPaths.savedCover(playlistID: playlist.id),
               let image = UIImage(contentsOfFile: file.path) {
                item.setImage(image)
            }
            return
        }
        if playlist.hasArt { loadArt(albumID: playlist.id, into: item) }
    }

    private static func duration(_ seconds: TimeInterval) -> String? {
        guard seconds > 0 else { return nil }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
