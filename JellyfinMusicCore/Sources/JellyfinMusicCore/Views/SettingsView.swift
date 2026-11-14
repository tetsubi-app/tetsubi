// MARK: - SettingsView — server config, playback, and download settings

import SwiftUI

public struct SettingsView: View {

    /// Called when the user taps "Sign Out".
    public let onSignOut: () -> Void

    /// Optional download manager — enables the format picker and disk usage.
    public let downloadManager: DownloadManager?

    private let authenticator = Authenticator()

    /// Optional player — re-applies volume normalisation when it's toggled.
    public let player: PlayerManager?

    @State private var imageCacheBytes = 0
    @State private var cellularQuality = PlaybackSettings.cellularQuality
    @State private var normalizeVolume = PlaybackSettings.normalizeVolume
    @State private var maniacMode = PlaybackSettings.maniacMode
    @State private var downloadOverCellular = PlaybackSettings.downloadOverCellular
    @State private var equalizerSummary = ""

    public init(onSignOut: @escaping () -> Void = {}, downloadManager: DownloadManager? = nil,
                player: PlayerManager? = nil) {
        self.onSignOut = onSignOut
        self.downloadManager = downloadManager
        self.player = player
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String
        return build.map { "\(version) (\($0))" } ?? version
    }

    /// Copyright line from the app's Info.plist (`NSHumanReadableCopyright`).
    private var copyright: String {
        Bundle.main.infoDictionary?["NSHumanReadableCopyright"] as? String
            ?? "© 2026 DEKIRU Ltd. All rights reserved."
    }

    /// Group (row) background — matches `DownloadsView`.
    private static let groupBackground = Color.appGroupBackground

    public var body: some View {
        Form {
            // Maniac Mode first: it changes how everything plays.
            Section {
                Toggle(isOn: $maniacMode) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Maniac Mode")
                        Text("A front-to-back listen. No skips.")
                            .font(.plex(.caption))
                            .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: maniacMode) { _, on in
                    if let player { player.setManiacMode(on) } else { PlaybackSettings.maniacMode = on }
                }
                .accessibilityIdentifier("SettingsView.maniacMode")
            }
            .listRowBackground(Self.groupBackground)

            Section("Server") {
                if authenticator.hasStoredCredentials,
                   let url = try? authenticator.keychain.loadServerURLString() {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(url).font(.plex(.body))
                            .foregroundStyle(.secondary).textSelection(.enabled)
                        if let name = authenticator.savedUserName {
                            Text("Signed in as \(name)").font(.plex(.subheadline))
                                .foregroundStyle(.secondary)
                        }
                        Text(KeychainManager.useDataProtectionKeychain ? "Saved in the Keychain (Data Protection)" : "Saved in your login keychain").font(.plex(.caption))
                            .foregroundStyle(.tertiary)
                    }
                    Button(role: .destructive) {
                        onSignOut()
                    } label: {
                        HStack {
                            Image(systemName: "arrow.right.circle")
                            Text("Sign Out")
                            Spacer()
                        }
                    }
                } else {
                    Text("No server configured.")
                        .foregroundStyle(.secondary)
                }
            }
            .listRowBackground(Self.groupBackground)

            // Format picker when a download manager is available.
            if let mgr = downloadManager {
                Section("Downloads") {
                    HStack {
                        Text("Default format")
                        Spacer()
                        Picker(
                            "",
                            selection: Binding<AudioFormat?>(
                                get: { mgr.defaultFormat },
                                set: { mgr.defaultFormat = $0 }
                            )
                        ) {
                            Text("Original (as served)")
                                .tag(AudioFormat?.none)
                            ForEach(AudioFormat.allCases, id: \.self) { fmt in
                                Text(fmt.displayName)
                                    .tag(AudioFormat?.some(fmt))
                            }
                        }
                        .labelsHidden()
                    }
                    Toggle("Download over mobile data", isOn: $downloadOverCellular)
                        .onChange(of: downloadOverCellular) { _, on in
                            PlaybackSettings.downloadOverCellular = on
                        }
                    HStack {
                        Text("Disk usage")
                        Spacer()
                        Text(DownloadPaths.formatBytes(mgr.totalDiskUsage))
                            .font(.plex(.subheadline).monospacedDigit())
                    }
                }
                .listRowBackground(Self.groupBackground)
            }

