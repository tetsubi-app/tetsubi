// MARK: - AuthenticatedImage — authenticated async image loader

import SwiftUI
import ImageIO
import CryptoKit

/// On-disk cache of the original image bytes (in the system Caches folder),
/// keyed by a hash of the request URL. Survives relaunches and works offline.
/// Oldest files are evicted once the folder exceeds `byteLimit`.
private final class ImageDiskCache: @unchecked Sendable {
    static let shared = ImageDiskCache()
    private let byteLimit = 200 * 1024 * 1024
    private let queue = DispatchQueue(label: "ImageDiskCache", qos: .utility)
    private let directory: URL

    private init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        directory = base.appendingPathComponent("AlbumArt", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func fileURL(forKey key: String) -> URL {
        let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(hash)
    }

    func data(forKey key: String) -> Data? {
        let url = fileURL(forKey: key)
        guard let data = try? Data(contentsOf: url) else { return nil }
        // Touch so recently used art is evicted last.
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return data
    }

    func store(_ data: Data, forKey key: String) {
        let url = fileURL(forKey: key)
        queue.async { [self] in
            try? data.write(to: url, options: .atomic)
            trim()
        }
    }

    func clear() {
        queue.sync {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    func totalBytes() -> Int {
        queue.sync {
            let files = (try? FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            return files.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        }
    }

    private func trim() {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: keys) else { return }
        var entries = files.compactMap { url -> (URL, Date, Int)? in
            guard let v = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, v.contentModificationDate ?? .distantPast, v.fileSize ?? 0)
        }
        var total = entries.reduce(0) { $0 + $1.2 }
        guard total > byteLimit else { return }
        entries.sort { $0.1 < $1.1 }
        for entry in entries where total > byteLimit * 3 / 4 {
            try? FileManager.default.removeItem(at: entry.0)
            total -= entry.2
        }
    }
}

/// In-memory cache of decoded, downsampled images keyed by request URL.
/// Uses `NSCache` which auto-evicts under memory pressure. Capped by
/// decoded size too, since a count alone allows ~1 GB of 1200 px art.
private final class ImageDataCache: @unchecked Sendable {
    static let shared = ImageDataCache()
    private let nsCache = NSCache<NSString, CGImage>()
    private init() {
        nsCache.countLimit = 200
        nsCache.totalCostLimit = 128 * 1024 * 1024
    }

    func image(forKey key: String) -> CGImage? {
        nsCache.object(forKey: key as NSString)
    }

    func removeAll() { nsCache.removeAllObjects() }

    func store(_ image: CGImage, forKey key: String) {
        nsCache.setObject(image, forKey: key as NSString, cost: image.bytesPerRow * image.height)
    }
}

/// What `AuthenticatedImage` shows before (or instead of) the image.
public enum AuthenticatedImagePhase: Sendable {
    /// Still fetching.
    case loading
    /// No request, or the image couldn't be fetched or decoded.
    case failed
}

/// Loads an image from a `URLRequest` that carries an `Authorization`
/// header — something SwiftUI's built-in `AsyncImage(url:)` cannot do.
///
/// Used by `AlbumArtView` (framed, with a loading/failed placeholder) and by
/// `AppBackgroundView`'s blurred album art (full-bleed, no frame).
///
/// Until the image is ready it shows `placeholder(phase)`; by default that's
/// `Color.clear`, so whatever is behind shows through.
public struct AuthenticatedImage<Placeholder: View>: View {

    // MARK: Inputs

    /// The request to load. Must have the `Authorization` header set
    /// (built by `JellyfinAPI.imageURLRequest`). `nil` shows the failed placeholder.
    let request: URLRequest?
    let placeholder: (AuthenticatedImagePhase) -> Placeholder

    // MARK: State

    @State private var cgImage: CGImage?
    @State private var failed = false

    // MARK: Init

    public init(request: URLRequest?,
                @ViewBuilder placeholder: @escaping (AuthenticatedImagePhase) -> Placeholder) {
        self.request = request
        self.placeholder = placeholder
        // Already in memory: show it on the first frame, with no placeholder flash.
        _cgImage = State(initialValue: request.flatMap(ImageCache.cached))
    }

    // MARK: Body

    public var body: some View {
        content
            .task(id: request?.url?.absoluteString) {
                await load()
            }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if let cgImage {
            Image(decorative: cgImage, scale: 1)
                .resizable()
                .aspectRatio(contentMode: .fill)
        } else {
            placeholder(failed || request == nil ? .failed : .loading)
        }
    }

    // MARK: Loading

    private func load() async {
        guard let request else { return }
        if let cached = ImageCache.cached(request) {
            cgImage = cached
            failed = false
            return
        }
        cgImage = nil
        failed = false
        if let image = await ImageCache.load(request) {
            cgImage = image
        } else if !Task.isCancelled {
            failed = true
        }
    }
}

public extension AuthenticatedImage where Placeholder == Color {
    /// Shows nothing (clear) until the image loads.
    init(request: URLRequest?) {
        self.init(request: request) { _ in Color.clear }
    }
}

/// Longest edge, in pixels, that a decoded image is limited to. Keeps huge
/// source art from producing oversized layers.
private let maxImagePixelSize = 1200

/// Decodes `data` to a CGImage no larger than `maxImagePixelSize` on its longest edge.
private func downsampledImage(_ data: Data) -> CGImage? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceThumbnailMaxPixelSize: maxImagePixelSize
    ]
    return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
}

/// Public controls for the album-art cache (memory + disk).
public enum ImageCache {
    /// Deletes all cached album art. Images already on screen stay until reloaded.
    public static func clear() {
        ImageDataCache.shared.removeAll()
        ImageDiskCache.shared.clear()
    }

    /// The image if it's already decoded in memory; no disk or network.
    public static func cached(_ request: URLRequest) -> CGImage? {
        ImageDataCache.shared.image(forKey: request.url?.absoluteString ?? "")
    }

    /// Size of the on-disk cache in bytes.
    public static func diskUsage() -> Int { ImageDiskCache.shared.totalBytes() }

    /// Loads an image for an authenticated request: memory cache, then disk
    /// cache, then the network (storing it in both). Downsampled to at most
    /// 1200 px. `nil` on failure. Shared by `AuthenticatedImage` and CarPlay.
    public static func load(_ request: URLRequest) async -> CGImage? {
        let key = request.url?.absoluteString ?? ""

        if let cached = ImageDataCache.shared.image(forKey: key) {
            return cached
        }

        // Disk cache next: decode off the main thread, no network.
        if let data = ImageDiskCache.shared.data(forKey: key),
           let decoded = await Task.detached(priority: .userInitiated, operation: {
               downsampledImage(data)
           }).value {
            ImageDataCache.shared.store(decoded, forKey: key)
            return decoded
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                print("[AuthenticatedImage] \(http.statusCode) for \(key)")
                return nil
            }
            guard let decoded = await Task.detached(priority: .userInitiated, operation: {
                downsampledImage(data)
            }).value else {
                print("[AuthenticatedImage] Failed to decode image for \(key) — \(data.count) bytes")
                return nil
            }
            // Files on disk (downloaded track pictures) aren't copied to the cache.
            if request.url?.isFileURL != true { ImageDiskCache.shared.store(data, forKey: key) }
            ImageDataCache.shared.store(decoded, forKey: key)
            return decoded
        } catch {
            print("[AuthenticatedImage] Error loading \(key): \(error)")
            return nil
        }
    }
}
