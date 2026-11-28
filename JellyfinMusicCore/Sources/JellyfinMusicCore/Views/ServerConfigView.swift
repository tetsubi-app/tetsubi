// MARK: - ServerConfigView — Jellyfin login UI (Quick Connect first, username/password fallback)

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Login screen for a Jellyfin server.
///
/// **Primary flow — Quick Connect.** The "Use Quick Connect" button starts a
/// Quick Connect session and shows a 6-digit code the user approves on another
/// device (or the server). On success it calls `onConnected` straight to the
/// main screen. Nothing else starts it: each session leaves a pending approval
/// request on the server, so appearing, discovery, picking a server and Return
/// in the address field only fill in the address. The code is dropped when the
/// address changes, on the password form and when the screen goes away.
///
/// **Fallback — username & password.** A "Sign in with username & password"
/// link, and auto-reveal on any Quick Connect failure (server has it disabled,
/// the user rejects, or it times out), swap in the classic credential form.
/// "Use Quick Connect instead" swaps back and asks for a fresh code.
/// The server URL field is shared by both. Below it, servers found on the
/// local network (`ServerDiscovery`) can be tapped instead of typing; if
/// exactly one is found and nothing's typed yet, its address is filled in.
/// The sign-in screen's small rules, pure so they can be tested.
public enum SignInRules {
    /// The line under the Quick Connect panel before a code is asked for.
    public static func idleHint(hasAddress: Bool) -> String {
        hasAddress ? "Tap Use Quick Connect to get a code." : "Choose your server above, or type its address."
    }

