import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// Code shared by the Android and Linux (desktop) apps: models, server code,
// logic and Compose screens. Platform code goes in androidMain / desktopMain;
// code both of them can share (they are both JVMs) goes in jvmSharedMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "gg.dekiru.musicplayer.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Compose resources (images) need Android resources switched on.
        androidResources { enable = true }
        // Run the shared tests (commonTest) on the JVM for Android too.
        withHostTest {}
    }
    jvm("desktop")

    // jvmSharedMain: code both JVM platforms share (Android and desktop).
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.datetime)
            // Files for downloads (already used by Ktor).
            implementation(libs.kotlinx.io.core)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        // Shared by Android and desktop: the OkHttp engine, UDP sockets for
        // server discovery, java.text.Normalizer.
        getByName("jvmSharedMain").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            api(libs.androidx.media3.exoplayer)
            api(libs.androidx.media3.session)
            // Google Cast (Default Media Receiver); pulls in Play services Cast framework.
            api(libs.androidx.media3.cast)
            implementation(libs.androidx.mediarouter)
            // The Cast device dialogs are AppCompat dialogs (mediarouter already brings it at run time).
            implementation(libs.androidx.appcompat)
            // Downloads go on with the app closed (foreground work).
            implementation(libs.androidx.work.runtime)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.resources {
    packageOfResClass = "gg.dekiru.musicplayer.resources"
    publicResClass = true
}
