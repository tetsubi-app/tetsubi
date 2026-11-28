rootProject.name = "Tetsubi"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":audiodek", ":shared", ":androidApp", ":desktopApp")
