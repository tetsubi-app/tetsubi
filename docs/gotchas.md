# Gotchas

This file collects the technical lessons learned while building Tetsubi, a Jellyfin music player for iOS, Mac, Android and Linux. Each item is something a developer (or an AI coding session) would probably get wrong, or lose hours on, without being told.

Items marked "unverified" were built and unit-tested but had not been checked on a real device or server when this was written.

## Apple signing, IDs and distribution

**The Xcode project is generated, so every setting must live in `project.yml`.** `Tetsubi.xcodeproj` is produced by `xcodegen generate` and is not committed. Anything set by hand in Xcode (signing team, signing style, version numbers) is wiped by the next generate; the Mac build once failed for want of a profile for exactly this reason.

**`bundleIdPrefix`, `developmentLanguage` and `deploymentTarget` belong under `options:` in `project.yml`.** Placed under `settings:` they are silently ignored, and the app then required the newest OS instead of iOS 17 and macOS 14.

**New source and test files are not in the Xcode targets until you run `xcodegen generate` again.** `swift test` in `JellyfinMusicCore/` picks up new test files straight away, but Xcode builds do not see them until the project is regenerated.

**Bundle IDs and App Group IDs are unique across all Apple teams.** An ID that any other team has registered, including your own free personal team, is refused for a paid team with "not available" or "cannot be registered to your development team". Choose final IDs before requesting entitlements or creating the App Store Connect record, because both are tied to the bundle ID.

**A new bundle ID is a new app, and a new App Group is a new container.** Nothing is migrated: the old install stays on the device beside the new one until deleted by hand, and the login and downloads kept in the old group are not carried over. The simulator's sign-in has to be redone as well.

**App Group IDs must start with `group.`.** The group is `group.gg.dekiru.musicplayer.shared` and appears in both iOS entitlements files, the widget's entitlements, `DownloadPaths`, `KeychainManager` and `WidgetShared.appGroup`; change them together.

**iOS entitlements files must not hold Info.plist keys or macOS-only keys.** Usage-description keys such as `NS*UsageDescription` in an entitlements file break signing; they belong in the Info.plist properties in `project.yml`.

**The Mac app must not have the App Group entitlement.** With it, SwiftData's default store went to the group container, macOS denied access (Cocoa error 513) and the signed Mac app crashed at launch. The Mac has nothing to share, so `DownloadPaths.rootDir` uses the group container on iOS only and `KeychainManager` uses no access group on macOS.

**Command-line signing needs an App Store Connect API key when Xcode has no Apple account.** Without one, `xcodebuild -allowProvisioningUpdates` fails with "No Accounts". Keep the key at `~/.appstoreconnect/private_keys/AuthKey_<key ID>.p8` (never committed) and add `-allowProvisioningUpdates -authenticationKeyPath … -authenticationKeyID <key ID> -authenticationKeyIssuerID <issuer ID>` to the `build`, `archive` and `-exportArchive` commands.

**Automatic signing registers App IDs and the App Group for you.** Signing a new target for the first time (for example the widget) registers its ID. Builds that do not need signing can use `CODE_SIGNING_ALLOWED=NO`; simulator builds and unsigned Mac builds are unaffected by team changes. From the command line with only an API key this did not work for new IDs that use an App Group: `xcodebuild` reported "Authentication failed" until the group existed. The App IDs can be registered through the App Store Connect API, but the App Group has to be created and assigned in the developer portal, or by opening the project once in Xcode with an account signed in.

**The Mac target may need its profile created in Xcode once.** Command-line signing failed with no profile for the Mac bundle ID until the project was opened in Xcode and the target's Signing & Capabilities pane was visited. Signing the Mac app with the paid team had not been tried when this was written.

**Uploading to TestFlight is `archive` followed by `-exportArchive` with an options plist.** The plist uses `method` `app-store-connect`, `destination` `upload`, `teamID` and `signingStyle` `automatic`.

**iPad builds must allow all four orientations.** The first upload was rejected until `UISupportedInterfaceOrientations~ipad` in `project.yml` included upside-down portrait; the iPhone list is unchanged.

**The build number must rise before every upload, and the widget must carry the same version and build as the app.** `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` are set once at the top of `project.yml` and both the iOS app and `Tetsubi-Widget` read them; the Kotlin side's `tetsubi.versionName` and `tetsubi.versionCode` in `Kotlin/gradle.properties` are kept in step by hand.

**An over-the-air install of a development build fails on an iPhone.** Development builds carry `get-task-allow`, so the phone reports "Unable to install" even with a valid profile that lists the device. Over-the-air installs need an Ad Hoc build signed with an Apple Distribution certificate; otherwise run from Xcode over USB, install with `devicectl`, or use TestFlight.

**The CarPlay entitlement is granted to the developer account, then switched on for each App ID.** Apple's request form (developer.apple.com/contact/carplay) does not ask for a bundle ID. Once approved, tick "CarPlay Audio App" on the identifier, under the Capability Requests tab (it is not in the main Capabilities list, and the App Store Connect API cannot set it); a new bundle ID needs the tick again. Before that, signing fails with "Entitlement com.apple.developer.carplay-audio not found and could not be included in profile". Check the result with `codesign -d --entitlements` on the signed app.

**Simulator builds use a separate entitlements file.** `Tetsubi-iOS-CarPlay.entitlements` is applied only for `sdk=iphonesimulator*` and also holds `com.apple.developer.networking.multicast`, which Apple has not granted; putting that key in the device entitlements would stop device builds signing. Server discovery does not need it (see the discovery sweep below).

**Plain HTTP is allowed on purpose and App Review needs the reason.** `NSAllowsArbitraryLoads` is true on iOS and macOS because Jellyfin home servers are often `http://`; the review note is "users connect to their own Jellyfin media servers, which often don't use HTTPS". `NSLocalNetworkUsageDescription` is set on both platforms.

**`ITSAppUsesNonExemptEncryption` is NO because only OS encryption is used.** That covers URLSession HTTPS, the Keychain and a CryptoKit SHA-256 that only names cache files. Revisit it if a crypto library or custom encryption is ever added.

**Privacy manifest reasons depend on which APIs are used.** `PrivacyInfo.xcprivacy` declares UserDefaults reason `CA92.1` (the standard store only; switch to `1C8F.1` if a `suiteName` store is added) and file-timestamp reason `C617.1`. Re-check the reasons when adding disk-space, boot-time or keyboard APIs.

**Jellyfin's LLM policy applies to this project.** Community projects built mainly with an LLM are asked to say so and to respect licences, which is why Settings, About says how the app was made. Posts in Jellyfin's own community (issues, pull requests, forum) must be in the developer's own words.

## iOS and Mac behaviour

### Audio and playback

