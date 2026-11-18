import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Linux (desktop) app: window, and later the sidebar, menus, MPRIS and
// packaging (see ../Linux/plans). Screens and logic come from :shared.
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
            vendor = "DEKIRU Ltd"
            linux {
                // Playback needs libmpv at run time. Compose has no setting for jpackage's
                // --linux-package-deps, so the release workflow adds it to the .deb (see docs/gotchas.md).
                iconFile.set(project.file("icons/tetsubi.png"))
            }
        }
    }
}
