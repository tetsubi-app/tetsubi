# Tetsubi

A music player for [Jellyfin](https://jellyfin.org/) servers, built around playing whole albums. For iPhone, iPad, CarPlay and Mac (Swift), and Android and Linux (Kotlin).

Website, support and privacy: [tetsubi.app](https://tetsubi.app)

Tetsubi is made by DEKIRU Ltd. It isn't affiliated with Jellyfin. Made with AI.

## What it does

- Signs in with Quick Connect or a password, and finds servers on the local network.
- Home: On This Day (albums released on today's date), Favourites, Recently Played, Recently Added, Random Albums.
- Library, artist pages, search, favourites, and Jellyfin playlists (browse, play, make, edit). Albums and favourites sort by name, artist, release date or date added/favourited.
- On wider screens (Mac, iPad, Android tablets, Linux), zoom buttons choose 3 to 10 album cards per row; covers and text grow with them.
- Recently Played: a page of the albums you played last, with an export of a 3×3, 4×4 or 5×5 cover grid (1200×1200 PNG, optional album and artist labels) to the clipboard or a file.
- Tap an artist's name on an album or in Now Playing to see all their albums. Now Playing grows with the window on Mac, iPad, Android tablets and Linux, in two columns when there's room.
- Gapless album playback with lock-screen and media-key controls, an equaliser and a visualiser.
- A listening queue: Play Next and Add to Queue on any album, playlist or track; reorder, remove or clear it in Now Playing; shuffle and repeat across it; it's kept when the app closes.
- Downloads for offline listening, in the original format or converted.
- Home-screen widgets; CarPlay and Siri on iOS; Android Auto, voice and Google Cast on Android.
- Maniac Mode: albums play start to finish, with no skipping, shuffle or scrubbing.
- Android and Linux check GitHub once a day for a newer release and say so (nothing is downloaded; it can be switched off). iOS and Mac update through the App Store.

## Status

| Platform | State |
|---|---|
| iOS, iPadOS | In testing (TestFlight) |
| macOS | Runs; not yet distributed |
| Android | In testing (signed APK on the [releases page](https://github.com/tetsubi-app/tetsubi/releases)) |
| Linux | In testing: .deb, .rpm and AppImage on the [releases page](https://github.com/tetsubi-app/tetsubi/releases); plays through libmpv (install mpv); media keys and the desktop's now-playing panel through MPRIS; Space, Pause and Ctrl+P play or pause and Ctrl+arrows skip, as on the Mac; the login is kept in the desktop's keyring |

## Layout

| Path | What |
|---|---|
| `JellyfinMusicCore/` | Swift package shared by the iOS and Mac apps: models, Jellyfin API, player, downloads, views, tests |
| `Tetsubi-iOS/`, `Tetsubi-macOS/`, `Tetsubi-Widget/` | The app targets and the iOS widget extension |
| `project.yml` | The Xcode project definition (XcodeGen) |
| `Kotlin/` | Kotlin Multiplatform build: `shared`, `androidApp`, `desktopApp` ([details](Kotlin/README.md)) |
| `docs/` | [Gotchas](docs/gotchas.md), [icons](docs/icons.md) |

## Build

**iOS and Mac** need Xcode (iOS 17 / macOS 14 or later) and [XcodeGen](https://github.com/yonaskolb/XcodeGen). The Xcode project is generated, not committed:

```bash
xcodegen generate
open Tetsubi.xcodeproj
```

Tests: `cd JellyfinMusicCore && swift test`

To sign your own builds, change `DEVELOPMENT_TEAM` and the bundle and App Group IDs in `project.yml` and the entitlements files; these IDs are unique across all Apple teams.

**Android and Linux**: see [`Kotlin/README.md`](Kotlin/README.md).

```bash
cd Kotlin
./gradlew :shared:allTests :androidApp:assembleDebug
./gradlew :desktopApp:run
```

## Licence

[Mozilla Public License 2.0](LICENSE). The bundled typeface, DEKIRU Sans, is a modified IBM Plex Sans under the SIL Open Font License; its licence is beside the font files.
