// MARK: - NowPlayingController — MPNowPlayingInfoCenter + MPRemoteCommandCenter

import Foundation
import MediaPlayer
import ImageIO
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
#endif

/// Drives `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter` so the
/// lock screen, Control Center, and (on iOS) Siri see the current
/// track with working play / pause / next / previous / seek controls.
///
/// The controller owns:
///   - the static metadata for the *current* track (set on every
///     `update(track:)` call — title / artist / album / duration)
///   - the progress for the now-playing centre (`elapsedTime`, etc.)
///   - the remote-command callbacks (installed once, owned by
///     `PlayerManager`)
///
/// `PlayerManager` is the only legitimate caller.
@MainActor
public final class NowPlayingController {

    // MARK: Public API

    /// Update the centre with metadata for a new track. Pass `nil` to
    /// reset after a stop.
    /// Artwork comes from `savedArtwork` (a downloaded album's cover file)
    /// when there is one, else `artworkRequest` through the app's image
    /// cache (memory, disk, then network), so it also shows offline.
    public func update(track: PlaybackTrack?, artworkRequest: URLRequest? = nil,
                       savedArtwork: URL? = nil) {
        artworkTask?.cancel()
        artworkTask = nil
        guard let track else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle:             track.title,
            MPMediaItemPropertyArtist:            track.artist,
            MPMediaItemPropertyAlbumTitle:        track.album,
            MPMediaItemPropertyPlaybackDuration:  track.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.0,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        info[MPMediaItemPropertyArtwork] = makePlaceholderArtwork()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        applyPlaybackState()

        guard savedArtwork != nil || artworkRequest != nil else { return }
        let trackID = track.id
        artworkTask = Task { [weak self] in
            var cgImage: CGImage?
            if let savedArtwork,
               let data = try? Data(contentsOf: savedArtwork),
               let source = CGImageSourceCreateWithData(data as CFData, nil) {
                cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil)
            }
            if cgImage == nil, let artworkRequest {
                cgImage = await ImageCache.load(artworkRequest)
            }
            guard let cgImage, !Task.isCancelled else { return }
            #if canImport(UIKit)
            let image = UIImage(cgImage: cgImage)
            #else
            let image = NSImage(cgImage: cgImage, size: CGSize(width: cgImage.width, height: cgImage.height))
            #endif
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self?.applyArtwork(artwork, forTrack: trackID, title: track.title)
        }
    }

    private var artworkTask: Task<Void, Never>?

    private func applyArtwork(_ artwork: MPMediaItemArtwork, forTrack id: String, title: String) {
        let center = MPNowPlayingInfoCenter.default()
        guard var info = center.nowPlayingInfo,
              info[MPMediaItemPropertyTitle] as? String == title else { return }
        info[MPMediaItemPropertyArtwork] = artwork
        center.nowPlayingInfo = info
    }

    /// Whether playback is running, as last reported by `updateState`.
    private var isPlaying = false

    /// Update playback state — `isPlaying` and `elapsedTime`. The system
    /// works out the position between updates from the rate (1 playing,
    /// 0 paused). These keys used to be made-up strings ("IsPlaying",
    /// "ElapsedPlaybackTime") that the system ignored.
    public func updateState(isPlaying: Bool, elapsedTime: TimeInterval) {
        self.isPlaying = isPlaying
        applyPlaybackState()
        let center = MPNowPlayingInfoCenter.default()
        guard var info = center.nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        center.nowPlayingInfo = info
    }

    /// Clear the centre (on stop / logout).
    public func clear() {
        isPlaying = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        #if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = .stopped
        #endif
    }

    /// macOS decides which app gets the keyboard's media keys (and the menu
    /// bar's Now Playing) from `playbackState`; iOS works it out from the
    /// audio session, so this only matters on the Mac.
    private func applyPlaybackState() {
        #if os(macOS)
        let center = MPNowPlayingInfoCenter.default()
        center.playbackState = center.nowPlayingInfo == nil ? .stopped : (isPlaying ? .playing : .paused)
        #endif
    }

    // MARK: Remote command handlers
    //
    // `PlayerManager` installs these closures once. The controller
    // forwards user actions (play / pause / next / previous / seek) to
    // the closures; the manager updates itself and the centre.

    public var onPlay:      () -> Void = { }
    public var onPause:     () -> Void = { }
    /// The single play/pause key: the Mac keyboard's ⏯ key, headphone clicks.
    public var onTogglePlayPause: () -> Void = { }
    public var onNext:      () -> Void = { }
    public var onPrevious:  () -> Void = { }
    public var onSeekTo:    (TimeInterval) -> Void = { _ in }
    public var onStart:     (TimeInterval) -> Void = { _ in }

    /// Maniac Mode: grey out next / previous / skip and the scrubber on the
    /// lock screen, Control Center, headphones and CarPlay.
    public func setSkipControlsEnabled(_ enabled: Bool) {
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = enabled
        center.previousTrackCommand.isEnabled = enabled
        center.skipForwardCommand.isEnabled = enabled
        center.skipBackwardCommand.isEnabled = enabled
        center.changePlaybackPositionCommand.isEnabled = enabled
    }

    /// Register handlers on the default `MPRemoteCommandCenter`.
    /// Call once at `PlayerManager` init.
    public func registerRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            self?.onPlay()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.onPause()
            return .success
        }
        // The keyboard's play/pause key sends this, not play or pause.
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.onTogglePlayPause()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.onNext()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.onPrevious()
            return .success
        }
        // The changePlaybackPositionCommand is non-optional on iOS /
        // tvOS / watchOS / macOS 14+. We register with an event cast
        // to `MPChangePlaybackPositionCommandEvent` (the proper event
        // type for this command).
        {
            let change = center.changePlaybackPositionCommand
            change.addTarget { [weak self] event in
                guard let ev = event as? MPChangePlaybackPositionCommandEvent else {
                    return .commandFailed
                }
                self?.onSeekTo(ev.positionTime)
                return .success
            }
        }()
        center.skipForwardCommand.addTarget { [weak self] _ in
            self?.onStart(0)
            return .success
        }
        center.skipBackwardCommand.addTarget { [weak self] _ in
            self?.onStart(0)
            return .success
        }
    }

    // MARK: - Private helpers

    /// A small fixed-color placeholder so the now-playing centre shows
    /// *something* for each track before real album art is loaded.
    ///
    /// Phase 3.5+ will replace this with the real album art, fetched
    /// from `JellyfinAPI.albumArtURL(for:album:)`.
    private func makePlaceholderArtwork() -> MPMediaItemArtwork {
        #if canImport(UIKit)
        let size = CGSize(width: 300, height: 300)
        let artwork = MPMediaItemArtwork(boundsSize: size) { _ in
            // Render a tinted placeholder on the main thread via a
            // background-rendering call, since MPMediaItemArtwork's
            // requestHandler is called on an unknown queue.
            UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
            defer { UIGraphicsEndImageContext() }
            if let ctx = UIGraphicsGetCurrentContext() {
                UIColor.systemGray4.setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
            }
            return UIGraphicsGetImageFromCurrentImageContext() ?? UIImage()
        }
        return artwork
        #else
        // macOS: hand back a fixed-size NSImage-based artwork. The
        // system shows its own default art if we return nil.
        return MPMediaItemArtwork(boundsSize: CGSize(width: 300, height: 300)) { _ in
            let img = NSImage(size: CGSize(width: 300, height: 300))
            img.lockFocus()
            NSColor(white: 0.55, alpha: 1).setFill()
            NSBezierPath(rect: NSRect(x: 0, y: 0, width: 300, height: 300)).fill()
            img.unlockFocus()
            return img
        }
        #endif
    }
}