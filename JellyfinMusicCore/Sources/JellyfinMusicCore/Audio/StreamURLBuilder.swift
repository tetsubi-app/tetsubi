// MARK: - StreamURLBuilder — builds playable audio stream URLs

import Foundation

/// Builds authenticated stream and download URLs.
///
/// Streams (`url(...)`) follow `plan(codec:container:)`:
///   - a source AVPlayer plays (MP3, AAC, ALAC, FLAC, WAV, AIFF) is the
///     original file, `/Audio/{id}/stream.<its container>?static=true`;
///   - anything else is converted by `/Audio/{id}/universal` **as HLS**
///     (fMP4 segments): ALAC for a lossless or unknown source, AAC 320 kbps
///     for a lossy one. A plain HTTP transcode comes back chunked, with no
///     length and no byte ranges, and AVPlayer refuses it (-11850 / -12939);
///   - on mobile data with the cap, AAC at most that bitrate, as HLS.
///   `Container=` is chosen so the server has a single reason to convert
///   (see `hlsContainer`).
///
/// Downloads (`downloadURL(for:format:)`) are fetched by URLSession, which
/// takes a plain HTTP transcode, so they convert over HTTP.
///
/// Authentication in the URL uses `ApiKey=<token>` because
/// `AVURLAsset` doesn't accept a custom `Authorization` header.
public struct StreamURLBuilder {

    let api: JellyfinAPI
    let userId: String

    public init(api: JellyfinAPI, userId: String) {
        self.api = api
        self.userId = userId
    }

    // MARK: - The decision

    /// How a track is streamed to AVPlayer.
    public enum StreamPlan: Equatable, Sendable {
        /// The original file, with this extension in the path (`stream.<ext>`).
        case original(fileExtension: String)
        /// Converted by the server, as HLS, to this format (`.alac` or `.aac`).
        case converted(AudioFormat)

        public var isConverted: Bool {
            if case .converted = self { return true }
            return false
        }
    }

    /// The AAC bitrate a lossy source that AVPlayer can't play is converted at.
    public static let convertedAACBitrate = 320_000

    /// What AVPlayer does with a source of this codec and container (both as
    /// Jellyfin reports them; the container may be ffprobe's list, such as
    /// "mov,mp4,m4a,3gp,3g2,mj2"). AVFoundation plays MP3, AAC and ALAC
    /// (MP4 family), FLAC (since iOS 11 / macOS 10.13), WAV and AIFF (linear
    /// PCM) and ADTS AAC; the rest (Opus, Vorbis, WMA, APE, WavPack, DSD, any
    /// codec in a container it doesn't read, unknown) is converted.
    public static func plan(codec: String?, container: String?) -> StreamPlan {
        let codec = (codec ?? "").lowercased()
        let containers = (container ?? "").lowercased()
            .split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        func any(_ names: [String]) -> Bool { containers.contains { names.contains($0) } }
        let isPCM = codec.hasPrefix("pcm")
        if containers.isEmpty {
            // No container known (an old saved queue): go by the codec alone.
            switch codec {
            case "aac", "alac": return .original(fileExtension: "m4a")
            case "mp3": return .original(fileExtension: "mp3")
            case "flac": return .original(fileExtension: "flac")
            case _ where isPCM: return .original(fileExtension: "wav")
            default: return .converted(isLossyCodec(codec) ? .aac : .alac)
            }
        }

        if any(["m4a", "mp4", "m4b", "mov"]), codec.isEmpty || codec == "aac" || codec == "alac" {
            // Jellyfin usually says "m4a"; from ffprobe's list prefer a real extension.
            let ext = containers.first { ["m4a", "m4b", "mp4"].contains($0) } ?? "m4a"
            return .original(fileExtension: ext)
        }
        if any(["mp3"]), codec.isEmpty || codec == "mp3" { return .original(fileExtension: "mp3") }
        if any(["flac"]), codec.isEmpty || codec == "flac" { return .original(fileExtension: "flac") }
        if any(["wav", "wave"]), codec.isEmpty || isPCM { return .original(fileExtension: "wav") }
        if any(["aiff", "aif", "aifc"]), codec.isEmpty || isPCM {
            return .original(fileExtension: containers.first { ["aiff", "aif", "aifc"].contains($0) } ?? "aiff")
        }
        if any(["aac"]), codec.isEmpty || codec == "aac" { return .original(fileExtension: "aac") }
        return .converted(isLossyCodec(codec) ? .aac : .alac)
    }

