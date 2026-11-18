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
        buildTypes.release.proguard {
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
                iconFile.set(project.file("icons/tetsubi.png"))
            }
        }
    }
}
