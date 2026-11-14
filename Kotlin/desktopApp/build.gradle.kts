import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Linux (desktop) app: window, and later the sidebar, menus, MPRIS and
// packaging (see ../Linux/plans). Screens and logic come from :shared.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
}

compose.desktop {
    application {
        mainClass = "gg.dekiru.musicplayer.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "tetsubi"
            packageVersion = "0.1.0"
            description = "A music player for Jellyfin"
            vendor = "DEKIRU Ltd"
            linux {
                iconFile.set(project.file("icons/tetsubi.png"))
            }
        }
    }
}
