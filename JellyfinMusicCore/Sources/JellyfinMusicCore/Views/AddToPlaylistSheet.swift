// MARK: - AddToPlaylistSheet — pick a playlist (or make one) for some songs

import SwiftUI
import Observation

// MARK: - Model

/// Lists the playlists the user can change, and adds the songs to the
/// one chosen (asking first when some are already in it) or to a new one.
@MainActor
@Observable
final class AddToPlaylistModel {

    /// "Already in <name>. Add again?", waiting for Add or Skip.
    struct Question: Identifiable {
        let id = UUID()
        let playlist: JellyfinPlaylist
        let plan: PlaylistAdd.Plan
    }

    let trackIds: [String]
    private(set) var playlists: [JellyfinPlaylist] = []
    private(set) var isLoading = false
    private(set) var hasLoaded = false
    private(set) var errorMessage: String?
    /// A playlist was chosen; its songs are being checked or added.
    private(set) var isWorking = false
    var question: Question?
    /// Set when there's nothing more to do; the sheet closes.
    private(set) var isFinished = false

    private let api: JellyfinAPI
    private let events: PlaylistEvents

    init(api: JellyfinAPI, trackIds: [String], events: PlaylistEvents? = nil) {
        self.api = api
        self.trackIds = trackIds
        self.events = events ?? .shared
    }

    // MARK: Loading

    /// The user's playlists by name; only those they may change.
    func load() {
        isLoading = true
        errorMessage = nil
        api.playlists { [weak self, api] result in
            switch result {
            case .failure(let error):
                DispatchQueue.main.async {
                    self?.isLoading = false
                    self?.errorMessage = error.errorDescription ?? "\(error)"
                }
            case .success(let found):
                let group = DispatchGroup()
                let lock = NSLock()
                var editable: Set<String> = []
                for playlist in found {
                    group.enter()
                    api.canEditPlaylist(playlist.id) { canEdit in
                        if canEdit {
                            lock.lock()
                            editable.insert(playlist.id)
                            lock.unlock()
                        }
                        group.leave()
                    }
                }
                group.notify(queue: .main) {
                    guard let self else { return }
                    self.playlists = PlaylistsViewModel.sorted(found.filter { editable.contains($0.id) }, by: .name)
                    self.isLoading = false
                    self.hasLoaded = true
                }
            }
        }
    }

    // MARK: Adding

    /// The playlist was tapped: look at what's in it, then add, or ask
    /// first when some of the songs are already there.
    func choose(_ playlist: JellyfinPlaylist) {
        guard !isWorking else { return }
        isWorking = true
        api.playlistTracks(playlist.id) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .failure(let error):
                    self.fail(PlaylistWording.addFailed(to: playlist.name, error: error.errorDescription ?? "\(error)"))
                case .success(let entries):
                    let plan = PlaylistAdd.plan(adding: self.trackIds, existing: entries.map(\.track.id))
                    if plan.needsQuestion {
                        self.question = Question(playlist: playlist, plan: plan)
                    } else {
                        self.add(plan.all, to: playlist)
                    }
                }
            }
        }
    }

    func answer(_ question: Question, _ choice: PlaylistAdd.Choice) {
        self.question = nil
        add(question.plan.trackIds(for: choice), to: question.playlist)
    }

    /// The question was put away without an answer: back to the list.
    func questionDismissed() {
        guard !isFinished else { return }
        isWorking = false
    }

    private func add(_ ids: [String], to playlist: JellyfinPlaylist) {
        // Skipped, and every song was already there: nothing to send.
        guard !ids.isEmpty else {
            isFinished = true
            return
        }
        api.addToPlaylist(playlist.id, trackIds: ids) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success:
                    self.events.playlistsChanged()
                    self.events.show(PlaylistWording.added(count: ids.count, to: playlist.name))
                    self.isFinished = true
                case .failure(let error):
                    self.fail(PlaylistWording.addFailed(to: playlist.name, error: error.errorDescription ?? "\(error)"))
                }
            }
        }
    }

    /// "New Playlist…": make it with the songs already in it.
    func create(named raw: String) {
        guard !isWorking, let name = PlaylistName.cleaned(raw) else { return }
        isWorking = true
        let ids = trackIds
        api.createPlaylist(name: name, trackIds: ids) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success:
                    self.events.playlistsChanged()
                    if !ids.isEmpty {
                        self.events.show(PlaylistWording.added(count: ids.count, to: name))
                    }
                    self.isFinished = true
                case .failure(let error):
                    self.fail(PlaylistWording.createFailed(error: error.errorDescription ?? "\(error)"))
                }
            }
        }
    }

    private func fail(_ message: String) {
        events.show(message, isError: true)
        isFinished = true
    }
}

// MARK: - Sheet

/// "Add to Playlist": "New Playlist…" at the top, then the user's
/// playlists with their song counts. Tapping one adds the songs.
struct AddToPlaylistSheet: View {

    @State private var model: AddToPlaylistModel
    @State private var isNamingNew = false
    @State private var newName = ""
    @Environment(\.dismiss) private var dismiss

    init(api: JellyfinAPI, trackIds: [String]) {
        _model = State(initialValue: AddToPlaylistModel(api: api, trackIds: trackIds))
    }

