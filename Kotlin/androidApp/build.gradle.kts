// The Android app: entry point, and later the playback service, Android Auto
// and Cast. Screens and logic come from :shared. (AGP 9 compiles Kotlin itself.)
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/*
 * Release signing. The key lives outside the repo (~/.android-keys/tetsubi-release.jks)
 * and its password in the macOS Keychain ("Tetsubi Android release key"), or in
 * TETSUBI_KEYSTORE_PASSWORD. Without them a release build is left unsigned.
 * Never commit the key or its password; a lost key means no more updates.
 */
val releaseKeystore = file(System.getProperty("user.home") + "/.android-keys/tetsubi-release.jks")
val releaseKeyPassword: String? = providers.environmentVariable("TETSUBI_KEYSTORE_PASSWORD").orNull
    ?: providers.exec {
        commandLine("security", "find-generic-password", "-a", "tetsubi-release", "-s", "Tetsubi Android release key", "-w")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }

android {
    namespace = "gg.dekiru.musicplayer.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "gg.dekiru.musicplayer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // In step with iOS: the same version and build number (project.yml MARKETING_VERSION /
        // CURRENT_PROJECT_VERSION). Raise both before each release.
        versionCode = 5
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = releaseKeyPassword
                keyAlias = "tetsubi"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink and optimise the code, drop unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists() && releaseKeyPassword != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.media3.session)
    implementation(libs.coil.compose)
    // The home-screen widget.
    implementation(libs.androidx.glance.appwidget)
    // Cast's dialogs bring in an old Fragment; 1.3+ is needed for permission requests (release lint).
    implementation(libs.androidx.fragment)
    // Android Auto's browse tree, IDs and voice requests (plain Kotlin, run on the JVM).
    testImplementation(kotlin("test-junit"))
}
