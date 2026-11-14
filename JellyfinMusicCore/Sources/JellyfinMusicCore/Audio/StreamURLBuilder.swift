// MARK: - StreamURLBuilder — builds playable audio stream URLs

import Foundation

/// Builds authenticated, format-correct stream URLs for AVPlayer.
///
/// The strategy:
///   - When the source container matches the requested one, direct-play the
///     original file via `/Audio/{id}/stream.<ext>?static=true`.
///   - Otherwise use the universal endpoint `/Audio/{id}/universal`, which
///     requires `UserId`, `DeviceId` and a container, and transcodes as needed.
///
/// Authentication in the URL uses `ApiKey=<token>` because
/// `AVURLAsset` doesn't accept a custom `Authorization` header. The token
/// is short-lived per session, never persisted, and the host is typically
/// on the local network (per the user's "HTTP for LAN-only" decision).
public struct StreamURLBuilder {

    let api: JellyfinAPI
    let userId: String

    public init(api: JellyfinAPI, userId: String) {
        self.api = api
        self.userId = userId
    }

    /// A direct-play stream for a track, with `api_key` and `format`
    /// query params baked in. Returns `nil` if we can't build a URL
    /// (e.g. missing token, malformed base URL).
    ///
    /// - Parameters:
    ///   - track: the `JellyfinTrack` to play.
    ///   - format: target `AudioFormat`. Default `.alac` — universally
    ///     playable by AVURLAsset on Apple platforms, lossless, and
    ///     what `DeviceProfile.current` lists as a direct-play
    ///     container.
    ///   - maxBitrate: when set (bits/s), streams AAC transcoded to at most
    ///     this bitrate — used on cellular. `nil` keeps the normal behaviour.
    public func url(
        for track: JellyfinTrack,
        format: AudioFormat = .alac,
        maxBitrate: Int? = nil
    ) -> URL? {
        if let maxBitrate {
            return cappedURL(for: track, bitrate: maxBitrate)
        }
        // Choose the right endpoint.
        //  * If the source already matches the requested container, direct-play.
        //  * Otherwise transcode via /Audio/{id}/universal.
        let sourceContainerNormalized = track.mediaSource?.container?.lowercased()
        let requestedContainer = Self.containerFor(format)

        let canDirectPlay = sourceContainerNormalized == requestedContainer

        var query: [URLQueryItem]
        let path: String
        if canDirectPlay {
            // Original file, untouched.
            path = "Audio/\(track.id)/stream.\(format.fileExtension)"
            query = [URLQueryItem(name: "static", value: "true")]
        } else {
            path = "Audio/\(track.id)/universal"
            query = [
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "DeviceId", value: api.deviceId),
                URLQueryItem(name: "Container", value: requestedContainer),
                URLQueryItem(name: "TranscodingContainer", value: requestedContainer),
                URLQueryItem(name: "TranscodingProtocol", value: "http"),
            ]
            if let codec = Self.audioCodecFor(format) {
                query.append(URLQueryItem(name: "AudioCodec", value: codec))
            }
        }
        // `ApiKey` (exact casing) is what the universal endpoint accepts;
        // `/stream` accepts it too.
        if let token = api.accessToken {
            query.append(URLQueryItem(name: "ApiKey", value: token))
        }

        return api.buildURL(path, queryItems: query)
    }

    /// `/Audio/{id}/universal` forced to AAC in m4a at `bitrate` (checked
    /// against the server: returns audio/mp4 AAC).
    private func cappedURL(for track: JellyfinTrack, bitrate: Int) -> URL? {
        var query = [
            URLQueryItem(name: "UserId", value: userId),
            URLQueryItem(name: "DeviceId", value: api.deviceId),
            URLQueryItem(name: "Container", value: "m4a"),
            URLQueryItem(name: "TranscodingContainer", value: "m4a"),
            URLQueryItem(name: "TranscodingProtocol", value: "http"),
            URLQueryItem(name: "AudioCodec", value: "aac"),
            URLQueryItem(name: "MaxStreamingBitrate", value: String(bitrate)),
            URLQueryItem(name: "AudioBitRate", value: String(bitrate)),
        ]
        if let token = api.accessToken {
            query.append(URLQueryItem(name: "ApiKey", value: token))
        }
        return api.buildURL("Audio/\(track.id)/universal", queryItems: query)
    }

    static func audioCodecFor(_ format: AudioFormat) -> String? {
        switch format {
        case .flac: "flac"
        case .alac: "alac"
        case .aac:  "aac"
        case .opus: "opus"
        case .mp3:  "mp3"
        case .wav:  nil
        }
    }

    /// The Jellyfin `format=` query token for a target `AudioFormat`.
    /// Maps to the *container* name, since the universal endpoint keys
    /// off the container.
    static func containerFor(_ format: AudioFormat) -> String {
        switch format {
        case .flac: "flac"
        case .alac: "m4a"   // Apple Lossless
        case .aac:  "m4a"
        case .opus: "ogg"
        case .mp3:  "mp3"
        case .wav:  "wav"
        }
    }
}