    var body: some View {
        NavigationStack {
            List {
                Button {
                    newName = ""
                    isNamingNew = true
                } label: {
                    Label("New Playlist…", systemImage: "plus")
                        .font(.plex(.body))
                }
                .accessibilityIdentifier("AddToPlaylistSheet.new")

                if let error = model.errorMessage {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Couldn't load your playlists. \(error)")
                            .font(.plex(.footnote))
                            .foregroundStyle(.secondary)
                        Button("Retry") { model.load() }
                            .font(.plex(.footnote).bold())
                            .buttonStyle(.bordered)
                    }
                } else if model.isLoading, !model.hasLoaded {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    ForEach(model.playlists) { playlist in
                        Button {
                            model.choose(playlist)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(playlist.name)
                                    .font(.plex(.body))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Text(JellyfinPlaylist.songCountText(playlist.songCount))
                                    .font(.plex(.caption))
                                    .foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("AddToPlaylistSheet.playlist.\(playlist.id)")
                    }
                }
            }
            .disabled(model.isWorking)
            .navigationTitle("Add to Playlist")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360.scaled, minHeight: 420.scaled)
        #endif
        .task { model.load() }
        .onChange(of: model.isFinished) { _, finished in
            if finished { dismiss() }
        }
        .newPlaylistPrompt(isPresented: $isNamingNew, name: $newName) { model.create(named: $0) }
        .alert(model.question.map { PlaylistWording.duplicateQuestion(playlist: $0.playlist.name) } ?? "",
               isPresented: Binding(get: { model.question != nil },
                                    set: { if !$0 { model.question = nil; model.questionDismissed() } }),
               presenting: model.question) { question in
            Button("Add") { model.answer(question, .add) }
            Button("Skip", role: .cancel) { model.answer(question, .skip) }
        }
    }
}

// MARK: - Name prompts

extension View {
    /// "New Playlist": asks for a name. `onCreate` gets it as typed; Create
    /// is off until there's something other than spaces.
    func newPlaylistPrompt(isPresented: Binding<Bool>, name: Binding<String>,
                           onCreate: @escaping (String) -> Void) -> some View {
        alert("New Playlist", isPresented: isPresented) {
            TextField("Playlist name", text: name)
            Button("Create") { onCreate(name.wrappedValue) }
                .disabled(PlaylistName.cleaned(name.wrappedValue) == nil)
            Button("Cancel", role: .cancel) {}
        }
    }

    /// "Rename Playlist": the same rules as a new playlist's name.
    func renamePlaylistPrompt(isPresented: Binding<Bool>, name: Binding<String>,
                              onRename: @escaping (String) -> Void) -> some View {
        alert("Rename Playlist", isPresented: isPresented) {
            TextField("Playlist name", text: name)
            Button("Rename") { onRename(name.wrappedValue) }
                .disabled(PlaylistName.cleaned(name.wrappedValue) == nil)
            Button("Cancel", role: .cancel) {}
        }
    }
}

// MARK: - Host

/// Presents what `PlaylistEvents` asks for: the "Add to Playlist…" sheet,
/// the Mac menu's "New Playlist…" prompt, and the short notice afterwards.
/// On the main window and on Now Playing; only the one in front (`isActive`)
/// presents the sheet and the prompt.
private struct PlaylistEventsHost: ViewModifier {
    let isActive: Bool
    /// Called when "New Playlist…" has made an (empty) playlist.
    var onCreated: (() -> Void)?

    @State private var newName = ""
    private var events: PlaylistEvents { .shared }
    private var api: JellyfinAPI? { AppSession.shared.api }

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if let notice = events.notice {
                    PlaylistNoticeView(notice: notice)
                        .onTapGesture { events.dismissNotice() }
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: events.notice)
            .sheet(item: Binding(get: { isActive ? events.addRequest : nil },
                                 set: { if $0 == nil, isActive { events.addRequest = nil } })) { request in
                if let api {
                    AddToPlaylistSheet(api: api, trackIds: request.trackIds)
                        .appTheme()
                }
            }
            .newPlaylistPrompt(isPresented: Binding(get: { isActive && events.isAskingForNewPlaylist },
                                                    set: { if !$0, isActive { events.isAskingForNewPlaylist = false } }),
                               name: $newName) { raw in
                createEmptyPlaylist(named: raw)
            }
            .onChange(of: events.isAskingForNewPlaylist) { _, asking in
                if asking { newName = "" }
            }
    }

    private func createEmptyPlaylist(named raw: String) {
        guard let api, let name = PlaylistName.cleaned(raw) else { return }
        api.createPlaylist(name: name, trackIds: []) { result in
            DispatchQueue.main.async {
                switch result {
                case .success:
                    events.playlistsChanged()
                    onCreated?()
                case .failure(let error):
                    events.show(PlaylistWording.createFailed(error: error.errorDescription ?? "\(error)"),
                                isError: true)
                }
            }
        }
    }
}

/// The notice after adding: the usual banner, on a solid backing so it
/// reads over whatever is under it. Tap to put it away.
private struct PlaylistNoticeView: View {
    let notice: PlaylistEvents.Notice

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: notice.isError ? "exclamationmark.triangle" : "checkmark.circle")
                .foregroundStyle(Color.appPink)
            Text(notice.message)
                .font(.plex(.footnote))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .background(Color.appBackground, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.15)))
        .padding(.horizontal)
        .padding(.top, 8)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("PlaylistNotice")
    }
}

extension View {
    /// See `PlaylistEventsHost`.
    func playlistEventsHost(isActive: Bool, onCreated: (() -> Void)? = nil) -> some View {
        modifier(PlaylistEventsHost(isActive: isActive, onCreated: onCreated))
    }
}