    /// The address changed: a Quick Connect started for another address (or still resolving
    /// the typed one) is dropped; the same address it was started for, or none, leaves it be.
    public static func shouldStopQuickConnect(address: String, startedFor: String?) -> Bool {
        address != startedFor
    }
}

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
    /// The address the running Quick Connect was started for (the resolved one).
    @State private var quickConnectAddress: String?

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
            // One centred card (max 440): logo, title, server, the two ways in.
            VStack(spacing: Spacing.l) {
                header

                VStack(alignment: .leading, spacing: Spacing.m) {
                    field("Server address") {
                        TextField(Self.exampleURL, text: $serverURL)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .textContentType(.URL)
                            #endif
                    }
                    discoveryList
                }

                Hairline()

                // The two login modes swap in place.
                if mode == .quickConnect {
                    quickConnectPanel
                } else {
                    manualPanel
                }
            }
            .padding(Spacing.xl)
            .frame(maxWidth: 440)
            .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.panel))
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.xl)
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .animation(.easeInOut(duration: 0.2), value: mode)
        .onAppear {
            // The first time the screen appears: look for servers (no server is contacted but by the search).
            if !started {
                started = true
                quickConnect.onConnected = { onConnected($0) }
                searchNetwork()
            }
        }
        // A code is for one address: editing or picking another drops it.
        .onChange(of: trimmedServerURL) { _, address in
            if SignInRules.shouldStopQuickConnect(address: address, startedFor: quickConnectAddress) {
                stopQuickConnect()
            }
        }
        .onDisappear { stopQuickConnect() }
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
        VStack(spacing: Spacing.s) {
            // The app icon's disc (DiscLogo in each app's asset catalog).
            Image("DiscLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 88, height: 88)
                .accessibilityHidden(true)
            Text("Sign in to Jellyfin")
                .font(TetsubiType.pageTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text("Tetsubi")
                .font(TetsubiType.body)
                .foregroundStyle(TetsubiColors.textSecondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Quick Connect panel

    private var quickConnectPanel: some View {
        VStack(spacing: Spacing.m) {
            Text("Quick Connect")
                .font(TetsubiType.sectionTitle)
                .foregroundStyle(TetsubiColors.textPrimary)
            Text("Approve this login on your Jellyfin server, or in the Jellyfin app on another device you're signed into.")
                .font(TetsubiType.body)
                .foregroundStyle(TetsubiColors.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            if isFindingServer {
                waiting("Finding server…")
            } else {
                switch quickConnect.state {
                case .starting:
                    waiting("Requesting login code…")
                case .pending(let code):
                    VStack(spacing: Spacing.s) {
                        Text(code)
                            .font(.plex(size: 44, weight: .bold))
                            .monospacedDigit()
                            .tracking(4)
                            .foregroundStyle(TetsubiColors.blue)
                            .textSelection(.enabled)
                            .padding(.vertical, Spacing.xs)
                        waiting("Waiting for approval…")
                    }
                case .failed(let message):
                    errorText(message)
                case .idle:
                    Text(SignInRules.idleHint(hasAddress: hasServerURL))
                        .font(TetsubiType.body)
                        .foregroundStyle(TetsubiColors.textTertiary)
                        .multilineTextAlignment(.center)
                    PrimaryButton("Use Quick Connect", enabled: hasServerURL, fullWidth: true) {
                        startQuickConnect()
                    }
                    .accessibilityIdentifier("ServerConfigView.useQuickConnect")
                }
            }

            // Let the user bypass Quick Connect and sign in with credentials.
            ActionChip("Sign in with username & password", color: TetsubiColors.blue) {
                stopQuickConnect()
                mode = .manual
            }
            .padding(.top, Spacing.xs)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Username & password panel

    private var manualPanel: some View {
        VStack(spacing: Spacing.m) {
            Text("Username & password")
                .font(TetsubiType.sectionTitle)
                .foregroundStyle(TetsubiColors.textPrimary)

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

            PrimaryButton(isConnecting ? "Signing in…" : "Sign in",
                          enabled: !password.isEmpty && hasServerURL, working: isConnecting, fullWidth: true) {
                connect()
            }
            .padding(.top, Spacing.xs)

            // Back to Quick Connect, with a fresh code.
            ActionChip("Use Quick Connect instead", color: TetsubiColors.blue) {
                errorMessage = nil
                mode = .quickConnect
                startQuickConnect()
            }
            .disabled(isConnecting)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Servers on the network

    private var discoveryList: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack {
                Text("On your network")
                    .font(TetsubiType.caption)
                    .foregroundStyle(TetsubiColors.textSecondary)
                Spacer()
                if isSearching {
                    ProgressView().controlSize(.small)
                } else {
                    ActionChip("Search again", color: TetsubiColors.blue) { searchNetwork() }
                }
            }

            if discovered.isEmpty {
                Text(isSearching || !hasSearched
                     ? "Looking for servers…"
                     : "No servers found. Type your server's address above.")
                    .font(TetsubiType.body)
                    .foregroundStyle(TetsubiColors.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ForEach(discovered) { server in
                let selected = server.address == trimmedServerURL
                Button {
                    pick(server)
                } label: {
                    HStack(spacing: Spacing.m) {
                        Image(systemName: "server.rack")
                            .foregroundStyle(TetsubiColors.blue)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(server.name)
                                .font(TetsubiType.rowTitle)
                                .foregroundStyle(TetsubiColors.textPrimary)
                            Text(server.address)
                                .font(TetsubiType.caption)
                                .foregroundStyle(TetsubiColors.textSecondary)
                        }
                        Spacer()
                        if selected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(TetsubiColors.blue)
                        }
                    }
                    .padding(Spacing.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(selected ? TetsubiColors.blueTint : TetsubiColors.surface,
                                in: RoundedRectangle(cornerRadius: Radii.card))
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
        // Only fills in the address; changing it drops any code (`onChange`).
        serverURL = server.address
        errorMessage = nil
    }

    private var trimmedServerURL: String {
        serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var hasServerURL: Bool { !trimmedServerURL.isEmpty }

    // MARK: - Building blocks

    /// A labelled text field on a translucent fill.
    private func field<Field: View>(_ label: String, @ViewBuilder _ input: () -> Field) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(label)
                .font(TetsubiType.caption)
                .foregroundStyle(TetsubiColors.textSecondary)
            input()
                .font(TetsubiType.body)
                .textFieldStyle(.plain)
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, 10)
                .background(TetsubiColors.surface, in: RoundedRectangle(cornerRadius: Radii.card))
                .overlay(RoundedRectangle(cornerRadius: Radii.card).strokeBorder(TetsubiColors.hairline))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func waiting(_ text: String) -> some View {
        HStack(spacing: Spacing.s) {
            ProgressView().controlSize(.small)
            Text(text)
                .font(TetsubiType.body)
                .foregroundStyle(TetsubiColors.textSecondary)
        }
    }

    private func errorText(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(TetsubiType.body)
            .foregroundStyle(TetsubiColors.red)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Works out the full address (`ServerAddress`: adds http://, tries
    /// port 8096…), shows it in the field, then asks it for a code.
    private func startQuickConnect() {
        findTask?.cancel()
        quickConnect.reset()
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
                quickConnectAddress = url.absoluteString
                serverURL = url.absoluteString
                quickConnect.start(serverURL: url)
            case .failure(let error):
                mode = .manual
                errorMessage = error.errorDescription
            }
        }
    }

    /// Drops the code and any lookup under way: back to the "Use Quick Connect" button.
    private func stopQuickConnect() {
        findTask?.cancel()
        isFindingServer = false
        quickConnectAddress = nil
        quickConnect.reset()
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
