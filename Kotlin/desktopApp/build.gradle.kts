import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Linux (desktop) app: the window, media keys (MPRIS) and packaging.
// Screens and logic come from :shared.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Java 17 bytecode whatever JDK builds it (as :shared and Android).
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
}

compose.desktop {
    application {
        mainClass = "gg.dekiru.musicplayer.desktop.MainKt"
        // Skia (skiko) loads its native library through JNI; newer JDKs warn
        // about that, and later ones will refuse, unless it's allowed.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // No shrinking for now: the ProGuard-shrunk 1.0.6 packages couldn't
        // reach the server (Ktor/serialization code removed or renamed) and
        // can't be tested on a Mac. Turn it back on only after a run on Linux.
        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "tetsubi"
            // "<versionName>.<versionCode>" from ../gradle.properties, e.g. 1.0.5 (jpackage wants three numbers).
            packageVersion = providers.gradleProperty("tetsubi.versionName").get() + "." +
                providers.gradleProperty("tetsubi.versionCode").get()
            description = "A music player for Jellyfin"
            // On top of Compose's default runtime modules: D-Bus (dbus-java, for MPRIS and the
            // keyring) reads the user ID for the bus's EXTERNAL sign-in through jdk.security.auth
            // (UnixSystem) and sets socket options through jdk.net.
            modules("jdk.security.auth", "jdk.net")
            vendor = "DEKIRU Ltd"
            linux {
                // Playback needs libmpv at run time: see the package dependencies below.
                iconFile.set(project.file("icons/tetsubi.png"))
            }
        }
    }
}

// Playback needs libmpv, which the app loads at run time (JNA), so jpackage
// doesn't find it, and Compose's DSL has no setting for jpackage's
// --linux-package-deps: it goes in as a raw jpackage argument. The .deb takes
// either Debian/Ubuntu package (libmpv2 on Debian 12 / Ubuntu 24.04 and later,
// libmpv1 on Ubuntu 22.04); the .rpm asks for the library itself, which
// Fedora's mpv-libs and openSUSE's libmpv2 both provide. Compose writes these
// unquoted into jpackage's argument file, one per line, so they must have no
// spaces (dpkg reads `a|b` as `a | b`). The release workflow checks both
// packages (see docs/gotchas.md).
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    when {
        name.endsWith("Deb") -> freeArgs.addAll("--linux-package-deps", "libmpv2|libmpv1")
        name.endsWith("Rpm") -> freeArgs.addAll("--linux-package-deps", "libmpv.so.2()(64bit)")
    }
}
