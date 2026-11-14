// MARK: - AudioSession — configures AVAudioSession for background playback

import Foundation
import AVFoundation
#if canImport(UIKit)
import UIKit
#endif

/// Configures `AVAudioSession` for background audio playback on iOS,
/// and is a no-op on macOS (which has no `AVAudioSession` class).
///
/// `PlayerManager` creates one and calls `configureForPlayback()` on
/// startup. For iOS it sets the `.playback` category (audio continues
/// after the app backgrounds; not mixable, so Tetsubi is the app the lock
/// screen and Control Center show) and `setActive(true)`.
///
/// Interruptions (calls, Siri, other apps) and a device going away
/// (headphones unplugged) are passed to the player through the `on…`
/// callbacks, so its state and the lock screen stay right.
public final class AudioSession {

    public enum State: Equatable, Sendable {
        case unconfigured
        case configured
        case interrupted
    }

    /// The shared, observable state — the View layer can show
    /// "audio suspended by phone call" if desired.
    public private(set) var state: State = .unconfigured
    public var onChange: () -> Void = { }

    #if os(iOS)
    private var session: AVAudioSession?
    private var interruptionObserver: NSObjectProtocol?
    private var endInterruptionObserver: NSObjectProtocol?
    #endif

    public init() {}

    // MARK: - Events for the player

    /// A call, Siri, an alarm or another app's audio took over: iOS has
    /// already paused our audio.
    public var onInterruptionBegan: () -> Void = { }
    /// The interruption is over. `shouldResume`: iOS suggests carrying on
    /// (it says so after calls and Siri, not after another app's music).
    public var onInterruptionEnded: (_ shouldResume: Bool) -> Void = { _ in }
    /// Headphones unplugged or a Bluetooth device went away: the audio
    /// would otherwise come out of the speaker, so playback pauses (Apple's
    /// guideline; AVPlayer has already paused).
    public var onOutputDeviceRemoved: () -> Void = { }

    // MARK: - Configuration

    /// Configure the session for background playback. Safe to call again:
    /// the observers are only installed once.
    ///
    /// It used to run again on every route change and every interruption,
    /// re-activating the session mid-playback and adding another set of
    /// observers each time (which then all ran on the next change). That
    /// could make iOS stop treating Tetsubi as the app that's playing, so the
    /// lock screen and Control Center player went away.
    public func configureForPlayback() {
        #if os(iOS)
        let s = AVAudioSession.sharedInstance()
        self.session = s
        do {
            try s.setCategory(.playback, mode: .default, options: [])
            try s.setActive(true, options: [])
            state = .configured
            onChange()
        } catch {
            print("[AudioSession] configureForPlayback failed: \(error)")
        }
        installObserversOnce()
        #else
        state = .configured
        onChange()
        #endif
    }

    /// Deactivate the session (call on stop / logout).
    public func deactivate() {
        #if os(iOS)
        if let s = session {
            do { try s.setActive(false, options: []) } catch {
                print("[AudioSession] deactivate failed: \(error)")
            }
        }
        state = .unconfigured
        onChange()
        #else
        state = .unconfigured
        onChange()
        #endif
    }

    // MARK: - Interruptions and route changes

    #if os(iOS)
    private func installObserversOnce() {
        guard interruptionObserver == nil else { return }
        let center = NotificationCenter.default
        interruptionObserver = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            guard let self,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            switch type {
            case .began:
                self.state = .interrupted
                self.onChange()
                self.onInterruptionBegan()
            case .ended:
                // Take the audio back; the player decides whether to play.
                try? AVAudioSession.sharedInstance().setActive(true, options: [])
                self.state = .configured
                self.onChange()
                let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                    .map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
                self.onInterruptionEnded(options.contains(.shouldResume))
            @unknown default:
                break
            }
        }
        endInterruptionObserver = center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            // Only a device going away matters; other route changes (a new
            // device, AirPlay, the category being set) need nothing.
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            self?.onOutputDeviceRemoved()
        }
    }
    #endif
}
