# Tetsubi — notes for Claude

Tetsubi is a Jellyfin music player by DEKIRU Ltd. Two codebases, one product:

- **iOS + Mac (Swift/SwiftUI):** `JellyfinMusicCore/` (shared package: models, API, player, downloads, views) + `Tetsubi-iOS/`, `Tetsubi-macOS/`, `Tetsubi-Widget/`. `Tetsubi.xcodeproj` is generated from `project.yml` (`xcodegen generate`) and is not committed.
- **Android + Linux (Kotlin Multiplatform, Compose):** `Kotlin/` — `:shared` (commonMain = almost everything; androidMain, desktopMain, jvmSharedMain), `:androidApp`, `:desktopApp`.

Read `docs/gotchas.md` before changing signing, playback, downloads, widgets or the build. `docs/icons.md` covers the icon sizes.

## Names that must not change

The user-facing name is Tetsubi. The permanent IDs are deliberately name-free, so the app can be renamed without becoming a new app:

- iOS `gg.dekiru.musicplayer.ios`, widget `gg.dekiru.musicplayer.ios.widget`, Mac `gg.dekiru.musicplayer.macos`
- App Group `group.gg.dekiru.musicplayer.shared`
- Kotlin package and Android application ID `gg.dekiru.musicplayer`
- The typeface is "DEKIRU Sans" (a modified IBM Plex Sans; "Plex" is a reserved font name).

## How to work

- **Parity matters.** A feature or fix on one platform goes to the other in the same piece of work, or the gap is stated plainly. iOS is the spec for behaviour and wording on Android unless decided otherwise.
- Commit or push only when asked.
- Never put model names or IDs, private addresses, hostnames, device IDs or signing key IDs in commits, code or docs. This repo is meant to be shareable.
- Temporary debug hooks (launch-argument screenshots, server probes) are fine for checks but must be removed before committing.
- Report what was checked on a device and what wasn't.

## Build and test

- Swift tests: `cd JellyfinMusicCore && swift test`
- iOS: `xcodegen generate`, then `xcodebuild -project Tetsubi.xcodeproj -scheme Tetsubi-iOS -destination 'id=<simulator>' -derivedDataPath <scratch>/dd build`
- Mac: `xcodebuild -project Tetsubi.xcodeproj -scheme Tetsubi-macOS -derivedDataPath <scratch>/ddmac CODE_SIGNING_ALLOWED=NO build`
- Kotlin (all tests + APK + desktop): `cd Kotlin && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :shared:allTests :androidApp:assembleDebug :desktopApp:compileKotlin` (add `-Pkotlin.incremental=false` if the incremental cache breaks). Count results from `Kotlin/*/build/test-results/**/*.xml`.
- Android release APK (signed, R8): `./gradlew :androidApp:assembleRelease`. It needs the release key outside the repo and its password in the macOS Keychain or `TETSUBI_KEYSTORE_PASSWORD`; without them the build is left unsigned. The names are in `Kotlin/androidApp/build.gradle.kts`.
- Versions: iOS and Android share a version and build number (`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` at the top of `project.yml`; `versionName` / `versionCode` in `Kotlin/androidApp/build.gradle.kts`). Raise the build number before each upload.
