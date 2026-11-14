// MARK: - DeviceProfile — tells Jellyfin which codecs this device can direct-play

import Foundation

/// The device profile sent to Jellyfin at login. Jellyfin uses this to decide
/// whether to direct-play or transcode audio.
///
/// For Apple platforms, the safe direct-play set is:
///   - FLAC (iOS 13+, macOS 10.15+) — ✅ universally safe on Apple
///   - ALAC / .m4a — ✅ natively supported
///   - AAC / .m4a — ✅ natively supported
///   - MP3 / .mp3 — ✅ natively supported
///   - WAV / PCM — ❌ NOT confirmed by Jellyfin docs for Apple clients; may be
///     transcoded to AAC on the server.
///
/// Opus is natively supported on iOS 17+/macOS 14+ in MP4 containers,
/// and iOS 18.4+/macOS 15.4+ in OGG. For safety, declare MP4-Opus only.
public struct DeviceProfile: Codable, Sendable {
    public static let current = DeviceProfile(
        name: "Tetsubi",
        directPlay: [
            DirectPlayProfile(type: .audio, containers: ["flac"]),
            DirectPlayProfile(type: .audio, containers: ["m4a"]),   // ALAC / AAC
            DirectPlayProfile(type: .audio, containers: ["mp3"]),
            DirectPlayProfile(type: .audio, containers: ["ogg"]),   // Opus iOS 18.4+
        ],
        transcodingContainers: [
            TranscodeProfile(type: .audio, container: "m4a",  audioCodecs: "aac"),
            TranscodeProfile(type: .audio, container: "flac", audioCodecs: "flac"),
            TranscodeProfile(type: .audio, container: "ogg",  audioCodecs: "opus"),
        ],
        maxAudioBitDepth: 24,
        maxStaticAudioBitrate: 2400
    )

    public let name: String
    public let directPlay: [DirectPlayProfile]
    public let transcodingContainers: [TranscodeProfile]
    public let maxAudioBitDepth: Int
    public let maxStaticAudioBitrate: Int

    public struct DirectPlayProfile: Codable, Sendable {
        public let type: ItemType
        public let containers: [String]
    }

    public struct TranscodeProfile: Codable, Sendable {
        public let type: ItemType
        public let container: String
        public let audioCodecs: String
    }

    public enum ItemType: String, Codable, Sendable {
        case audio
        case video
    }
}
