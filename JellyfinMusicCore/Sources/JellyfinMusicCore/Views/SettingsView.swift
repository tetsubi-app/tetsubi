// MARK: - SettingsView — Maniac Mode, server, downloads, playback, storage and about (docs/redesign.md section 5)

import SwiftUI

/// The Settings categories, in the order of the page. Compact shows them as
/// one grouped list; wide (see `usesSplit`) as a split view, this list on the
/// left and the chosen category on the right. The Equaliser lives inside
/// Playback: a section of it on wide, a pushed page on compact. (Updates is
/// Android and Linux only: iOS and the Mac update through the App Store.)
public enum SettingsCategory: String, CaseIterable, Identifiable, Sendable {
    case maniac = "Maniac Mode"
    case server = "Server"
    case downloads = "Downloads"
    case playback = "Playback"
    case storage = "Storage"
    case about = "About"

    public var id: String { rawValue }
    public var title: String { rawValue }

    /// Downloads shows only where there is a download manager (not signed out).
    public static func available(hasDownloads: Bool) -> [SettingsCategory] {
        allCases.filter { $0 != .downloads || hasDownloads }
    }

    /// The remembered category, or the first one when nothing is stored, it is
    /// not known, or it is not on offer now.
    public static func resolve(_ stored: String?, available: [SettingsCategory]) -> SettingsCategory {
        stored.flatMap(SettingsCategory.init(rawValue:)).flatMap { available.contains($0) ? $0 : nil }
            ?? available.first ?? .maniac
    }

    /// The split view from the same width as a detail page's two panes (expanded,
    /// or medium once the page is 900 wide).
    public static func usesSplit(windowClass: WindowWidthClass, paneWidth: CGFloat) -> Bool {
        DetailLayout.isTwoPane(windowClass: windowClass, paneWidth: paneWidth)
    }

    /// The category list's width in the split view.
    public static let listWidth: CGFloat = 240
    /// The detail's groups stop growing here, so rows stay readable on a big window.
    public static let detailMaxWidth: CGFloat = 720
}

/// Settings wording that depends on the platform. Mobile data exists on iPhone and iPad only
/// (not on the Mac), as on Android and not on Linux in Kotlin's `SettingsText`.
public enum SettingsText {
    /// "Download over mobile data", "Streaming over mobile data" and their footer sentences.
    public static func showsMobileData(isMac: Bool) -> Bool { !isMac }

    /// Where this build runs: false on the Mac.
    public static var showsMobileData: Bool {
        #if os(macOS)
        showsMobileData(isMac: true)
        #else
        showsMobileData(isMac: false)
        #endif
    }

    public static let downloadsFooter = "When off, downloads wait for Wi\u{2011}Fi."
    static let playbackMobileSentence = "On mobile data, tracks stream at the chosen quality (from the next track); Original (as served) streams the files as they are. "
    static let playbackRest = "Normalise volume evens out loud and quiet tracks using Jellyfin's loudness data; it can only turn tracks down, and downloaded tracks play at full volume. Gapless playback is on unless the equaliser or visualiser is in use."