    /// Lossy codecs (converting them to a lossless format would only make the
    /// stream bigger). Anything not listed, including unknown, counts as
    /// possibly lossless.
    static func isLossyCodec(_ codec: String) -> Bool {
        // WMA Lossless is the one lossless member of its family.
        if codec == "wmalossless" { return false }
        let lossy = ["opus", "vorbis", "wma", "mp3", "mp2", "aac", "ac3", "eac3", "dts", "mpc", "musepack",
                     "atrac", "amr", "speex", "cook"]
        return lossy.contains { codec.hasPrefix($0) }
    }

    // MARK: - Streams

    /// The stream AVPlayer plays for a track (see `plan(codec:container:)`).
    ///
    /// - maxBitrate: when set (bits/s), AAC at at most this bitrate, as HLS —
    ///   used on mobile data. The equaliser and visualiser don't run on HLS
    ///   streams (no audio track for the tap).
    public func url(for track: JellyfinTrack, maxBitrate: Int? = nil) -> URL? {
        url(trackId: track.id, container: track.mediaSource?.container,
            codec: track.mediaSource?.codecs, maxBitrate: maxBitrate)
    }

    /// The same from a track's id and its source file's container and codec
    /// (what a saved queue keeps).
    public func url(trackId: String, container: String?, codec: String?, maxBitrate: Int? = nil) -> URL? {
        let plan = Self.plan(codec: codec, container: container)
        if let maxBitrate {
            return cappedURL(trackId: trackId, container: container, codec: codec, plan: plan, bitrate: maxBitrate)
        }
        switch plan {
        case .original(let ext):
            return originalURL(trackId: trackId, fileExtension: ext)
        case .converted(let format):
            return convertedURL(trackId: trackId, container: container, format: format)
        }
    }

    /// A source AVPlayer can't play, converted to `format` (`.aac` at
    /// `convertedAACBitrate`, else ALAC) as HLS.
    func convertedURL(trackId: String, container: String?, format: AudioFormat) -> URL? {
        let codec = format == .aac ? "aac" : "alac"
        return hlsURL(trackId: trackId, codec: codec,
                      container: Self.hlsContainer(source: container, codec: codec),
                      maxStreamingBitrate: nil,
                      audioBitrate: format == .aac ? Self.convertedAACBitrate : nil)
    }

    /// Mobile data with the cap: AAC at most `bitrate`, as HLS. An AAC source
    /// is converted only when it's over the cap (the server's
    /// `ContainerBitrateExceedsLimit`; under it the server sends the file);
    /// any other source always (`AudioCodecNotSupported`).
    private func cappedURL(trackId: String, container: String?, codec: String?, plan: StreamPlan,
                           bitrate: Int) -> URL? {
        if case .original = plan, codec?.lowercased() == "aac" {
            return hlsURL(trackId: trackId, codec: "aac",
                          container: Self.containerTokens(container).joined(separator: ","),
                          maxStreamingBitrate: bitrate, audioBitrate: bitrate)
        }
        return hlsURL(trackId: trackId, codec: "aac", container: Self.hlsContainer(source: container, codec: "aac"),
                      maxStreamingBitrate: nil, audioBitrate: bitrate)
    }

    /// The source's container names (ffprobe's list split), or "m4a" if unknown.
    static func containerTokens(_ container: String?) -> [String] {
        let tokens = (container ?? "").lowercased().split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return tokens.isEmpty ? ["m4a"] : tokens
    }

    /// `Container=` for a conversion: the source's own container, restricted
    /// to the target codec ("mp3|aac", or "mov|aac,mp4|aac,…"). The server
    /// then has exactly one reason to convert, `AudioCodecNotSupported`.
    ///
    /// It must be one. With several (asking "m4a" of an MP3 gives
    /// "ContainerNotSupported, VideoCodecNotSupported, AudioCodecNotSupported")
    /// Jellyfin writes them, comma and space, into the variant playlist's
    /// URL, and AVPlayer's request for it gets HTTP 400 (CoreMedia -16845,
    /// shown as NSURLError -1008), though URLSession fetches the same URL
    /// fine. Seen against Jellyfin 12.4 in the iOS simulator.
    static func hlsContainer(source: String?, codec: String) -> String {
        containerTokens(source).map { "\($0)|\(codec)" }.joined(separator: ",")
    }