**Stream the original file whenever AVPlayer plays it.** `StreamURLBuilder.plan(codec:container:)` decides from the track's codec and container (a restored queue keeps both in `QueueTrack`): MP3, AAC and ALAC in the MP4 family (m4a, mp4, m4b; also ffprobe's "mov,mp4,m4a,…" list), ADTS AAC, FLAC, WAV and AIFF (PCM) play as `stream.<the file's own extension>?static=true`. Everything else (Opus, Vorbis, WMA, APE, WavPack, DSD, a playable codec in a container AVFoundation doesn't read such as FLAC in Ogg, unknown) is converted. Until 2026-11 the app asked for ALAC and direct-played only when the file's container equalled ALAC's, so every MP3, FLAC and AAC track went through a plain HTTP transcode that AVPlayer refused: an all-MP3 library (the App Review demo server) played nothing. Android does the same through `StreamUrls` and `PlayerEngine.directPlayFormats`.

**A converted stream for AVPlayer must be HLS.** A plain HTTP transcode from `/Audio/{id}/universal` (`TranscodingProtocol=http`) comes back chunked, with no length and no byte ranges, and AVPlayer refuses it (AVFoundation -11850, CoreMedia -12939). Conversions therefore ask for `TranscodingProtocol=hls` with `TranscodingContainer=mp4` (fMP4 segments): a lossy source as AAC at 320 kbps, a lossless or unknown one as ALAC (Apple's own lossless HLS codec; FLAC in fMP4 HLS played too). AAC, ALAC and FLAC HLS were checked playing in AVPlayer against Jellyfin 12.4 in the iOS simulator in 2026-11, over the internet.

**Give the server exactly one reason to convert.** `/universal` passes its reasons on in the variant playlist's URL (`TranscodeReasons=`). One reason plays; several, written with a comma and a space ("ContainerNotSupported, VideoCodecNotSupported, AudioCodecNotSupported", what asking "m4a" of an MP3 gives), make AVPlayer's request for the variant playlist get HTTP 400 (CoreMedia -16845, shown as NSURLError -1008), though URLSession fetches the same URL fine. So `Container=` is the source's own container restricted to the target codec (`mp3|aac`, `ogg|alac`, `mov|aac,mp4|aac,…`), leaving only `AudioCodecNotSupported`. The mobile-data cap works the same way (AAC at the cap via `AudioBitRate`, no `MaxStreamingBitrate`), except for an AAC source, which asks for its plain container with `MaxStreamingBitrate` so the only reason is the bitrate (under the cap the server sends the file). The old mobile URL (`Container=m4a` for everything) only ever worked for m4a sources: it failed for MP3 and FLAC. A server-side bitrate limit for remote clients could still add a second reason; not seen yet.

**The equaliser and visualiser do not run on HLS streams.** The audio tap in `AudioEffects` needs the asset's audio track, and an HLS asset has none, so on the converted mobile-data stream the effects are silently skipped (converted streams of formats AVPlayer can't play, and everything on mobile data with the cap). They work for original streams and downloads.

**The playback error message ends with the player's own reason.** `PlayerManager.failureDetail` adds the error codes, any HTTP status, the kind of stream (original, converted, HLS, file) and the kind of network, for example "(AVFoundation -11850 / -12939, converted, mobile data)". Ask for that text when someone reports a track that will not play.

**Any `MTAudioProcessingTap` on an `AVQueuePlayer` item breaks gapless playback.** Measured on a direct-play ALAC album: no tap gave 0 ms between tracks, the app's tap 262 ms, and even a do-nothing tap 262 ms; setting the mix before queueing does not help, while an audio mix without a tap is fine. `AudioEffects.attach(to:)` therefore only registers items and adds the tap while the equaliser is on or a visualiser is showing.

**Equaliser and gapless together would need a different player.** The real fix is an `AVAudioEngine` player with sample-accurate scheduling and `AVAudioUnitEQ`, which is a large rewrite. Android does not have this limit (see the Android section).

**The tap's `process` callback must never allocate or block.** Settings reach it through `OSAllocatedUnfairLock.withLockIfAvailable` (skipped when busy) and filters are updated in place. `TapContext` handles float32 non-interleaved audio only and passes anything else through.

**AirPlay bypasses audio taps.** The equaliser and visualiser do nothing while audio goes over AirPlay.

**`AVURLAsset` cannot carry an `Authorization` header.** Stream URLs therefore put the token in the query string, and it must be spelled `ApiKey` (see the Jellyfin section). Images do use the header, through `AuthenticatedImage` and `JellyfinAPI.imageURLRequest`, because `AsyncImage(url:)` cannot add one either.

**`AVURLAsset.preload()` is iOS-only.** The next track is warmed up with `loadTracks(withMediaType: .audio)`, which exists on both iOS and macOS.

**Set up the audio session once.** `AudioSession.configureForPlayback()` used to run again on every route change and interruption end, re-activating the session mid-playback and installing another set of observers each time; the lock-screen player kept disappearing. Now route changes only act on `.oldDeviceUnavailable` (pause), an interruption's start pauses and remembers, and its end resumes only if the app was playing and iOS says `.shouldResume`. The fix was unverified on a device.

**Handle `togglePlayPauseCommand`, not just play and pause.** The keyboard play/pause key and headphone clicks send the toggle command; with no handler they do nothing.

**Use the real `MPNowPlayingInfo` keys.** Made-up keys such as "IsPlaying" are ignored by the system. Set `MPNowPlayingInfoPropertyElapsedPlaybackTime` and `MPNowPlayingInfoPropertyPlaybackRate` (1 or 0) on every state change and on each new track.

**macOS routes media keys only to the app whose `playbackState` is `.playing`.** `NowPlayingController` must set `MPNowPlayingInfoCenter.playbackState` to `.playing`, `.paused` or `.stopped` on the Mac; `PlayerManager.isPlaying` pushes every change.

**`AVQueuePlayer` moves on by itself when an item fails.** Each queued item needs `AVPlayerItemFailedToPlayToEndTime` and `status == .failed` observers, and only the playing item's failure should count; `PlayerManager.trackFailed()` then pauses, ends the server report and sets an error the UI shows. Without this, Now Playing claimed to be playing nothing.

**Keep NotificationCenter tokens for per-item observers and remove them.** Discarding the token left an observer behind for every queued track. Tokens live in `itemEndObservers` and `itemFailureObservers` and are removed when items leave the queue.

**Local files have no server runtime.** Durations of downloaded tracks are read with `AVURLAsset.load(.duration)`; the seek bar divides by the duration, so a zero freezes it. Format and bitrate of a download are likewise read from the file (`CMFormatDescriptionGetMediaSubType`, `estimatedDataRate`), not taken from whatever default started playback.

**A converted stream that gets no answer is asked for again after 8 s.** AVPlayer has no timeout of its own to set: an HLS request that goes nowhere waits for the system's request timeout (about a minute) and then fails the item. `PlayerManager.watchStart` therefore makes the playing converted item (`/universal`) again, from the same point, when 8 s after Play it is neither ready nor has logged any access (`accessLog()` empty: no response at all); up to three times, then AVPlayer is left to it (a slow server, not a dead request). Direct streams and files are left to AVPlayer. Unverified on a device.

**Guard late lock-screen artwork by track.** `NowPlayingController.update(track:artworkRequest:)` swaps in the real cover when it arrives and checks the title so a late image cannot land on the wrong track. Offline, it reads the album's saved cover (`DownloadPaths.savedCover`) before trying the cache or the network.

**Skip controls must be disabled in the remote command centre too.** In Maniac Mode `NowPlayingController.setSkipControlsEnabled(false)` disables next, previous, skip and scrub for the lock screen, headphones and CarPlay, as well as hiding the in-app buttons.

**Volume normalisation uses the server's `NormalizationGain`.** The player volume is `10^(gain/20)` capped at 1, and the field must be requested in the track list's `Fields`. Downloaded files carry no gain on iOS and play at full volume.

**The system Now Playing controls replaced a custom Live Activity.** A custom Dynamic Island activity looked poor beside iOS's own player and was removed; the lock screen, Control Centre and Dynamic Island are all fed by `NowPlayingController`.

**Give play/pause buttons a fixed frame.** The pause and play symbols differ in size, so a button sized by its symbol shifts everything beside and below it when the symbol swaps.

### Downloads

**URLSession deletes the temporary file when `didFinishDownloadingTo` returns.** Move the file synchronously inside the delegate callback before hopping to the main actor; otherwise the album is marked complete with no files on disk.

**Use one background `URLSession` per process.** `DownloadSessionDelegate.shared` owns it with the identifier `<bundle id>.downloads` and falls back to a default session when there is no bundle ID, as under `swift test`. The iOS `AppDelegate` must forward `handleEventsForBackgroundURLSession` to `DownloadManager.handleBackgroundEvents`.

**Each task must carry everything needed to finish without the app's state.** `taskDescription` holds a JSON `DownloadTaskInfo`, so the delegate can move a file to its final path after a background relaunch when no `DownloadManager` exists yet. On init the manager re-adopts running tasks (`restoreInFlightDownloads`). Non-2xx responses are not saved.

**A force-quit cancels background tasks and delivers resume data on the next launch.** iOS marks these with `NSURLErrorBackgroundTaskCancelledReasonKey`; a cancel with no reason key is the user's own Cancel and must not be retried. Interrupted tracks retry up to `maxRetries`, continuing from resume data when there is some. This was unverified on a device.

**Resuming needs Range support.** Direct-play URLs (`stream.<ext>?static=true`) should support it; transcoded `/universal` streams restart from zero.

**Downloads are always a file AVPlayer plays.** `AudioFormat.downloadFormat` keeps "Original (as served)" for MP3, AAC, ALAC, FLAC and WAV, and `StreamURLBuilder.downloadURL` fetches the original file when it already is the chosen format in a container the saved extension describes (MP4-family files are saved as .m4a). Opus is saved in Ogg, which AVFoundation doesn't read, so an Opus original (or the old Opus preference, no longer offered on iOS and Mac) downloads as FLAC, as Android does for formats its player can't decode; AIFF and FLAC-in-Ogg are converted to WAV and FLAC. Downloads convert over plain HTTP, which URLSession takes. Opus files downloaded before this are not found and replaced (Android does that).

**Keep paused-download state outside `DownloadPaths.rootDir`.** Any folder with files under the root counts as a downloaded album, so resume points live in `Application Support/PausedDownloads/`. `restorePausedDownloads` and `restoreFailedDownloads` must both run before `refreshDownloaded`.

**Ignore late cancel notices.** The delegate's `onCancel` passes the task ID and is ignored unless it matches the current task; otherwise a late notice after Pause then Resume untracks the new task. Similarly, `cancelledAlbums` makes a track that finishes just after Cancel get deleted on arrival.

**Downloaded state comes from the disk, with exceptions for work in progress.** `DownloadManager.refreshDownloaded()` rebuilds the set of completed albums from folders, but an album still downloading, paused or failed must not count as downloaded even though its folder has files. Cancel deletes the album's files for the same reason.

**A track with no stream URL must fail the album.** Otherwise the album waits forever. Failed albums stay listed with Retry, which downloads only the tracks missing on disk; `DownloadPaths.trackExists` ignores the extension in case the default format changed.

**Track numbers are not unique within an album.** Albums tagged with disc numbers repeat track numbers per disc, so files use `JellyfinTrack.fileNumber` (disc two, track one is 201). Untagged albums can have every track numbered zero, so `JellyfinTrack.fileNumbers(for:)` renumbers in play order when the numbers are not all different. Saved titles keyed by number collided in both cases before these fixes.

**Per-request settings control mobile data.** "Download over mobile data" (default off; a saved choice is kept) is applied with `allowsExpensiveNetworkAccess` on each request; the background session then holds the task until Wi‑Fi, and the Downloads tab and the album and playlist pages show "Waiting for Wi‑Fi" (`DownloadNetworkRule`). A request can't be changed in place and resume data keeps the old request, so switching the setting makes tasks with the other choice again from the start of the song (`mobileDataSettingChanged`). A task that ends with `networkUnavailableReason` expensive or constrained is made again without counting as a retry. Android does the same through `DownloadManager.mayDownload` and a WorkManager unmetered constraint; the desktop never reports a metered network and hides the switch. Pausing downloads in Low Power Mode is not implemented.

**Tests must never touch the real downloads folder.** Set `DownloadPaths.rootDirOverride` to a temporary directory; the real root is the App Group container and tests delete their root on teardown.

**Avoid `@Relationship` cycles in SwiftData.** `DownloadTask` has no relationship to `DownloadedAlbum`; query by an `albumID` predicate instead.

### Widget, Siri and App Intents

**The widget must not link the player.** It depends only on the small `TetsubiWidgetData` library; the app's `WidgetSnapshotWriter` writes `Widget/nowplaying.json` and an art JPEG into the App Group (throttled to once a second) and then reloads the timeline.

**Widget buttons are `AudioPlaybackIntent`s that run in the app.** `Tetsubi-Widget/PlaybackIntents.swift` is therefore compiled into both the widget and the app target (see `project.yml`). With the app killed, Play replays the album the widget shows.

**A widget cannot tick by itself.** While paused, the "Ends" time keeps up through minute-by-minute timeline entries for an hour; if the app is killed while playing, the widget shows Play and only the duration once `playingUntil` has passed.

**On an iPhone the medium widget's times take two lines.** On one line ("Duration: …   Ends: …") the finish time was cut off; `WidgetDisplay.MediumStyle.phone` also steps the type down so five lines and the buttons fit the iPhone SE's 321 × 148 pt widget. The iPad keeps one line.

**The widget bundles its own copies of the fonts.** The extension lists the four DEKIRU Sans files as resources and under `UIAppFonts`, because it does not load the package's font registration.

**Use App Intents for Siri, not SiriKit.** `INPlayMediaIntent` needs the Siri entitlement and an Intents extension; `AudioPlaybackIntent`s in `Tetsubi-iOS/Siri/PlayAlbumIntents.swift` start music without opening the app.

**Siri only knows the names of suggested entities.** `updateAppShortcutParameters()` runs at launch with recently played and favourite albums; any other album goes through the "Which album?" search. Album and playlist phrases have the same shape and Siri may confuse them (unverified).

**Siri phrases need `CFBundleDisplayName`.** Without it the home-screen label and the spoken app name were the build's target name. `INAlternativeAppNames` adds "Jellyfin" as a second name.

**Intents and CarPlay can launch the app with no phone UI.** `AppSession.shared` owns the API client, player and download manager for this reason; they must not be `@State` in a view.

### CarPlay

**CarPlay audio apps cannot use `CPSearchTemplate`.** There is no search tab. Lists are capped at `CPListTemplate.maximumItemCount`.

**The CarPlay scene must be declared and multiple scenes enabled.** `CPTemplateApplicationSceneSessionRoleApplication` is in the scene manifest in `project.yml` and `UIApplicationSupportsMultipleScenes` is true. Scene delegate class names use `$(PRODUCT_MODULE_NAME)`, so renaming the target needs no change there.

**Test CarPlay in the simulator, where it is offered.** In the older Simulator app it is under I/O, External Displays, CarPlay. Xcode 27 replaces that app with Device Hub, whose Device menu has a CarPlay Simulator item; on the development Mac it was disabled for iOS 26 and iOS 27 simulators and for a real iPhone, for a reason not yet found. CarPlay has since been used in a real car.

**CarPlay's light or dark appearance is the car's choice, not the phone's.** The templates are drawn by the system and follow CarPlay's own Appearance setting (Automatic or Always Dark), whatever the iPhone is set to. `UIUserInterfaceStyle: Dark` is set for the whole iOS app in `project.yml`; whether CarPlay's templates follow it is unverified.

**CarPlay's Now Playing screen cannot show extra text.** `CPNowPlayingTemplate` shows only the title, artist, album and artwork from `MPNowPlayingInfoCenter`. Anything else, such as the album's duration or finish time, would have to be folded into one of those fields, which also changes the lock screen.

**The queue is Now Playing's "Up Next" button, titled "Queue".** `CarPlayQueueScreen` sets `upNextTitle`, enables the button only while something follows the playing track, and pushes a `CPListTemplate` built by `CarPlayQueueList` (pure, tested): the same entries as the phone's queue screen (after the playing track, play order, no Repeat All wrap), a section per album or playlist with "Album · Artist" as the header and the group's time as the header subtitle (the iOS 15 section initialiser), rows with "artist · 3:45" as detail (artist only when not the album's); shuffled, one "Shuffled" section with "artist · album". `maximumItemCount` counts items across all sections, and `maximumSectionCount` sections; past either, the last shown row is "…and N more". It follows the queue through `withObservationTracking` while CarPlay is connected and stops redrawing once the list is popped. A tap skips to the entry and pops back to Now Playing; in Maniac Mode the rows have no handler and a first section holds the read-only note. CarPlay list items have no secondary action (no long press, swipe or context menu), so CarPlay has no Play Next / Add to Queue: Play there replaces the queue.

