// MARK: - AlbumArtView — authenticated async album art with placeholder

import SwiftUI

/// A reusable album/art thumbnail.
///
/// Loads the image via `AuthenticatedImage`, which sends the
/// `Authorization: MediaBrowser` header that Jellyfin requires. While
/// loading it shows Home's pink/blue loading outline; with no art, a
/// music-note placeholder.
///
/// The `request` should be built by `JellyfinAPI.imageURLRequest(for:size:)`.
public struct AlbumArtView: View {

    // MARK: Inputs

    /// The authenticated request for the image. `nil` shows the
    /// music-note placeholder.
    let request: URLRequest?
    /// Side length in points.
    let size: CGFloat
    /// Rounded corners.
    let cornerRadius: CGFloat
    /// Fill of the no-art placeholder (shown with a "music.note" symbol).
    var placeholderColor: Color = Color.gray.opacity(0.35)

    // MARK: Init

    public init(request: URLRequest?,
                size: CGFloat,
                cornerRadius: CGFloat = 10,
                placeholderColor: Color = Color.gray.opacity(0.35)) {
        self.request = request
        self.size = size
        self.cornerRadius = cornerRadius
        self.placeholderColor = placeholderColor
    }

    // MARK: Body

    public var body: some View {
        // Always a 1:1 square: the image is centre-cropped to fill, whatever
        // ratio the server returns.
        Color.clear
            .frame(width: size, height: size)
            .overlay {
                AuthenticatedImage(request: request) { phase in
                    switch phase {
                    case .loading:
                        // Same pink/blue chasing outline as Home's loading cards.
                        LoadingOutline(cornerRadius: cornerRadius)
                    case .failed:
                        placeholderArtwork
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            .frame(width: size, height: size)
    }

    // MARK: - Private

    /// No art: a tinted square with a music note.
    private var placeholderArtwork: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(placeholderColor)
            .overlay {
                Image(systemName: "music.note")
                    .font(.system(size: max(size * 0.28, 16)))
                    .foregroundStyle(.white.opacity(0.55))
            }
    }
}
