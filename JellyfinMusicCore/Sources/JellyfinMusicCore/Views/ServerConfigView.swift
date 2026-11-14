// MARK: - ServerConfigView — Jellyfin login UI (Quick Connect first, username/password fallback)

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Login screen for a Jellyfin server.
///
/// **Primary flow — Quick Connect.** On appear the view starts a Quick Connect
/// session and shows a 6-digit code the user approves on another device (or the
/// server). On success it calls `onConnected` straight to the main screen.
///
/// **Fallback — username & password.** A "Sign in with username & password"
/// link, and auto-reveal on any Quick Connect failure (server has it disabled,
/// the user rejects, or it times out), swap in the classic credential form.
/// "Use Quick Connect instead" swaps back and asks for a fresh code.
/// The server URL field is shared by both. Below it, servers found on the
/// local network (`ServerDiscovery`) can be tapped instead of typing; if
/// exactly one is found and nothing's typed yet, it's picked automatically.
public struct ServerConfigView: View {

    // MARK: Callbacks

    /// Called on successful authentication (either flow).
    public let onConnected: (JellyfinAPI) -> Void
    /// Called after the user taps "Cancel".
    public let onCancel: () -> Void

    // MARK: State

    private enum Mode { case quickConnect, manual }

    @State private var mode: Mode = .quickConnect
    @State private var quickConnect = QuickConnectController()
    @State private var serverURL: String = ""
    @State private var username: String  = ""
    @State private var password: String  = ""
    @State private var isConnecting = false
    @State private var errorMessage: String?
    @State private var started = false
    @State private var discovered: [DiscoveredServer] = []
    @State private var isSearching = false
    @State private var hasSearched = false
    /// Working out the full address (scheme, port) before Quick Connect.
    @State private var isFindingServer = false
    @State private var findTask: Task<Void, Never>?

    /// Example address, shown in the empty field and in errors.
    private static let exampleURL = "http://192.168.1.20:8096"

    private let authenticator = Authenticator()

    // MARK: Init

    public init(onConnected: @escaping (JellyfinAPI) -> Void,
                onCancel: @escaping () -> Void) {
        self.onConnected = onConnected
        self.onCancel    = onCancel
    }

    // MARK: Body