### SwiftUI layout and navigation

**Never wrap the root view in a `NavigationStack`.** An extra stack around `AppRootView` made every tab's own stack lose its bar, including titles, toolbars and the search field.

**Declare the album `navigationDestination` on every tab's stack.** A stack without `AlbumDestination` broke album links elsewhere after that tab had been visited.

**`NavigationStack` paints an opaque background over an outer theme.** Apply `.appTheme()` to the content inside each stack and to pushed destinations. The sign-in screen dropped its stack entirely so the background picture shows.

**Use `.searchable` for search fields.** A `TextField` in `ToolbarItem(.principal)` could not be tapped to focus on iOS.

**List rows need `.listRowBackground(Color.clear)`.** `.scrollContentBackground(.hidden)` alone still leaves black rows; one list (Artists) had to become a `ScrollView` with a `LazyVStack` because the `List` kept its black background.

**Do not replace a scrolling list with a spinner on reload.** It resets the scroll position. Show the spinner on the first load only, and replace data only when it has changed.

**Do not put the A to Z strip beside a `LazyVGrid` in an `HStack`.** It squeezed the grid to one column. Overlay the strip and reserve `AlphaIndexBar.width` of trailing padding.

**Present Now Playing from one sheet at the app root.** `PlayerManager.isNowPlayingPresented` drives a single `.sheet` in `AppRootView`. There is one mini-player bar per tab on iOS before 26.1, so a sheet on the bar would mean several sheets competing. On iPad it is a full-screen cover instead (`NowPlayingPresentation`): an iPad sheet is a form about 580 pt wide, under the 640 pt the two columns need, so they never showed there. It is closed with its chevron button (no swipe down). iPhone, even a Max on its side (regular width), and the Mac keep the sheet.

**`tabViewBottomAccessory(isEnabled:)` needs iOS 26.1.** Guard it with `#available(iOS 26.1, *)`; older systems and macOS use a `safeAreaInset` capsule on each tab.

**Draw full-bleed backgrounds as a clipped overlay.** An aspect-filled image placed directly in the layout widens it; `AppBackgroundView` draws on `Color.clear.overlay { … }.clipped()`.

**`TimelineView(.animation)` runs at full refresh rate forever.** One loading outline left on screen kept an idle Mac at about 40% CPU. `LoadingOutline` is limited to 30 frames a second and stops after `animationLimit`.

**Static stored properties are not allowed in generic types.** When `AuthenticatedImage` became generic, its decoder had to move to a file-level function.

**Decode and downsample images off the main thread and cap the cache by cost.** `AuthenticatedImage` downsamples with ImageIO and `ImageDataCache` has a `totalCostLimit`; a count limit alone could let 200 large covers reach about 1 GB.

**A bare Space keyboard shortcut blocks typing spaces.** The Mac's Play/Pause menu item uses Command-P because a Space shortcut would stop spaces being typed in Search.

**Some modifiers are iOS-only in shared views.** `navigationBarTitleDisplayMode` and `toolbarBackground` for the navigation bar need `#if os(iOS)` so `JellyfinMusicCore` compiles for macOS.

**`UILaunchScreen` allows only a colour and one centred image.** The launch screen is a storyboard (`Tetsubi-iOS/LaunchScreen.storyboard`) so the background picture can fill the screen.

**A very large launch image shows black.** The full-size background was too big for iOS to draw at launch, so the storyboard uses a smaller `LaunchPicture`. iOS caches the launch render even across uninstalls; restart the simulator or phone to see a change.

### Mac specifics

**macOS ignores `dynamicTypeSize` for custom fonts and `@ScaledMetric`.** `Font.custom(relativeTo:)` does not scale on the Mac, so `Views/UIScale.swift` provides an app-wide factor measured from the window size (1.0 to 1.6) that `Font.plex` and the main fixed sizes multiply by. iOS always uses 1.0.

**Use the login keychain on the Mac, not the data protection keychain.** The data protection keychain needs a provisioning profile on macOS; without one every save failed with -34018, so the login and device ID were never kept, each request used a new device ID and Quick Connect polling failed. `KeychainManager` sets `useDataProtectionKeychain` to false on macOS.

**Mac keychain items need `kSecAttrService`.** Items are labelled with the bundle ID; unlabelled items collided with the test runner's (-25299) and with any other program using the same account names.

**On iOS, fall back to the app's own keychain when the shared access group fails.** `KeychainManager` tries the shared group first and falls back when unentitled (-34018), logging if both fail.

**The sandboxed Mac app needs `com.apple.security.network.server` to receive UDP replies.** Without it server discovery's replies were denied (`deny network-inbound` in the log).

**Use a single `Window`, not a `WindowGroup`.** A second window would mean a second player. The app quits when its window closes, because playback belongs to the window.

**Mac `List`s ignore `contentMargins`.** `readableWidthOnMac()` narrows the list itself to a centred column. `SettingsView` and `EqualizerView` use `.formStyle(.grouped)` because the Mac's default form style is loose columns against the window edge.

**The Mac route picker needs the player.** `AVRoutePickerView` on macOS must be given `PlayerManager.outputPlayer` to route this app's audio.

**The first network lookup from a new Mac binary can fail.** The macOS local network privacy check can make the very first address lookup fail.

**A sheet on the Mac keeps a narrow default width unless it is told to fit its content.** The Now Playing sheet stayed about 470 pt wide whatever `.frame(idealWidth:)` said. Adding `.presentationSizing(.fitted)` (macOS 15 and later) makes the sheet take the ideal size; `NowPlayingView.nowPlayingSheetFrame()` does this, opening wide only when the main window has room (`macSheetIdealWidth`, `macSheetIdealHeight`, fed by `UIScale.windowWidth` and `windowHeight`).

**Now Playing has two columns when it is wide.** From 640 pt wide and 520 pt tall (before the Mac's window scaling) the cover, title, progress and buttons go on the left and "Up next" on the right (`NowPlayingView.isWide`). The cover is sized from the height that is left (`wideArtSize`), so the buttons are not cropped. The height rule keeps an iPhone on its side on one column. An unfolded iPhone Duo is about 670 pt wide by my estimate and is meant to get two columns; the simulated Duo started folded and could not be unfolded, so only the iPad was checked on screen (before the iPad's Now Playing became a full-screen cover; the cover has not been checked on screen yet).