    /// `/Audio/{id}/stream.<ext>?static=true`: the original file, untouched,
    /// with byte ranges.
    private func originalURL(trackId: String, fileExtension: String) -> URL? {
        var query = [URLQueryItem(name: "static", value: "true")]
        if let token = api.accessToken { query.append(URLQueryItem(name: "ApiKey", value: token)) }
        return api.buildURL("Audio/\(trackId)/stream.\(fileExtension)", queryItems: query)
    }

    /// `/Audio/{id}/universal` converted to `codec` as HLS with fMP4
    /// segments (`TranscodingContainer=mp4`), which AVPlayer plays for AAC,
    /// ALAC and FLAC (checked against Jellyfin 12.4; FFmpeg 6+ puts FLAC
    /// and ALAC in fMP4 and the playlist carries their codec strings). ALAC
    /// is used for lossless as it's Apple's own lossless HLS codec.
    private func hlsURL(trackId: String, codec: String, container: String,
                        maxStreamingBitrate: Int?, audioBitrate: Int?) -> URL? {
        var query = [
            URLQueryItem(name: "UserId", value: userId),
            URLQueryItem(name: "DeviceId", value: api.deviceId),
            URLQueryItem(name: "Container", value: container),
            URLQueryItem(name: "TranscodingContainer", value: "mp4"),
            URLQueryItem(name: "TranscodingProtocol", value: "hls"),
            URLQueryItem(name: "AudioCodec", value: codec),
        ]
        if let maxStreamingBitrate {
            query.append(URLQueryItem(name: "MaxStreamingBitrate", value: String(maxStreamingBitrate)))
        }
        if let audioBitrate {
            query.append(URLQueryItem(name: "AudioBitRate", value: String(audioBitrate)))
        }
        if let token = api.accessToken { query.append(URLQueryItem(name: "ApiKey", value: token)) }
        return api.buildURL("Audio/\(trackId)/universal", queryItems: query)
    }

    // MARK: - Downloads

    /// The file to download for a track in `format` (from
    /// `AudioFormat.downloadFormat`): the original when it already is that
    /// format, in a container the saved file's extension describes; else
    /// converted by the server over plain HTTP (fine for URLSession, though
    /// it can't resume from a byte range).
    public func downloadURL(for track: JellyfinTrack, format: AudioFormat) -> URL? {
        let source = track.mediaSource
        if AudioFormat.source(of: track) == format,
           case .original(let ext) = Self.plan(codec: source?.codecs, container: source?.container),
           Self.extensions(for: format).contains(ext) {
            return originalURL(trackId: track.id, fileExtension: ext)
        }
        let container = Self.containerFor(format)
        var query = [
            URLQueryItem(name: "UserId", value: userId),
            URLQueryItem(name: "DeviceId", value: api.deviceId),
            URLQueryItem(name: "Container", value: container),
            URLQueryItem(name: "TranscodingContainer", value: container),
            URLQueryItem(name: "TranscodingProtocol", value: "http"),
        ]
        if let codec = Self.audioCodecFor(format) {
            query.append(URLQueryItem(name: "AudioCodec", value: codec))
        }
        if let token = api.accessToken { query.append(URLQueryItem(name: "ApiKey", value: token)) }
        return api.buildURL("Audio/\(track.id)/universal", queryItems: query)
    }

    /// Original-file extensions that a download saved as `format.fileExtension`
    /// can hold (an MP4-family file saved as .m4a still plays; AIFF saved as
    /// .wav, or ADTS AAC as .m4a, might not).
    static func extensions(for format: AudioFormat) -> Set<String> {
        switch format {
        case .flac: ["flac"]
        case .alac, .aac: ["m4a", "m4b", "mp4"]
        case .mp3: ["mp3"]
        case .wav: ["wav"]
        case .opus: []
        }
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

    /// Jellyfin's container name for a format (`Container=` on the
    /// universal endpoint).
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