    public var body: some View {
        // No NavigationStack: it would paint an opaque system background
        // over the app's background picture (AppRootView applies appTheme).
        ScrollView {
            VStack(spacing: 20) {
                header

                // Server URL — shared by both flows.
                box {
                    VStack(alignment: .leading, spacing: 14) {
                    field("Server URL") {
                        TextField(Self.exampleURL, text: $serverURL)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .textContentType(.URL)
                            #endif
                            .onSubmit {
                                // New server: ask it for a fresh code.
                                if mode == .quickConnect { startQuickConnect() }
                            }
                    }
                    discoveryList
                    }
                }

                // The two login modes swap in place.
                if mode == .quickConnect {
                    quickConnectPanel
                } else {
                    manualPanel
                }
            }
            .padding()
            .frame(maxWidth: 440.scaled)
            .frame(maxWidth: .infinity)
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .animation(.easeInOut(duration: 0.2), value: mode)
        .onAppear {
            // Auto-start Quick Connect the first time the screen appears.
            if !started {
                started = true
                quickConnect.onConnected = { onConnected($0) }
                if mode == .quickConnect && hasServerURL { startQuickConnect() }
                searchNetwork()
            }
        }
        .onChange(of: quickConnect.state) { _, newState in
            // On any Quick Connect failure, reveal the manual form and
            // surface the message so the user can sign in without extra taps.
            if case .failed(let message) = newState {
                mode = .manual
                errorMessage = message
            }
        }
        .frame(minWidth: 300, minHeight: 400)
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 10) {
            // The app icon's disc (DiscLogo in each app's asset catalog).
            Image("DiscLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 112.scaled, height: 112.scaled)
                .shadow(color: .black.opacity(0.35), radius: 12, y: 4)
                .accessibilityHidden(true)
            Text("Sign in to Jellyfin")
                .font(.plex(.title2).bold())
            Text("Tetsubi")
                .font(.plex(.subheadline))
                .foregroundStyle(Color.albumArtistText)
        }
        .padding(.top, 40)
        .padding(.bottom, 4)
    }

    // MARK: - Quick Connect panel

    private var quickConnectPanel: some View {
        box {
            VStack(spacing: 12) {
                Text("Quick Connect")
                    .font(.plex(.headline))
                Text("Approve this login on your Jellyfin server, or in the Jellyfin app on another device you're signed into.")
                    .font(.plex(.footnote))
                    .foregroundStyle(Color.albumArtistText)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)

                if isFindingServer {
                    waiting("Finding server…")
                } else {
                switch quickConnect.state {
                case .starting:
                    waiting("Requesting login code…")
                case .pending(let code):
                    VStack(spacing: 10) {
                        Text(code)
                            .font(.plex(size: 44, weight: .bold))
                            .monospacedDigit()
                            .tracking(4)
                            .foregroundStyle(Color.appBlue)
                            .textSelection(.enabled)
                            .padding(.vertical, 4)
                        waiting("Waiting for approval…")
                    }
                case .failed(let message):
                    errorText(message)
                case .idle:
                    Text(hasServerURL ? "Press return to get a code." : "Choose your server above, or type its address.")
                        .font(.plex(.footnote))
                        .foregroundStyle(Color.albumYearText)
                        .multilineTextAlignment(.center)
                }
                }

                // Let the user bypass Quick Connect and sign in with credentials.
                switchButton("Sign in with username & password") {
                    quickConnect.cancel()
                    mode = .manual
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Username & password panel

    private var manualPanel: some View {
        box {
            VStack(spacing: 14) {
                Text("Username & password")
                    .font(.plex(.headline))

                if let errorMessage {
                    errorText(errorMessage)
                }

                field("Username") {
                    TextField("Username", text: $username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .textContentType(.username)
                        #endif
                }

                field("Password") {
                    SecureField("Password", text: $password)
                        #if os(iOS)
                        .textContentType(.password)
                        #endif
                        .onSubmit { if !password.isEmpty && !isConnecting { connect() } }
                }

                Button {
                    connect()
                } label: {
                    HStack(spacing: 8) {
                        if isConnecting { ProgressView().controlSize(.small) }
                        Text(isConnecting ? "Signing in…" : "Sign in")
                            .bold()
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.appBlue)
                .disabled(isConnecting || password.isEmpty || !hasServerURL)
                .padding(.top, 4)

                // Back to Quick Connect, with a fresh code.
                switchButton("Use Quick Connect instead") {
                    errorMessage = nil
                    mode = .quickConnect
                    startQuickConnect()
                }
                .disabled(isConnecting)
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Servers on the network

    private var discoveryList: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("On your network")
                    .font(.plex(.caption))
                    .foregroundStyle(Color.albumArtistText)
                Spacer()
                if isSearching {
                    ProgressView().controlSize(.small)
                } else {
                    Button {
                        searchNetwork()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(Color.appBlue)
                    .accessibilityLabel("Search again")
                }
            }

            if discovered.isEmpty {
                Text(isSearching || !hasSearched
                     ? "Looking for servers…"
                     : "No servers found. Type your server's address above.")
                    .font(.plex(.footnote))
                    .foregroundStyle(Color.albumYearText)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ForEach(discovered) { server in
                let selected = server.address == trimmedServerURL
                Button {
                    pick(server)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "server.rack")
                            .foregroundStyle(Color.appBlue)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(server.name)
                                .font(.plex(.subheadline).bold())
                            Text(server.address)
                                .font(.plex(.caption))
                                .foregroundStyle(Color.albumArtistText)
                        }
                        Spacer()
                        if selected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(Color.appBlue)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(selected ? Color.appBlue.opacity(0.2) : Color.white.opacity(0.06),
                                in: RoundedRectangle(cornerRadius: 8))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }

    private func searchNetwork() {
        guard !isSearching else { return }
        isSearching = true
        Task {
            let servers = await ServerDiscovery.search()
            discovered = servers
            isSearching = false
            hasSearched = true
            // One server and nothing typed yet: pick it.
            if servers.count == 1, !hasServerURL { pick(servers[0]) }
        }
    }

    private func pick(_ server: DiscoveredServer) {
        guard server.address != trimmedServerURL else { return }
        serverURL = server.address
        errorMessage = nil
        if mode == .quickConnect { startQuickConnect() }
    }

    private var trimmedServerURL: String {
        serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var hasServerURL: Bool { !trimmedServerURL.isEmpty }

    // MARK: - Building blocks

    /// A dark rounded box, as on Settings and Downloads.
    private func box<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.appGroupBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    /// A labelled text field on a translucent fill.
    private func field<Field: View>(_ label: String, @ViewBuilder _ input: () -> Field) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.plex(.caption))
                .foregroundStyle(Color.albumArtistText)
            input()
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.white.opacity(0.15)))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func waiting(_ text: String) -> some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text(text)
                .font(.plex(.footnote))
                .foregroundStyle(Color.albumArtistText)
        }
    }

    private func errorText(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(.plex(.footnote))
            .foregroundStyle(Color(red: 1, green: 0.55, blue: 0.55))
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The link that swaps between the two sign-in methods.
    private func switchButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .font(.plex(.footnote).bold())
            .buttonStyle(.borderless)
            .foregroundStyle(Color.appBlue)
            .padding(.top, 2)
    }

    /// Works out the full address (`ServerAddress`: adds http://, tries
    /// port 8096…), shows it in the field, then asks it for a code.
    private func startQuickConnect() {
        findTask?.cancel()
        quickConnect.cancel()
        guard hasServerURL else { isFindingServer = false; return }
        let typed = trimmedServerURL
        isFindingServer = true
        findTask = Task {
            let result = await ServerAddress.resolve(typed)
            // Ignore the answer if the address changed meanwhile.
            guard !Task.isCancelled, typed == trimmedServerURL else { return }
            isFindingServer = false
            switch result {
            case .success(let url):
                serverURL = url.absoluteString
                quickConnect.start(serverURL: url)
            case .failure(let error):
                mode = .manual
                errorMessage = error.errorDescription
            }
        }
    }

    // MARK: - Manual connect

    private func connect() {
        errorMessage = nil
        isConnecting  = true
        findTask?.cancel()
        isFindingServer = false
        let typed = trimmedServerURL
        Task {
            switch await ServerAddress.resolve(typed) {
            case .success(let url):
                serverURL = url.absoluteString
                signIn(at: url)
            case .failure(let error):
                errorMessage = error.errorDescription
                isConnecting = false
            }
        }
    }

    private func signIn(at url: URL) {
        authenticator.authenticate(
            serverURL: url,
            username:  username,
            password:  password,
            deviceName: "Tetsubi"
        ) { result in
            DispatchQueue.main.async {
                self.isConnecting = false
                switch result {
                case .success:
                    // Success is silent — build the authenticated API and hand
                    // off to the main screen directly, no "connected" screen.
                    if let api = self.authenticator.makeAPI() {
                        self.onConnected(api)
                    } else {
                        // Authentication succeeded but credentials didn't
                        // persist (e.g. stale/empty keychain) — surface it.
                        self.errorMessage =
                            "Signed in, but the session couldn't be started. Please try again."
                    }
                case .failure(let error):
                    self.errorMessage = error.errorDescription
                }
            }
        }
    }
}
