// MARK: - QuickConnectController — Quick Connect login with polling fallback

import Foundation
import Observation

/// Drives one Quick Connect login: starts the session, exposes the 6-digit
/// code, then polls `/QuickConnect/Connect` until the user approves it on another
/// device, it's rejected/expired, or a timeout is hit.
///
/// `@Observable @MainActor` so a SwiftUI view binds to `state` directly. The
/// poll loop is a repeating `Timer` (added to the main run loop in `.common`
/// mode so it keeps firing while the form is being scrolled); `cancel()`
/// invalidates it, e.g. when the user switches to the manual form or dismisses.
/// Mirrors the async style of `SearchViewModel`.
@Observable
@MainActor
public final class QuickConnectController {

    // MARK: State

    public enum State: Equatable {
        case idle
        case starting
        case pending(code: String)
        case failed(String)
    }

    /// UI-facing status. The view switches on this to render the code / errors.
    public private(set) var state: State = .idle

    /// Called once when a Quick Connect login succeeds, with a ready-to-use API.
    public var onConnected: ((JellyfinAPI) -> Void)?

    // MARK: Config

    private let authenticator: Authenticator
    private let pollInterval: TimeInterval
    private let maxAttempts: Int

    // MARK: Polling

    private var pollTimer: Timer?
    private var attempts = 0
    /// Moves on every `cancel()`, so a code that arrives after it is ignored.
    private var generation = 0

    // MARK: Init

    public init(
        authenticator: Authenticator = Authenticator(),
        pollInterval: TimeInterval = 2.0,
        maxAttempts: Int = 150  // 5 minutes at 2 s: time to switch devices and approve
    ) {
        self.authenticator = authenticator
        self.pollInterval = pollInterval
        self.maxAttempts = maxAttempts
    }

    // MARK: - Public

    /// Begin a Quick Connect login against `serverURL`: start the session,
    /// surface the code, then poll for approval.
    public func start(serverURL: URL) {
        cancel()
        attempts = 0
        state = .starting
        let started = generation
        authenticator.startQuickConnect(serverURL: serverURL) { [weak self] result in
            Task { @MainActor [weak self] in
                guard self?.generation == started else { return }
                switch result {
                case .success(let start):
                    self?.beginPolling(serverURL: serverURL, code: start.code ?? "", secret: start.secret ?? "")
                case .failure(let error):
                    self?.state = .failed(error.errorDescription ?? "Quick Connect failed.")
                }
            }
        }
    }

    /// Stop any in-flight polling. Safe to call from any state. Does not
    /// change `state` — the caller sets it as needed (e.g. switching to the
    /// manual form leaves the view's own mode in control).
    public func cancel() {
        generation += 1
        pollTimer?.invalidate()
        pollTimer = nil
        attempts = 0
    }

    /// Stops polling and forgets the code and any failure: back to idle (the address
    /// changed, the form switched, the screen went away). Each `start` leaves a pending
    /// approval request on the server, so none is left running unasked.
    public func reset() {
        cancel()
        state = .idle
    }

    // MARK: - Polling loop

    private func beginPolling(serverURL: URL, code: String, secret: String) {
        guard !code.isEmpty, !secret.isEmpty else {
            state = .failed("The server didn't return a login code.")
            return
        }
        state = .pending(code: code)
        attempts = 0
        // `.common` keeps the timer firing while the user scrolls the form.
        let timer = Timer(timeInterval: pollInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.pollOnce(serverURL: serverURL, secret: secret)
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
        // Fire the first poll immediately rather than waiting a full interval.
        pollOnce(serverURL: serverURL, secret: secret)
    }

    private func pollOnce(serverURL: URL, secret: String) {
        // A `cancel()` during a poll nils out the timer; ignore late results so
        // an abandoned login can't transition state or fire `onConnected`.
        guard pollTimer != nil else { return }
        attempts += 1
        if attempts > maxAttempts {
            state = .failed("The login didn't complete in time. Try again, or sign in manually.")
            return
        }
        authenticator.pollQuickConnect(serverURL: serverURL, secret: secret) { [weak self] result in
            DispatchQueue.main.async {
                guard let self, self.pollTimer != nil else { return }
                switch result {
                case .success(let poll):
                    if quickConnectStatus(from: poll) == .approved {
                        self.exchange(serverURL: serverURL, secret: secret)
                    }
                case .failure(let error):
                    self.cancel()
                    if case .quickConnectExpired = error {
                        self.state = .failed("The login code expired. Try again, or sign in manually.")
                    } else {
                        self.state = .failed(error.errorDescription ?? "Quick Connect failed.")
                    }
                }
            }
        }
    }

    private func exchange(serverURL: URL, secret: String) {
        // Stop polling first so the exchange only runs once.
        cancel()
        authenticator.completeQuickConnect(serverURL: serverURL, secret: secret) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(let auth):
                    if let token = auth.accessToken, let userId = auth.resolvedUserId {
                        self.succeed(serverURL: serverURL, token: token, userId: userId, userName: auth.user?.name)
                    } else {
                        self.state = .failed("The server returned an incomplete login.")
                    }
                case .failure(let error):
                    self.state = .failed(error.errorDescription ?? "Quick Connect failed.")
                }
            }
        }
    }

    private func succeed(serverURL: URL, token: String, userId: String, userName: String?) {
        cancel()
        guard let api = authenticator.storeQuickConnectSuccess(
            serverURL: serverURL, accessToken: token, userId: userId, userName: userName
        ) else {
            state = .failed("Signed in, but the session couldn't be started.")
            return
        }
        state = .idle
        onConnected?(api)
    }
}