**Space plays and pauses on the Mac through a key monitor, not a menu shortcut.** A bare Space menu shortcut would also take the spaces typed in Search and the playlist name field. `SpaceKeyMonitor` watches `keyDown` and acts only when no text field or focused button has the keyboard (`SpaceKeyRule`, which is tested). ⌘P in the Playback menu still works.

**Interactive glass makes a Mac button bar appear to move.** `.glassEffect(.regular.interactive())` squashes the whole capsule under the pointer on every click. The Mac's playback bar uses plain `.regular`. Its progress line also keeps its space when the track length is unknown, so the bar never changes height while skipping.

**The Mac playback bar is limited to Settings' content width** (`readableWidthOnMac`, 720 pt, growing with the window) and has a 16 pt gap under it; the window has no safe area to provide one.

**Mac builds can be installed by copying the app to Applications, but a running copy may refuse to quit** (an open sheet can cancel the quit request). Quit it by hand or end the process first, then relaunch; the signed-in session lives in the Keychain and is kept.

## Listening queue

Both platforms, same rules; iOS is the spec. The pure parts are `PlayQueue`, `PlayQueueStore`, `PlayQueueController` and `QueueEditor` (Swift `JellyfinMusicCore/Audio/`, Kotlin `playback/`), all unit-tested.

**The model.** A queue is a list of entries in queue order plus a play order (the same while shuffle is off). Each entry has its own entry id (the same track can be queued twice), its track's metadata, and the group it was queued from (album, playlist, or single songs: id, kind, title, artist, art). Entries added by one replace, Play Next or Add to Queue share a batch id; a run of consecutive entries from one batch is a "display group" (an album header in the queue screen). Play Next goes after the current group, not after the current track (straight after the current track while shuffled). Shuffle covers the whole queue and turning it off restores the queue order, current track kept. Repeat All loops the whole queue.

**The saved file.** `PlayQueue/queue.json` in the app's own storage (Application Support on Apple), versioned JSON with the server and user it belongs to and each track's codec. Saved on every change (debounced), every 10 s while playing, on pause and on going to the background; restored paused at sign-in only for the same server and user; removed on sign-out. A restored queue opens no stream until Play (see "Starting a track" below). A bad file is repaired or ignored. No stream URLs or file paths are saved (they change between launches): streams and downloads are found again by track id. Entries that can't play on restore (offline and not downloaded) are skipped.

**Engines sync by entry id, never by index.** Indexes shift after every edit. The players (AVQueuePlayer; Media3 and mpv) are told only what changed: a new current entry loads (`load`), anything else replaces what follows the playing item (`replaceAfterCurrent`), so the preloaded next track survives and albums join gaplessly. Converted-stream offsets, reconnects, Cast and the lock-screen/Auto queue all key on entry ids.

**Cast gets a window.** A queue of up to 26 entries goes to the receiver whole; a longer one as 5 behind, the playing one and 20 ahead, moved along as it plays (`CastWindow`). The receiver's own Repeat All would only go round the window, so the app drives the wrap.

**Maniac Mode.** The queue still plays on into the next album and adding is allowed (Play Next, Add to Queue, Play replacing the queue); moving, removing, clearing, skipping, shuffle and repeat are refused by the controller, not just hidden (`PlayQueue.canEdit`). Turning it on switches shuffle and repeat off. The queue screen is read-only with the note "Maniac Mode: the queue plays in order."

**The Play prompt.** Pressing Play or Shuffle on an album or playlist, or tapping a song in one, asks Replace queue / Play Next / Add to Queue / Cancel only when an upcoming entry belongs to another batch (`PlayQueue.needsPlayPrompt`); otherwise Play replaces as it always did. From a tapped song, Play Next and Add to Queue queue just that song; from Shuffle they leave the shuffle setting alone. "Now playing" on the album or playlist that's already playing just opens Now Playing. CarPlay, Android Auto, Siri/Assistant and the widgets never ask: they call the player directly and replace.

