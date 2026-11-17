import SwiftUI

/// App-wide colour palette.
public extension Color {
    /// App background: #000B25 (R 0, G 11, B 37).
    static let appBackground = Color(red: 0 / 255, green: 11 / 255, blue: 37 / 255)
    /// Accent pink: #AA5CC3 (R 170, G 92, B 195).
    static let appPink = Color(red: 170 / 255, green: 92 / 255, blue: 195 / 255)
    /// Accent blue: #039FD6 (R 3, G 159, B 214).
    static let appBlue = Color(red: 3 / 255, green: 159 / 255, blue: 214 / 255)
    /// Content boxes over the background picture (Settings, Downloads,
    /// Equaliser groups, status banners): 50% black.
    static let appGroupBackground = Color.black.opacity(0.5)
    /// Artist name under album titles (Home, Library, Album, Now Playing): bright but a step
    /// below the title. The system's secondary grey was too faint.
    static let albumArtistText = Color.white.opacity(0.85)
    /// Release year under the artist: one step dimmer than the artist.
    static let albumYearText = Color.white.opacity(0.65)
}

/// The app's background, filling the screen. Shared by every screen.
///
/// While a track is loaded it's the PlexAmp-style "ultrablur": the current
/// album art full-bleed, heavily blurred and scaled up to hide the blurred
/// edges, with a dark gradient on top for legibility. Otherwise it's the
/// background picture (`AppBackground` in each app's asset catalog); navy
/// shows if the image is missing.
public struct AppBackgroundView: View {
    public init() {}

    private var session: AppSession { AppSession.shared }

    /// The current album art, once a track is loaded.
    private var artRequest: URLRequest? {
        guard let player = session.player, let track = player.currentTrack else { return nil }
        return player.artRequest(for: track, size: 600)
    }

    public var body: some View {
        // The aspect-filled images report their own (larger) size, which
        // would widen layouts; take the size from the colour and draw the
        // images as a clipped overlay.
        Color.appBackground
            .overlay {
                Image("AppBackground")
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
            .overlay {
                if let artRequest {
                    // Clear until the art loads, so the picture shows meanwhile.
                    AuthenticatedImage(request: artRequest)
                        .blur(radius: 60)
                        .scaleEffect(1.4, anchor: .center)
                    LinearGradient(
                        colors: [
                            Color.appBackground.opacity(0.7),
                            Color.appBackground.opacity(0.4),
                            Color.clear
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            }
            .animation(.easeInOut(duration: 0.4), value: artRequest?.url)
            .clipped()
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
}

public extension View {
    /// App theme: background picture, white text, dark chrome.
    func appTheme() -> some View {
        self
            .scrollContentBackground(.hidden)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(AppBackgroundView())
            .foregroundStyle(.white)
            .font(.plex(.body))   // text without its own font
            .preferredColorScheme(.dark)
    }
}
