# Tetsubi — Kotlin (Android + Linux)

One Gradle build for the Android app and the Linux desktop app. Things that are easy to get wrong are in [`../docs/gotchas.md`](../docs/gotchas.md).

| Module | What |
|---|---|
| `shared/` | Kotlin Multiplatform: `commonMain` (models, server code, logic, Compose screens, theme, images), `jvmSharedMain` (what Android and desktop share: OkHttp, UDP sockets), `androidMain`, `desktopMain`, `commonTest`, `desktopTest` (live server checks) |
| `androidApp/` | Android entry point (`MainActivity`), manifest, launcher icon, splash |
| `desktopApp/` | Linux/desktop entry point (window), packaging settings |

Versions are in `gradle/libs.versions.toml` (AGP 9.5.1, Kotlin 2.4.21, Compose Multiplatform 1.12.2, Ktor 3.6.0, kotlinx.serialization 1.12.0, coroutines 1.11.0, kotlinx-datetime 0.8.0, Coil 3.6.3, Media3 1.11.1, Gradle 9.9.0 via the wrapper; compileSdk/targetSdk 37, minSdk 27).

## Build and run

Use Android Studio's bundled Java (JDK 25):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Android APK (debug):

```bash
./gradlew :androidApp:assembleDebug
```

Install on the emulator or a connected device:

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Desktop window (on the Mac now; Linux later):

```bash
./gradlew :desktopApp:run
```

Shared tests (run on the desktop JVM and as Android host tests):

```bash
./gradlew :shared:allTests
```

Live checks against a real server (skipped unless set):

```bash
TETSUBI_LIVE_ADDRESS=192.168.1.50 TETSUBI_LIVE_DISCOVERY=1 ./gradlew :shared:desktopTest --tests '*LiveServerTest*' --rerun -i
```

The desktop app keeps its login in `$XDG_DATA_HOME/gg.dekiru.musicplayer/login.properties` (else `~/.local/share/gg.dekiru.musicplayer/`); delete it to sign out by hand.

`local.properties` (not in git) holds `sdk.dir=<Android SDK path>`; Android Studio writes it, or create it by hand.

## Artwork

The icons and images are generated from the disc artwork and the background picture, whose source files are kept outside the repo. Sizes and how to redraw them are in [`../docs/icons.md`](../docs/icons.md). They are: the shared disc and background images, the Android adaptive icon (navy background, disc foreground, white monochrome layer for themed icons), the splash disc, and the desktop icon.
