// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "JellyfinMusicCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(name: "JellyfinMusicCore", targets: ["JellyfinMusicCore"]),
        // What the app tells the home-screen widget. Kept apart so the widget
        // extension links this and nothing else (no player, network or views).
        .library(name: "TetsubiWidgetData", targets: ["TetsubiWidgetData"])
    ],
    dependencies: [
        // KeychainAccess — optional; we hand-roll it in KeychainManager.swift
        // Add when needed:
        // .package(url: "https://github.com/kylef/KeychainAccess", from: "4.3.0")
    ],
    targets: [
        .target(name: "TetsubiWidgetData"),
        .target(
            name: "JellyfinMusicCore",
            dependencies: ["TetsubiWidgetData"],
            // IBM Plex Sans with SF Pro line height, as "DEKIRU Sans" (SIL OFL), registered by PlexFont.
            resources: [.copy("Resources/Fonts")]
            // If adding KeychainAccess:
            // .product(name: "KeychainAccess", package: "KeychainAccess")
        ),
        .testTarget(
            name: "JellyfinMusicCoreTests",
            dependencies: ["JellyfinMusicCore", "TetsubiWidgetData"]
        )
    ]
)