    /// The Playback footer: the mobile-data sentence only where there is mobile data.
    public static func playbackFooter(showsMobileData: Bool) -> String {
        showsMobileData ? playbackMobileSentence + playbackRest : playbackRest
    }
}

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
    @State private var paneWidth: CGFloat = 0
    /// The category the split view shows, remembered.
    @AppStorage("settings.category") private var storedCategory = ""

    @Environment(\.windowWidthClass) private var windowClass

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

    /// The people who test the app, listed under "QA team" in About.
    static let qaTeam = ["Matt Evans", "Eloy Smith Rodriguez", "Adam Buchanan"]
    /// The names on one line, as shown under "QA team".
    static var qaTeamLine: String { qaTeam.joined(separator: " \u{2022} ") }

    /// Copyright line from the app's Info.plist (`NSHumanReadableCopyright`).
    private var copyright: String {
        Bundle.main.infoDictionary?["NSHumanReadableCopyright"] as? String
            ?? "© 2026 DEKIRU Ltd. All rights reserved."
    }

    // MARK: Body

    public var body: some View {
        let available = SettingsCategory.available(hasDownloads: downloadManager != nil)
        let selected = SettingsCategory.resolve(storedCategory, available: available)
        let split = SettingsCategory.usesSplit(windowClass: windowClass, paneWidth: paneWidth)
        VStack(spacing: 0) {
            PageHeader("Settings")
            Group {
                if split {
                    HStack(alignment: .top, spacing: 0) {
                        ScrollView {
                            VStack(spacing: Spacing.xs) {
                                ForEach(available) { category in
                                    categoryRow(category, selected: category == selected)
                                }
                            }
                            .padding(.leading, windowClass.pageMargin)
                            .padding(.top, Spacing.s)
                        }
                        .frame(width: SettingsCategory.listWidth + windowClass.pageMargin)
                        ScrollView {
                            VStack(alignment: .leading, spacing: Spacing.l) { content(selected, split: true) }
                                .frame(maxWidth: SettingsCategory.detailMaxWidth, alignment: .leading)
                                .padding(.top, Spacing.s)
                                .padding(.bottom, Spacing.xxl)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                } else {
                    ScrollView {
                        // Maniac Mode first: it changes how everything plays.
                        VStack(alignment: .leading, spacing: Spacing.l) {
                            ForEach(available) { content($0, split: false) }
                        }
                        .padding(.top, Spacing.s)
                        .padding(.bottom, Spacing.xxl)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { paneWidth = $0 }
        }
        .ownsItsHeader()
        .task { imageCacheBytes = ImageCache.diskUsage() }
        .onAppear { refreshEqualizerSummary() }
    }

    /// A category in the split view's list: blue tint and blue text when selected.
    private func categoryRow(_ category: SettingsCategory, selected: Bool) -> some View {
        Button {
            storedCategory = category.rawValue
        } label: {
            Text(category.title)
                .font(TetsubiType.rowTitle)
                .foregroundStyle(selected ? TetsubiColors.blue : TetsubiColors.textPrimary)
                .lineLimit(1)
                .padding(.horizontal, Spacing.l)
                .frame(maxWidth: .infinity, minHeight: 44.scaled, alignment: .leading)
                .background(selected ? TetsubiColors.blueTint : Color.clear, in: RoundedRectangle(cornerRadius: Radii.card))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private func content(_ category: SettingsCategory, split: Bool) -> some View {
        switch category {
        case .maniac: maniacGroup
        case .server: serverGroup
        case .downloads: downloadsGroup
        case .playback:
            playbackGroup(showsEqualizerRow: !split)
            if split { EqualizerView(embedded: true) }
        case .storage: storageGroup
        case .about: aboutGroup
        }
    }

    // MARK: Groups

    private var maniacGroup: some View {
        SettingsGroup {
            SettingsToggleRow("Maniac Mode", isOn: $maniacMode,
                              caption: "No skips or shuffles, a front to back listen. Tettō tetsubi.",
                              divider: false)
        }
        .onChange(of: maniacMode) { _, on in
            if let player { player.setManiacMode(on) } else { PlaybackSettings.maniacMode = on }
        }
        .accessibilityIdentifier("SettingsView.maniacMode")
    }

    private var serverGroup: some View {
        SettingsGroup(title: "Server") {
            if authenticator.hasStoredCredentials,
               let url = try? authenticator.keychain.loadServerURLString() {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(url).font(TetsubiType.rowTitle.weight(.regular))
                        .foregroundStyle(TetsubiColors.textSecondary).textSelection(.enabled)
                    if let name = authenticator.savedUserName {
                        Text("Signed in as \(name)").font(TetsubiType.body)
                            .foregroundStyle(TetsubiColors.textSecondary)
                    }
                    Text(KeychainManager.useDataProtectionKeychain ? "Saved in the Keychain (Data Protection)" : "Saved in your login keychain")
                        .font(TetsubiType.caption)
                        .foregroundStyle(TetsubiColors.textTertiary)
                }
                .padding(.horizontal, Spacing.l)
                .padding(.vertical, Spacing.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(alignment: .bottom) { Hairline().padding(.leading, Spacing.l) }
                SettingsRow("Sign Out", divider: false, titleColor: TetsubiColors.red, onTap: onSignOut) {
                    Image(systemName: "arrow.right.circle")
                        .font(.system(size: 18.scaled))
                        .foregroundStyle(TetsubiColors.red)
                }
                .accessibilityIdentifier("SettingsView.signOut")
            } else {
                SettingsRow("No server configured.", divider: false) { EmptyView() }
            }
        }
    }

    /// Format picker when a download manager is available.
    @ViewBuilder
    private var downloadsGroup: some View {
        if let mgr = downloadManager {
            SettingsGroup(title: "Downloads", footer: SettingsText.showsMobileData ? SettingsText.downloadsFooter : nil) {
                SettingsMenuRow("Default format",
                                selection: Binding<AudioFormat?>(get: { mgr.defaultFormat }, set: { mgr.defaultFormat = $0 }),
                                options: [(AudioFormat?.none, "Original (as served)")]
                                    + AudioFormat.allCases.filter(\.playsOnApple).map { (AudioFormat?.some($0), $0.displayName) })
                if SettingsText.showsMobileData {
                    SettingsToggleRow("Download over mobile data", isOn: $downloadOverCellular)
                        .onChange(of: downloadOverCellular) { _, on in
                            PlaybackSettings.downloadOverCellular = on
                            // Running and waiting songs follow the new choice now.
                            mgr.mobileDataSettingChanged()
                        }
                }
                SettingsRow("Disk usage", divider: false) {
                    SettingsValue(DownloadPaths.formatBytes(mgr.totalDiskUsage)).monospacedDigit()
                }
            }
        }
    }

    private func playbackGroup(showsEqualizerRow: Bool) -> some View {
        SettingsGroup(
            title: "Playback",
            footer: SettingsText.playbackFooter(showsMobileData: SettingsText.showsMobileData)
        ) {
            if SettingsText.showsMobileData {
                SettingsMenuRow("Streaming over mobile data", selection: $cellularQuality,
                                options: StreamingQuality.allCases.map { ($0, $0.displayName) })
                    .onChange(of: cellularQuality) { _, q in PlaybackSettings.cellularQuality = q }
            }
            if showsEqualizerRow {
                NavigationLink {
                    EqualizerView().appTheme()
                } label: {
                    SettingsRow("Equaliser") {
                        HStack(spacing: Spacing.xs) {
                            SettingsValue(equalizerSummary)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12.scaled, weight: .semibold))
                                .foregroundStyle(TetsubiColors.textTertiary)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("SettingsView.equaliser")
            }
            SettingsToggleRow("Normalise volume", isOn: $normalizeVolume, divider: false)
                .onChange(of: normalizeVolume) { _, on in
                    PlaybackSettings.normalizeVolume = on
                    player?.applyNormalization()
                }
        }
    }

    /// Refresh after returning from the equaliser screen.
    private func refreshEqualizerSummary() {
        equalizerSummary = AudioEffects.shared.equalizerEnabled
            ? PlaybackSettings.equalizerPreset.displayName : "Off"
    }

    private var storageGroup: some View {
        SettingsGroup(title: "Storage") {
            SettingsRow("Album art cache") {
                SettingsValue(ByteCountFormatter.string(fromByteCount: Int64(imageCacheBytes), countStyle: .file))
            }
            SettingsRow("Clear image cache", divider: false, titleColor: TetsubiColors.red, onTap: {
                ImageCache.clear()
                imageCacheBytes = ImageCache.diskUsage()
            }) { EmptyView() }
        }
    }

    private var aboutGroup: some View {
        // The QA team, then the copyright and credits.
        // "Made with AI": Jellyfin's community rules ask apps
        // built mainly with an AI to say so clearly.
        SettingsGroup(
            title: "About",
            footerText: Text("QA team").fontWeight(.semibold)
                + Text("\n" + Self.qaTeamLine + "\n\n")
                + Text("""
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
        ) {
            HStack(spacing: 14) {
                Image("DiscLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 52.scaled, height: 52.scaled)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Tetsubi").font(TetsubiType.sectionTitle).foregroundStyle(TetsubiColors.textPrimary)
                    Text("A music player for Jellyfin")
                        .font(TetsubiType.body)
                        .foregroundStyle(TetsubiColors.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.l)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .bottom) { Hairline().padding(.leading, Spacing.l) }
            .accessibilityElement(children: .combine)
            SettingsRow("Version") { SettingsValue(appVersion) }
            SettingsRow("Artist information", divider: false) { SettingsValue("MusicBrainz, Wikipedia") }
        }
    }
}