**The queue screen** (Now Playing's list) shows the playing entry pinned at the top, then what follows it in play order without the Repeat All wrap; played entries aren't shown. `QueueEditor` turns list drags into `move(entryIds:toIndex:)`: positions count from after the current entry, and a dragged album header moves its whole group and snaps to a group boundary. On Apple, Now Playing's single column is one `List` (not a `ScrollView`), so rows can be dragged (long press on iOS) and swiped; any button added to its top row needs an explicit button style, or a tap anywhere in the row triggers it.

**Starting a track: nothing opens before Play, a spinner until sound, no accidental pause.** Just after launch the route to the server (a VPN such as Tailscale, Wi‑Fi coming up) may not carry the very first request: a restored converted stream was opened at launch, its request never reached the server and the phone waited out the 30 s read timeout, while Play presses toggled play/pause with nothing to show for it. Now a queue loaded paused (a restored one, or one that ends or loses its playing entry while paused) is held without opening anything: Media3 has the items unprepared (`PlayerEngine.cue`; the session is set to show its notification for an idle player, so the lock screen still shows the track), mpv has them appended but no file started, and `PlayerManager` makes no `AVPlayerItem` (`deferredLoad`). Now Playing, the bar, the widget and the lock screen show the track and point from the queue. The first Play (a skip, or Play from the notification, car or widget too) opens it there: a converted stream as the stream started at that point (`StartTimeTicks`), a direct stream or file with a seek; a seek before Play just moves the held point. Nothing is reported to the server until Play. While the track is loading after Play, a skip, a seek or a reconnect (Media3 buffering with play-when-ready or a reconnect waiting; mpv's `core-idle` while unpaused; AVPlayer `.waitingToPlayAtSpecifiedRate`, or a restored item waiting to reach its point) the controller's `isStarting` is true and the Play/Pause button in Now Playing, the bar and the Android widget shows a small spinner in the same space. Presses (`togglePlayPause`: the app's buttons, the widget, the Mac's space bar, headphone toggles on Apple) are ignored only in the first 10 s since Play, a skip or a seek (`ignoresPlayPause`), so an impatient press can't pause what's about to play. After 10 s of starting, or while a stream that had already played reconnects (Android's reconnect wait) or a start has been retried (the iOS watchdog), a press pauses and ends the tries; Play then carries on from the held point. A stall long after Play isn't a start, so presses pause then too. An explicit Pause (the system notification, which Media3 shows as Pause while buffering, or the lock screen's Pause on Apple) always pauses.

**Queue additions apply in tap order.** Queueing a streamed album fetches its tracks first, so two quick Play Next / Add to Queue taps used to land in the order their fetches finished. Each add now takes a turn when tapped (`QueueAddOrder` in `PlayerManager`); when its tracks arrive it waits until every earlier turn has been placed or dropped. A failed fetch drops its turn and shows its notice at once, without holding up later adds. Adds that need no fetch (downloads, playlists, songs) take a turn too, so they don't jump ahead of a pending album. Play (replacing the queue) is a clean start: it drops every outstanding add without a notice (their tracks are ignored when they arrive, a failure shows nothing) and takes a turn itself, so adds tapped after it wait for the replace and land in the new queue. A later Play also supersedes an earlier one whose tracks haven't arrived.

## Jellyfin server and API

Checked against Jellyfin 12.4.0. Verify any new endpoint against a live server rather than from memory: Jellyfin answers plausible-looking but wrong paths with a quiet 404 or 401.

### Authentication

**Every request needs the `Authorization: MediaBrowser` header.** The value is `MediaBrowser Client="...", Device="...", DeviceId="...", Version="..."`, without `Token=` for the first sign-in calls and with it afterwards. `X-Emby-Token` alone is legacy and gave HTTP 400 on sign-in.

**The header must be plain ASCII.** A device name with a curly apostrophe produced HTTP 400 "Error processing request" on every call. `JellyfinAPI.headerSafe` (and the Kotlin `headerSafe`) straightens quotes, drops accents and removes emoji, double quotes and backslashes.

**The user ID is in `SessionInfo.UserId`, not at the top level.** Use `AuthenticationResponse.resolvedUserId`. An empty user ID makes every browse call hit `Users//Items` and return 404.

**Clear the saved login after changing auth or decoding code.** Old credentials persist in the simulator's keychain, and a stale empty user ID looks like a server fault.

**Restore the saved session at launch without a network call.** A failed re-authentication used to bounce the user to the sign-in screen; `Authenticator.makeAPI()` now rebuilds the client from saved values only.

**Quick Connect is three calls.** `POST /QuickConnect/Initiate` (no body, header without a token) returns `Code` and `Secret`; poll `GET /QuickConnect/Connect?secret=…` until `Authenticated` is true (404 means the secret expired); then `POST /Users/AuthenticateWithQuickConnect` with `{"Secret": …}` returns the token and user. Only a 401 means Quick Connect is switched off on the server.

**Give Quick Connect five minutes.** Polling runs every two seconds for 150 attempts on both apps; two minutes kept running out while switching devices to approve.

**Invalidate throwaway URLSessions.** Sign-in builds a temporary API client per request, every two seconds while Quick Connect polls; `JellyfinAPI.deinit` calls `finishTasksAndInvalidate()` so sessions do not pile up.

**Query-string auth must be spelled `ApiKey`.** On `/Audio/{id}/universal`, both `api_key` and `X-Emby-Token` return 401. On image requests, `api_key` is treated as a server-level API key rather than a user token and fails silently.

### Browsing

**Most item lists use an envelope with capitalised keys.** `/Items` responses are `{ "Items": […], "TotalRecordCount": n }`.

**`Items/Latest` returns a bare JSON array.** Decoding it as the envelope throws, and the Recently Added row stays silently empty.

**`Items/Latest` is recently added, not the whole library.** Use a full album query for anything that needs every album.

**`Items/Resume` only returns partly played items.** Recently Played queries audio items with `SortBy=DatePlayed` and groups them into albums.

**Test "has been played" with `UserData.PlayCount`.** A track played to completion has a play count of 1 while `DatePlayed` and `IsPlayed` can be nil. `Fields` must include `UserData`.

**De-duplicate Recently Played by album ID as well as by name and artist.** A split album whose tracks carry different artists appeared twice with the same ID, which crashed Android's Home row (duplicate list key).

**Items of type `Audio` used as albums carry a track ID.** `JellyfinAlbum.init(from:)` resolves them to `AlbumId` and the `Album` name.

**`ParentId` of a non-folder item is ignored.** `/Items?ParentId=<trackId>` returns the entire library, so always make sure an album ID really is a `MusicAlbum` ID. The track list also drops any track whose `AlbumId` is not the requested album.

**The track list is `GET /Items?ParentId={albumId}&UserId=…&IncludeItemTypes=Audio`.** Sort by `ParentIndexNumber,IndexNumber,SortName`. The path `/Users/{id}/Items/{albumId}/Items` does not exist.

**Tracks send `MediaSources` (an array), and there is no top-level codec list.** Keep the first entry and derive the codec, sample rate and bit depth from its audio entry in `MediaStreams`. Bitrate is `Bitrate` on the source, else `BitRate` on the audio stream.

**`PremiereDate` is a fractional-second ISO string.** A value such as `1994-10-31T00:00:00.0000000Z` defeats `ISO8601DateFormatter` with `.withInternetDateTime`. Slice the leading `yyyy-MM-dd` and parse that, and filter out year-one junk dates.

**The server cannot match "this month and day in any year".** On This Day fetches every album's ID, name and release date only (`EnableImages=false`, `EnableUserData=false`, `EnableTotalRecordCount=false`), matches on the device, then fetches just the matches in full.

**Album `SearchTerm` matches titles only.** Searching an artist's name finds no albums, so `AlbumSearch` also calls `/Artists/AlbumArtists?searchTerm=` and adds those artists' albums.

**Jellyfin has no "date favourited".** `FavoritesStore` records favourite times locally to order newest first; favourites made elsewhere follow in server order. Favourites are set with `POST` or `DELETE /UserFavoriteItems/{id}?userId=` and listed with `Filters=IsFavorite`.

**Untagged files come back with filename-style titles.** A title such as `01 - Artist - Song` is cleaned for display by `TrackTitle`.

**Multi-disc albums come in two forms.** Some libraries store each disc as a separate album named "… (Disc 1)" or "- CD 2", which `DiscGrouping.merge` shows as one entry; others are one album whose tracks carry `ParentIndexNumber`, which `DiscGrouping.byDiscNumber` splits under disc headings. Search results are not merged.

**Every track has its own `ImageTags.Primary`, even when it only shows the album cover.** To tell whether a track really has its own picture, compare blur hashes (`Fields=ImageBlurHashes` and `AlbumPrimaryImageTag`); `JellyfinTrack.hasOwnArt` is true only when they differ.

**The client and device names sent in the header appear on the server.** They show under Dashboard, Devices, and a fresh sign-in registers under the new name.

### Images and streams

**The image endpoint has no user prefix.** It is `/Items/{id}/Images/Primary`; the `/Users/{userId}/…` variant returns 404.

**Direct play is `/Audio/{id}/stream.<ext>?static=true`.** Use the file's own extension (its container), not the one of a format you would like. The older `/Users/{id}/Items/{id}/Stream` path returns 404.

**Transcoding is `/Audio/{id}/universal` and needs a full set of parameters.** `UserId`, `DeviceId`, `Container`, `TranscodingContainer` and `TranscodingProtocol` (plus `AudioCodec`) are required; without them it returns 401. The mobile-data cap adds `AudioBitRate` (and `MaxStreamingBitrate` for an AAC source); see "Give the server exactly one reason to convert".

**Report playback or play counts never change.** `POST /Sessions/Playing` and `/Sessions/Playing/Stopped` with `ItemId` and `PositionTicks`; without them Recently Played goes stale.

**Append to the server's base path.** A server behind a proxy at a sub-path such as `https://example.com/jellyfin` must keep that path on every API call, image and stream. The Swift client once replaced it; the fix was not checked against a real proxied server.

### Playlists

**`PlaylistItemId` is the track's ID.** A song listed twice in a playlist cannot be told apart by the Move and Remove calls. Edits therefore send the whole new order with `POST Playlists/{id}` and `{Ids}`, one call at a time, and reload on failure; rows are keyed by position.

**New playlists are created private.** Playlist downloads are stored by track ID with a manifest and reuse files from downloaded albums, fetching them again if that album is deleted.

### Server discovery and addresses

**Discovery is a UDP message to port 7359.** Send "Who is JellyfinServer?"; the reply is JSON with `Address`, `Id` and `Name`.

**Broadcast alone misses servers in Docker.** Docker bridge networking forwards a published UDP port only for packets addressed to the host, never broadcasts. `ServerDiscovery` therefore also sweeps: it sends the message directly to every address on each private network (cut to a /22 around the device). The sweep needs no Apple multicast entitlement.

**macOS refuses to send to 255.255.255.255 with several interfaces up.** Send to each interface's own broadcast address as well.

**A Docker server replies with its container address.** When the reply's address is a bare IP that is not the sender's, `preferredAddress` swaps in the sender's IP; hostnames are kept.

**Stop the receive loop on a real socket error.** A failed `recvfrom` that was not a timeout once spun about four million times in 2.5 seconds.

**Be forgiving about typed addresses.** `ServerAddress.candidates(for:)` adds `http://` and tries Jellyfin's ports (8096, or 8920 for https); `resolve` accepts the first candidate whose `/System/Info/Public` looks like Jellyfin.

### Artist information

**MusicBrainz has rules and no biographies.** Requests need an identifying User-Agent, at least a second between them, and one retry on 503. The summary text comes from Wikipedia through the artist's Wikidata link, and the Wikipedia attribution (CC BY-SA) is required.

**Do not remember "not found" after a failed request.** `ArtistInfoClient` caches results for 30 days but remembers a miss only for the current run, and never when the request itself failed.

## Android

### Build and release

**AGP 9 changes the plugin set-up.** `:shared` uses the `com.android.kotlin.multiplatform.library` plugin with `kotlin { android { … } }`; it needs `androidResources { enable = true }` for Compose resources and `withHostTest {}` so `commonTest` also runs as Android host tests. `:androidApp` has no `kotlin-android` plugin because AGP 9 compiles Kotlin itself.

**List Compose libraries by coordinates.** The Compose plugin's `compose.runtime`-style shortcuts are deprecated.

**Code shared by Android and desktop goes in `jvmSharedMain`.** This source set (a `jvmShared` group in the default hierarchy template) holds the OkHttp engine, `java.text.Normalizer`, UUIDs and UDP sockets.

**`URLEncoder.encode(String, Charset)` needs API 33.** It crashed album pages on Android 8 to 12; use the form that takes the charset's name. It was found on an old emulator, not by the unit tests.

**minSdk is 27 because Android 8.0 has no FLAC decoder.** On an Android 8.0 emulator FLAC and ALAC played silently, so the minimum was raised to Android 8.1.

**R8 needs keep rules for classes it cannot see being used.** `Kotlin/androidApp/proguard-rules.pro` keeps `CastOptionsProvider` (named only in the manifest), Glance `ActionCallback` classes (created from their class names) and the app's kotlinx.serialization serializers. Libraries ship their own rules.

**Cast and MediaRouter bring in an old Fragment.** Release lint flags it because permission requests need Fragment 1.3 or later; a newer Fragment is pinned in the version catalog.

**The release signing key lives outside the repo.** `Kotlin/androidApp/build.gradle.kts` looks for `~/.android-keys/tetsubi-release.jks` (alias `tetsubi`) and reads the password from the macOS Keychain item "Tetsubi Android release key" (account `tetsubi-release`) or from `TETSUBI_KEYSTORE_PASSWORD`. Without them a release build is left unsigned. A lost key means no more updates, so back up both the file and the password.

**The Kotlin version lives in one place: `Kotlin/gradle.properties`.** `tetsubi.versionName` and `tetsubi.versionCode` feed the Android `versionName`/`versionCode`, the desktop `packageVersion` (`<name>.<code>`, e.g. 1.0.5, because jpackage wants three numbers) and the shared code: `:shared:generateAppVersion` writes `AppVersion.NAME`/`BUILD` into `shared/build/generated`, which `AppInfo.VERSION` (the server header, user agents) and `AppInfo.BUILD` read. Before this, `AppInfo.VERSION` said 0.1.0 while the APK said 1.0. `AppVersionTest` checks the generated values against the file.

### Update check and GitHub releases

**Android and Linux look for a newer release on GitHub; iOS and the Mac do not, on purpose.** The Apple builds are updated through the App Store. When the signed-in app opens (once per run, at most once in 24 hours; any HTTP answer counts as a look, no answer does not) it asks `https://api.github.com/repos/tetsubi-app/tetsubi/releases/latest`, sending only `Accept: application/vnd.github+json` and `User-Agent: Tetsubi/<name>.<code>`. If `tag_name` is newer than the running `<name>.<code>`, Home shows a banner with Download (opens `html_url` in the browser, only if it is an http(s) address, else the releases page), What's new (the release notes) and Skip this version. Nothing is downloaded and failures are silent (logged). Settings ▸ Updates has the switch, Check now (ignores the switch and the 24 hours, and clears a skip) and its result. Code: `shared/.../data/update/UpdateCheck.kt`; settings keys `checkForUpdates`, `skippedUpdateVersion`, `lastUpdateCheck`.

**Release tags are `v<versionName>.<versionCode>`, e.g. `v1.0.6`.** Tags are compared number by number after dropping the `v` and anything after the first character that is not a digit or a dot (`v1.1-beta` is 1.1); a missing part counts as 0. Raise `tetsubi.versionCode` before tagging, or people on the previous build are not told.

**A Google Play build must switch the check off.** Play updates its own installs, and its policies restrict apps that update outside it. Set `UpdateCheck.ENABLED_IN_THIS_BUILD` to false for such a build: no request is made, no banner shows and the Settings rows are hidden.

**Android can't seek in a FLAC written on the fly, so downloaded FLACs are repaired.** A track the server converts (`/universal`, e.g. ALAC, which Android can't decode, downloaded under "Original (as served)") is written by ffmpeg to a pipe: STREAMINFO's total samples is 0 and there is no seek table. Media3's FlacExtractor then treats the file as unseekable and a seek restarts it at 0:00. `downloads/FlacRepair.kt` counts the samples (frame headers checked by CRC-8, CRC-16 and frame numbers) and writes the 36-bit total before the `.part` is renamed; a one-time background pass (`downloads.flacTotalsRepaired`) repairs older downloads. Streams the server converts are seeked by restarting them (`StartTimeTicks`), which needs the track's duration.

**A GitHub release needs the files attached.** Attach the signed release APK (`./gradlew :androidApp:assembleRelease`, see the signing item below) to the release; the banner only links to the release page. Linux packages (AppImage, deb, rpm) must be built on Linux, as jpackage only builds for the system it runs on: the `Linux release` GitHub workflow (`.github/workflows/linux-release.yml`) does it on Ubuntu 22.04 when a `v*` tag is pushed (create the release first, or the upload fails; it can be rerun by hand with the tag), starts the app once under a virtual display, and attaches the `.deb`, `.rpm`, `.AppImage` and `SHA256SUMS-linux.txt`. Built on a newer, rolling distro the packages would carry that system's newer libraries and might not start on Ubuntu or Debian.

**The desktop release build (ProGuard) needs a JDK from 17 to 25 that includes `jmods`.** ProGuard reads the Java library from the JDK's `jmods` folder, which JDK 24 and later builds may leave out (Temurin 25 and Android Studio's bundled JDK have none: every class, even `java.lang.Object`, comes out "can't find"), and ProGuard 7.8 can't read Java 26+ classes ("Unsupported version number [71.0]"). CI uses Temurin 21. ProGuard is currently switched off for the desktop (`desktopApp/build.gradle.kts`): the shrunk 1.0.6 Linux packages couldn't reach the server, so turn it back on only after testing on Linux. The desktop code is compiled for Java 17 whatever JDK builds it (`jvmTarget` in `shared` and `desktopApp`), so the JDK only changes what runs the build. `desktopApp/proguard-rules.pro` lists OkHttp's optional TLS and native-image classes, whose absence otherwise stops ProGuard.

**The Android build only asks the macOS Keychain on a Mac that has the release key.** `androidApp/build.gradle.kts` runs while the project is configured, so a `security` call on Linux or CI would stop every build, the desktop ones included.

**A debug install must be removed before installing a release build.** The signatures differ, and uninstalling wipes the app's downloads.

**Set `allowBackup="false"`.** The login is encrypted with a key in the Android Keystore, which cannot move to another device. `AndroidSecretStore` uses an AES-256-GCM Keystore key with a private SharedPreferences file because EncryptedSharedPreferences is deprecated.

**Declare `configChanges` on the activity.** Without it, rotating the device restarts the activity and drops the Quick Connect code.

**`usesCleartextTraffic="true"` is deliberate.** Home servers are often plain HTTP, as on iOS.

### Playback

**Some devices have no ALAC decoder, and Media3 plays on silently.** The Xiaomi test tablet decodes FLAC but not ALAC. `StreamUrls` streams the original file only when the player can decode it (`PlayerEngine.directPlayFormats`) and otherwise asks the server for FLAC through `/universal`. Bundling an FFmpeg ALAC decoder was rejected because it would mean GPL-3.0 or a custom native build.

**Downloads need the same rule.** "Original" downloads kept the server's ALAC and played silently, so a track the device cannot play is downloaded as FLAC, and older downloads in such formats are found on start and downloaded again.

**ExoPlayer cannot seek in a stream the server converts on the fly.** Tapping the seek bar restarted the track, and because such a stream counts as live, Media3 hid the position and the seek and previous actions from the notification.

**Seek converted streams by restarting the conversion.** `Media3Engine.seekable` is a `ForwardingPlayer` that swaps in the same stream with `StartTimeTicks`, adds the starting point back to the reported position, reports the track's known duration, marks it seekable and not live, and adds the seek commands. Original-file streams are unchanged.

**A dropped connection sent a converted stream back to 0:00.** A converted stream (ALAC, or anything on mobile data with the cap) has no length and no seek map, so when the connection drops (moving between LTE and Wi‑Fi) ExoPlayer's `ProgressiveMediaPeriod` treats it as live and loads it again from its start once the buffer runs out, with no error. `Media3Engine`'s `ConvertedStreamErrorPolicy` gives `/universal` loads no retries, so the error surfaces (after the buffered audio has played); for a network error (`PlaybackException` 2000–2002) the engine swaps in the stream started where playback got to (`StartTimeTicks`, as a seek does) and prepares again, keeping play/pause, without telling the controller. Tries: at once, then 1, 2 and 4 s, then every 10 s, and at once when `NetworkMonitor` reports a network; after two minutes "Playback failed" is shown and Play resumes from the held point. The rules are `StreamReconnect` in `commonMain`. A converted stream's first response is limited to 8 s (`StreamTimeouts`, connect 8 s too): the HTTP source has one read timeout for the response and every later read, so `FirstResponseDataSource` opens converted streams on a helper thread and gives up after 8 s with a connection timeout (2002), which this reconnect then tries again at once from the same point; reads after the open keep the 30 s timeout, and the abandoned request is closed when it ends by itself. Direct streams and downloads keep Media3's own retries (they resume by byte range). On Linux, mpv sets FFmpeg's `reconnect=1` but not `reconnect_streamed`, so a converted stream that drops plays out its cache and ends early (the next track starts) rather than restarting; Linux only converts files whose format the app doesn't recognise, so that is left as it is.

**Android's player streams over the app's own OkHttp client.** `TetsubiOkHttp.client` (`jvmSharedMain`, `data/api/Jvm.kt`) is the one `OkHttpClient`: Ktor's engine is built on it (`preconfigured`), and `Media3Engine` streams through Media3's `OkHttpDataSource` over clients derived from it with `newBuilder()` (`StreamHttpClients`: direct 15 s connect / 30 s read, converted 8 s connect / 30 s read plus `FirstResponseDataSource`'s 8 s first-response limit), so streams share the API's connection pool, DNS and proxy settings. The reason: on a phone the API calls reached the server at once, but the first stream over Media3's `DefaultHttpDataSource` (its own `HttpURLConnection` stack, a fresh connection) reached the server about 8 s after it was sent, together with the 8 s-limit retry; the iPhone had no delay. The stream clients have their own `Dispatcher` (no per-host limit) so a stream never queues behind API calls, artwork or abandoned converted-stream requests. Errors still come out as Media3's `HttpDataSourceException` (2000–2002 for network failures), so the reconnect is unchanged. Never build a second `OkHttpClient`; derive from this one. Cast is unaffected (the receiver fetches its own URLs).

**Slow track starts are logged: `adb logcat -s Tetsubi`.** Every fresh load of a track's audio (a new queue, the queue moving on, a skip, a seek, a reconnect, Play after an error, the first Play of a restored queue) logs one line when sound starts, for example `stream start: item=<id> kind=converted reason=skip response=412 firstAudio=880 sound=1034`. The times are ms from when the app asked for the item: `response` is the stream's connection opening (Media3 `TransferListener.onTransferStart`, also for downloaded files), `firstAudio` the item's first audio reaching the decoder (`AnalyticsListener.onAudioInputFormatChanged`) and `sound` the audio output starting to move (`onAudioPositionAdvancing`, or `onIsPlayingChanged` if that doesn't follow within a second). `buffered` means it happened before the ask (the next track loaded ahead; gapless moves log `reason=next … sound=0`), `-` that it didn't happen (a seek inside audio already loaded), `sound=paused` that it was ready while paused. With no sound after 10 s a `stream waiting:` line says how far it got and the player's state, again every 10 s; `stream failed:` ends a run with the error. A large `response` is the server or network, a large gap from `response` to `firstAudio` the server converting, a large gap after `firstAudio` the player buffering. Only the track id and the kind of stream are logged. The bookkeeping is `StreamStartLog` in `commonMain`, fed by `Media3Engine`; iOS, Mac and Linux have no equivalent.

**One ExoPlayer holds the whole queue.** That gives gapless playback and preloading of the next track. Streams carry the `Authorization` header; `ApiKey=` is added to the URL only for Cast.

**Build the equaliser and visualiser as an ExoPlayer `AudioProcessor`.** `TetsubiAudioProcessor` sits in the audio sink instead of using `android.media.audiofx`, so no microphone permission is needed and gapless stays on. The maths is in `commonMain` under `playback/effects/`.

**Media-control glyphs render as emoji on Android.** Characters such as the previous, pause and next symbols show as colour emoji, so the controls are drawn icons.

**An exception inside `async` can cancel the caller before the error is shown.** A failing track fetch inside `async` cancelled the load before its message appeared; this was found by a test.

**Album art for palette work must be a software bitmap.** `loadReadableArt` loads the picture through Coil as a readable bitmap so pixels can be sampled off the main thread.

**Coil sends the `Authorization` header, so image URLs carry no token.** The image loader uses the app's Ktor client.

### Media session, Android Auto and Cast

**In `onConnect`, leave the system's notification controller alone.** `AutoCallback.onConnect` once rebuilt the connection result for that controller too; the platform session lost its actions, and media keys and notification buttons did nothing.

**In `onConnect`, give the app's own controllers every player command.** The widget's play/pause button did nothing until they were; the player's own rules (such as Maniac Mode) still apply.

**Hide skip and seek with a `ForwardingPlayer`.** In Maniac Mode the session is given a forwarding player without those commands, which removes them from the notification, lock screen and headset buttons.

**The car cannot send the login header.** Android Auto loads artwork through a small content provider (`ArtworkProvider`). Every play from Auto, voice or the widget goes through `PlayerController`.

**Drive Cast with `RemoteCastPlayer`, not the self-switching `CastPlayer`.** `CastPlayer` hides the track-change events that converted-stream seeking needs. Cast uses the Default Media Receiver and streams with `ApiKey=`.

**A Cast device must be able to reach the server's address itself.** It needs the server's LAN address; a VPN-only name will not work. Maniac Mode cannot block skips from a TV remote or Google Home. Casting, Android Auto and voice were unverified on real hardware.

**Media playback and downloads need their own foreground service types.** `PlaybackService` is declared `mediaPlayback` with the matching permissions; downloads run as WorkManager foreground work of type `dataSync`, and `POST_NOTIFICATIONS` is requested once on the first download (Android 13 and later).

### Widgets

**Use `SizeMode.Exact` in Glance.** `SizeMode.Responsive` gave the nearest preset size, so the art came out tiny; `WidgetLayout` sizes everything from the real size.

**On a phone the medium widget's times take two lines, with smaller text.** `WidgetLayout.isPhone` decides from the widget's width and the device's smallest width (both under 600 dp), because the Pad's 4×2 is only 512 dp wide.

**HyperOS draws widgets smaller than it reports.** The Xiaomi launcher reports the real widget size (225 dp for a 2 by 2 cell on the test tablet) but draws widgets about 22% smaller.

**Write the widget snapshot only on real changes.** `WidgetFeed` writes `filesDir/widget/nowplaying.json` and the art at most once a second. A stale snapshot (process gone) shows Play and only the duration; with no queue, Play replays the shown album through the Android Auto play path.

### Compose UI

**Leave `lineHeight` unspecified in the Material `Typography`.** Material's defaults (24 sp for body text at any size) add space between lines; unspecified, lines use the font's own height and match iOS.

**Lazy lists crash on duplicate keys.** The error is `Key "…" was already used`. `AlbumRow` skips repeated IDs so bad data cannot crash a row.

**Hide the tab bar and mini player while the keyboard is up.** In landscape they left only a thin strip for search results.

**An adaptive grid can leave ragged last rows.** The Random Albums page uses a fixed column count in a centred block instead.

**Japanese text falls back to the system font.** DEKIRU Sans has no CJK glyphs; the fallback looks fine, and bundling a CJK face was rejected because of its size (about 5 MB a weight).

### Devices

**Xiaomi Pad (HyperOS) summary.** No ALAC decoder (see Playback); widgets drawn about 22% smaller than reported (see Widgets).

**The shake gesture is an accelerometer threshold.** `ShakeEffect` fires above 2.5 g at most once a second, only while the screen is composed, and returns false on desktop.

## Linux desktop

**The desktop app shares the Kotlin code but not every feature.** Playback is libmpv (`MpvEngine`, below); the equaliser, visualiser, shake, Cast and output switcher are hidden or do nothing, `NetworkMonitor` always reports online, and reading format details from a downloaded file returns nothing.

**Storage names carry the app ID, never the app's name.** Android's SharedPreferences files (`secrets`, `settings`, `app`), the Keystore alias (`secrets`), the WorkManager work name (`downloads`), the desktop folders (`gg.dekiru.musicplayer` under each XDG directory) and the desktop keyring item's attributes (`application` and `xdg:schema`) are name-free on purpose, like the bundle IDs. Changing any of them after a release would need code that moves the old data across.

**The login is in the desktop's keyring when there is one.** `desktopSecretStore()` (called in `Main.kt`) talks to the Secret Service API over D-Bus (GNOME Keyring; KWallet from KDE Frameworks 5.97; KeePassXC with its Secret Service option on): one item labelled "Tetsubi login", found by `application=gg.dekiru.musicplayer`, holding every key as Java properties text. It is read once at start; a locked keyring shows the desktop's unlock prompt then, before the window opens. A login in the old file moves in on the first run and the file is deleted. With no session bus or nothing offering Secret Service, `FileSecretStore` keeps it in `$XDG_DATA_HOME/gg.dekiru.musicplayer/login.properties` (mode 600) as before; a keyring that stays locked (prompt dismissed) gives a login that lasts until the app closes, rather than a plain file next to a keyring. The session is "plain" (the secret crosses the session bus unencrypted, which only the user's processes see); libsecret encrypts that hop, and that could be added. On KDE Plasma (KWallet) a login kept in the old file moved into the keyring on the first start, as "Tetsubi login", and the file was deleted. Making a default keyring where a desktop has none, and the unlock prompt, were not tried on a desktop. Settings are in `$XDG_CONFIG_HOME/gg.dekiru.musicplayer/settings.properties` and caches under `$XDG_CACHE_HOME/gg.dekiru.musicplayer`.

**D-Bus code needs two more JDK modules in the packaged runtime.** dbus-java (MPRIS and the keyring) reads the user ID for the bus's EXTERNAL sign-in through `jdk.security.auth` (`UnixSystem`) and uses `jdk.net`; Compose's default jlinked runtime has neither, and without the first every connection fails with `NoClassDefFoundError` (an `Error`, so the connect calls catch `Throwable`). Both are listed in `nativeDistributions.modules` in `desktopApp/build.gradle.kts`. `./gradlew :desktopApp:suggestRuntimeModules` shows what else a new library needs. dbus-java logs through SLF4J, so `slf4j-nop` is on the runtime classpath to keep SLF4J's start-up warning out of stderr.

### Media keys (MPRIS)

**The app is an MPRIS player, `org.mpris.MediaPlayer2.tetsubi`.** `MprisService` (`shared/src/desktopMain/.../playback/mpris/`) exports `/org/mpris/MediaPlayer2` with the root and Player interfaces and `org.freedesktop.DBus.Properties` over dbus-java, so media keys, GNOME's and KDE's now-playing panels and `playerctl` reach it. A second copy of the app takes `….tetsubi.instance<pid>`, as the spec says. With no session bus it logs "Tetsubi MPRIS: …" and the app carries on. TrackList, OpenUri, Rate and Volume are not offered (Volume reads 1; the app's volume is normalisation's). Checked on KDE Plasma (Wayland): the keyboard's previous and next keys, play-pause, next, previous, SetPosition and Seek, and the title, artist, album, length and cover.

**Commands go to the controller, not the engine.** On Android the MediaSession drives ExoPlayer directly; on Linux `MprisBridge` calls the same `PlayerController` methods as the app's buttons, on the main thread, so the play report, a failed track's retry and Maniac Mode behave the same. Previous follows the app (back to 0:00 after 3 s), Seek past the end is Next, Stop pauses (the app has no "stopped with a queue"), and SetPosition is ignored for anything but the playing entry. In Maniac Mode CanGoNext, CanGoPrevious and CanSeek are false and skip, seek, shuffle and repeat calls are ignored; `MpvEngine.setSkipControlsEnabled` does nothing.

**State goes out through `snapshotFlow`.** The bridge reads the controller's Compose state and sends `PropertiesChanged` with only what changed (`Mpris.changed`). Position is never signalled (the spec); `Position` reads the engine while playing and the last shown position otherwise, and `Seeked` is sent when the position jumps more than 1.5 s from where the clock puts it. `mpris:trackid` is the queue entry's ID, as a path under `/gg/dekiru/musicplayer/entry/`.

**Covers are files, fetched with the sign-in header.** The desktop can't send the `Authorization` header Jellyfin images need, so the bridge saves covers to `$XDG_CACHE_HOME/gg.dekiru.musicplayer/mpris-art/` (newest 30 kept) and gives `mpris:artUrl` as a `file://` URI; a downloaded album's own cover is used where it is. The metadata goes out without a cover first and again when it arrives.

**`DesktopEntry` is a guess.** It is `tetsubi-tetsubi` (the name jpackage's .deb and .rpm install the .desktop file under) or `tetsubi` inside the AppImage (`$APPIMAGE` set); the panel uses it for the app's icon. Unverified on a desktop.

**Keys in the window follow the Mac.** Space and Ctrl+P play or pause, Ctrl+→ and Ctrl+← skip (none in Maniac Mode), and so do the Pause/Break key (which some laptops put on an F key) and a media Play/Pause key if one reaches the window rather than the desktop. `PlaybackKeys` is the window's `onKeyEvent`, not `onPreviewKeyEvent`, so a focused text field keeps its spaces and Ctrl+arrows, as `SpaceKeyRule` does on the Mac; the rule itself is `PlaybackKeyRule`. A held key acts once (held from press to release; AWT asks X11 for detectable auto-repeat). The keyboard's media keys outside the window go through MPRIS instead. The key code is the AWT one, read with `Key.nativeKeyCode`: on the desktop `KeyEvent.nativeKeyEvent` is Compose's own internal event, not the AWT event, and Compose Multiplatform 1.12 has no `awtEventOrNull`. Checked on a laptop with KDE Plasma on Wayland (the window runs through XWayland), whose F10 Pause key sends Pause/Break.

**Tests use a bus of their own.** `PrivateBus` (desktopTest) starts `dbus-daemon` with a config that activates nothing, so tests never reach the desktop's bus or start the real keyring by accident; `KeyringSecretStoreTest` starts `gnome-keyring-daemon --unlock` against it with a throwaway home. Both are skipped where the daemons aren't installed (a Mac). Checked on Ubuntu 24.04 with GNOME Keyring 46 and playerctl, not on a desktop session.

**`:desktopApp` needs `components-resources` as a direct dependency.** Without it the desktop app cannot load images from the shared module.

**A UDP sweep blocks on Linux kernels (Android included).** A packet to an address that has not answered ARP waits in the kernel and counts against the socket's send buffer, so after about 150 of them `send` blocked and the sweep took 9 seconds. The fix is a 4 MB send buffer and listening while the sweep goes out. macOS does not behave this way.

### Playback (libmpv)

**Linux plays through libmpv, because gapless albums come first.** `MpvEngine` (`shared/src/desktopMain/.../playback/`) drives mpv's library through its C API with JNA. mpv's own playlist holds the whole queue, so mpv joins the tracks itself: `gapless-audio=yes` keeps one audio output open across tracks (a track in a different format is resampled rather than the output reopened, which would leave a gap) and `prefetch-playlist=yes` opens the next track before the current one ends. Other settings: audio only (`vo=null`, `video=no`, `audio-display=no`), none of the user's mpv config, scripts, keys or terminal, `idle=yes` with `keep-open=no` (the playlist stays when the queue ends), a 30-second read-ahead cache, `replaygain=no` (normalisation is the app's own volume) and `audio-client-name=Tetsubi`.

**The queue is mpv's playlist, entry for entry.** `MpvPlayback` keeps the same list and mirrors `Media3Engine`: a new queue is `stop` plus `loadfile … append` per track and `playlist-play-index`; a skip is `playlist-play-index`; Up next changes (`replaceAfterCurrent`) remove the entries after `playlist-pos` from the end and append, so the playing track is never touched; Repeat One is `loop-file=inf`, Repeat All `loop-playlist=inf`. A start mpv makes by itself (the next track, Repeat All going round) is reported as automatic; a start the app asked for is waited for, and earlier, stale starts are ignored. Repeat One going round makes no new file, so the engine treats a seek it did not ask for near the end of the track as the repeat.

**mpv goes on to the next track when one fails; Media3 stops.** On an end-of-file with reason "error" the engine pauses and sends `stop keep-playlist`, reports the error, and Play loads the failed track again. The last track ending with Repeat off is `onEnded`; mpv is then idle with the playlist kept, and the controller's skip back to track 1 loads it paused.

**Commands run in order on their own thread, events on another, and the window never asks mpv anything.** libmpv can block while it opens a slow stream, so the main thread only queues commands; position and length are values mpv reported (`time-pos`, `duration`), and events reach `PlayerController` on the AWT event thread. The engine is closed (`mpv_terminate_destroy`) when the window closes, and by a shutdown hook otherwise.

**The `Authorization` header goes in `http-header-fields`, with `change-list … append`.** The option is a string list whose plain `set` splits on commas, and the Jellyfin header is full of them. Being a global option, it applies to every stream mpv opens, prefetched ones included.

**Originals, not conversions, for gapless.** `directPlayFormats` is every `AudioFormat` (FFmpeg decodes them all, ALAC included), so the server sends the original file. A stream the server converts on the fly (`/universal`) carries encoder padding at both ends, which is a gap between tracks. Files `AudioFormat.source` does not recognise (APE, WavPack, DSD and so on) are still converted to FLAC by the server and so are not gapless; they would need their own `AudioFormat` entries. Converted streams are seeked by restarting them at the point, as on Android.

**Without libmpv, Play stays off and Home says what to install.** The library is loaded as `mpv`, then `libmpv.so.2`, then `libmpv.so.1` (runtime packages ship only the versioned file; `libmpv.dylib` from Homebrew for development on a Mac), and client API 1 or 2 is accepted. If it is missing, `isSupported` is false, the reason is logged to stderr ("Tetsubi playback: …") and Home shows "Install mpv (libmpv) to play music on Linux." (`PlayerEngine.unavailableReason`). Packages: `libmpv2` (Debian 12, Ubuntu 24.04 and later) or `libmpv1` (Ubuntu 22.04), `mpv-libs` on Fedora.

**The packages' libmpv dependency goes to jpackage as a raw argument.** Compose Multiplatform's `nativeDistributions.linux` has no setting for jpackage's `--linux-package-deps` (checked in 1.12.2), and jpackage only finds libraries the Java runtime links, not ones loaded at run time. The jpackage tasks' `freeArgs` (not in the DSL, but on `AbstractJPackageTask`) carry it: `libmpv2|libmpv1` for the .deb and the library itself, `libmpv.so.2()(64bit)`, for the .rpm, which Fedora's `mpv-libs` and openSUSE's `libmpv2` provide. Compose writes `freeArgs` unquoted, one per line, into jpackage's argument file, so a value with a space splits into several arguments; hence `a|b`, which dpkg reads as `a | b`. Checked with JDK 21's jpackage on Ubuntu 24.04 (Depends ends `libmpv2 | libmpv1`, Requires has `libmpv.so.2()(64bit)`), not yet through the Compose task. The `Linux release` workflow checks both packages, still adds the .deb's if it's missing (as it used to), installs `libmpv1` on the runner and fails the start-up check if the app logs that playback is off or that it couldn't get on the session bus (`dbus-run-session`).

**The AppImage does not bundle libmpv, on purpose.** libmpv's dependencies are most of a desktop's media stack: on Ubuntu 24.04 `libmpv.so.2` links 225 libraries, about 250 MB unpacked (FFmpeg, Mesa and LLVM, Wayland, PulseAudio and more), many of which an AppImage should take from the system anyway. Bundling it properly would mean a libmpv built for audio only. The AppImage shows Home's "Install mpv (libmpv)" notice where it is missing, like the packages.

**Tests never make a sound.** `MpvPlaybackTest` drives the queue logic with made-up mpv events. `MpvEngineIntegrationTest` uses the real libmpv with `ao=null` and generated WAV files, and is skipped where libmpv is not installed.

**Packaging is configured but untested.** AppImage, deb and rpm targets are set up in `Kotlin/desktopApp/build.gradle.kts` and had not been built.

## Artwork and fonts

**ImageMagick drops SVG gradients.** Render icon artwork with `NSImage` (a small Swift script) or `qlmanage -t -s 1024`, both of which draw the gradient.

**The iOS icon must be 1024 px with no alpha.** The Mac icon set needs every size from 16 to 1024, each drawn from the SVG.

**macOS fits a full-square icon image into its 824 of 1024 rounded tile.** Artwork therefore appears smaller in the Dock than in the file; size the artwork with that in mind.

**The Android themed (monochrome) icon layer is alpha only.** It is made from the artwork's luminance times alpha, used as the alpha of a white square. The adaptive foreground is drawn on a 108 dp canvas at five densities.

**After adding an asset catalogue, run `xcodegen generate` again.** The catalogue is not part of the target until the project is regenerated.

**"Plex" is a Reserved Font Name.** The app's typeface is IBM Plex Sans (SIL OFL 1.1) with modified vertical metrics, so the files had to be renamed: they are DEKIRU Sans (`DekiruSans-*.ttf` in Swift, `dekirusans_*.ttf` in Compose resources). IBM's copyright and the licence text are kept beside them with a README saying what changed.

**The metrics were changed to match the system line height.** Plex is 1.300 em per line against the iOS system font's 1.193 em, which pushed layouts visibly lower. The bundled files have ascender 951, descender -242 and line gap 0. To change them again, edit hhea and the OS/2 typo ascender and descender in all four files and keep the renaming.

**Fonts are registered in code and UIKit bars need their own call.** `PlexFont` registers the package's fonts at first use, `Font.plex(…)` keeps each text style's size and Dynamic Type scaling, and `PlexFont.applyToUIKitBars()` (called from the iOS `AppDelegate`) sets navigation titles, tab labels, bar buttons and segmented controls.

## Tooling

### Xcode, simulators and profiling

**`leaks` cannot attach to simulator apps.** Profile memory on the Mac build instead: run a debug build with `MallocStackLogging=1` and use `footprint`, `leaks`, `sample` and `top`. Xcode's static analyser found nothing; it mostly checks C and Objective-C.

**Trigger a shake in the simulator from the command line.** Use `xcrun simctl spawn <id> notifyutil -p com.apple.UIKit.SimulatorShake`.

**The simulator has no AirPlay devices and its audio can fail.** Tapping the AirPlay button there shows nothing, and a simulator whose audio output fails (CoreAudio error 35) makes every track fail to play.

**The simulator runtime must be at least the app's deployment target.** While the deployment target was wrong (see the `options:` item above), the app could not be checked on the older simulators that were installed.

**Bare `swift test` runs without app entitlements.** Keychain tests failed with -34018 there until the Mac moved to the login keychain. Live network tests are skipped unless `TETSUBI_LIVE_ADDRESS` or `TETSUBI_LIVE_DISCOVERY=1` is set.

**Screenshots of a Mac window use `screencapture -l <window id>`.** It must run from a terminal that has screen-recording permission; a sandboxed shell cannot do it.

### Android emulators and adb

**Old-version bugs only show on an old emulator.** The API 33 call and the Android 8.0 decoder gap were both found on an Android 8 emulator; keep an AVD at the minimum SDK and test release builds on it.

**An AVD can demand more disk than you have.** Lower `disk.dataPartition.size` in the AVD's `config.ini` if the emulator refuses to start for lack of space.

**Emulator discovery finds nothing.** The emulator's NAT network has no server to answer the UDP search, so type the address there.

**Android Studio's bundled JDK is enough.** Set `JAVA_HOME` to `/Applications/Android Studio.app/Contents/jbr/Contents/Home` and build with the Gradle wrapper. AGP fetches the SDK pieces it needs; Google's command-line tools (`sdkmanager`, `avdmanager`) are only needed to create extra AVDs.

**Wireless debugging changes its port.** Pair with `adb pair <ip>:<pairing port> <code>`, then `adb connect <ip>:<port>`; the connect port changes whenever Wireless debugging restarts, including after changing a network's metered setting.

**Over a VPN, read the adb port from the device's screen.** mDNS discovery of adb does not cross a VPN, and port scanning cannot find the port.

**Turning Wi-Fi off drops wireless adb.** Offline behaviour has to be checked by hand, in aeroplane mode.

**Mark Wi-Fi as metered to test "Waiting for Wi-Fi".** Use `adb shell cmd netpolicy set metered-network <name> true`, and set it back afterwards.

### Tests

**Shared Kotlin tests run twice.** `commonTest` runs on the desktop JVM and again as Android host tests, so test counts double; `./gradlew :shared:allTests` runs both.

**Pull pure logic out of actors and requests so it can be tested.** Examples are `planQueue`, `upNextIndices`, `distinctPlayedAlbums` and `LibraryView.gridLayout(width:)`; several of the bugs above were found by such tests rather than on a device.

**Keep the two codebases in step.** Settings keys, wording and behaviour are meant to match between the Swift and Kotlin apps (for example `library.sort` and `downloads.defaultFormat`), and porting one side has exposed a bug in the other (the sub-path server address).