            Section {
                Picker("Streaming over mobile data", selection: $cellularQuality) {
                    ForEach(StreamingQuality.allCases, id: \.self) { q in
                        Text(q.displayName).tag(q)
                    }
                }
                .onChange(of: cellularQuality) { _, q in PlaybackSettings.cellularQuality = q }
                NavigationLink {
                    EqualizerView().appTheme()
                } label: {
                    LabeledContent("Equaliser", value: equalizerSummary)
                }
                // Refresh after returning from the equaliser screen.
                .onAppear {
                    equalizerSummary = AudioEffects.shared.equalizerEnabled
                        ? PlaybackSettings.equalizerPreset.displayName : "Off"
                }
                Toggle("Normalise volume", isOn: $normalizeVolume)
                    .onChange(of: normalizeVolume) { _, on in
                        PlaybackSettings.normalizeVolume = on
                        player?.applyNormalization()
                    }
            } header: {
                Text("Playback")
            } footer: {
                Text("On mobile data, tracks stream at the chosen quality (from the next track); Original (as served) streams the files as they are. Normalise volume evens out loud and quiet tracks using Jellyfin's loudness data; it can only turn tracks down, and downloaded tracks play at full volume. Gapless playback is on unless the equaliser or visualiser is in use.")
            }
            .listRowBackground(Self.groupBackground)

            Section("Storage") {
                LabeledContent("Album art cache",
                               value: ByteCountFormatter.string(fromByteCount: Int64(imageCacheBytes), countStyle: .file))
                Button("Clear image cache", role: .destructive) {
                    ImageCache.clear()
                    imageCacheBytes = ImageCache.diskUsage()
                }
            }
            .listRowBackground(Self.groupBackground)

            Section {
                HStack(spacing: 14.scaled) {
                    Image("DiscLogo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 52.scaled, height: 52.scaled)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Tetsubi").font(.plex(.title3).bold())
                        Text("A music player for Jellyfin")
                            .font(.plex(.subheadline))
                            .foregroundStyle(Color.albumArtistText)
                    }
                }
                .padding(.vertical, 4)
                .accessibilityElement(children: .combine)
                LabeledContent("Version", value: appVersion)
                LabeledContent("Artist information", value: "MusicBrainz, Wikipedia")
            } header: {
                Text("About")
            } footer: {
                // "Made with AI": Jellyfin's community rules ask apps
                // built mainly with an AI to say so clearly.
                Text("""
                \(copyright)
                Made with AI.

                Tetsubi isn't made by, or affiliated with, the Jellyfin project. \
                Jellyfin is free software from jellyfin.org.

                Artist facts come from MusicBrainz and Wikidata (CC0). Artist \
                introductions come from Wikipedia and are available under the \
                Creative Commons Attribution-ShareAlike 4.0 licence; each album \
                page links to its source.

                DEKIRU Sans — based on IBM Plex Sans by IBM Corp., SIL Open \
                Font License 1.1.
                """)
            }
            .listRowBackground(Self.groupBackground)
        }
        // Grouped boxes on every platform. The Mac's default form style is
        // two loose columns hard against the window edge (and under the
        // sidebar); grouped matches the iPhone and System Settings.
        .formStyle(.grouped)
        // Same look as the Downloads list: navy page, near-black groups.
        .scrollContentBackground(.hidden)
        .navigationTitle("Settings")
        .task { imageCacheBytes = ImageCache.diskUsage() }
    }
}
