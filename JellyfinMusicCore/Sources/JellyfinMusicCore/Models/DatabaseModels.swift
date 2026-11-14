// MARK: - Swift Data models

import Foundation
import SwiftData

// MARK: - ServerCredential

@Model
public final class ServerCredential {
    /// Stable, unique-per-device UUID — sent as `DeviceId` header in all requests.
    @Attribute(.unique) public var deviceId: UUID

    /// Jellyfin server base URL, e.g. http://192.168.1.5:8096
    public var serverURL: String

    /// Jellyfin username — used only for display and re-auth
    public var username: String

    /// Jellyfin user ID — returned at login, used for all subsequent /Users/{id}/ calls
    public var userId: String

    /// When this credential was last successfully authenticated
    public var lastSync: Date?

    /// When the credential was first created
    public var createdAt: Date

    /// JWT access token — stored in Keychain, NOT in this model.
    /// This field is `nil` by design; the token lives in `KeychainManager`.
    public var jwtToken: String?

    /// Server version string — fetched at login for display
    public var serverVersion: String?

    public init(
        serverURL: String,
        username: String,
        userId: String,
        deviceId: UUID = UUID()
    ) {
        self.serverURL = serverURL
        self.username = username
        self.userId = userId
        self.deviceId = deviceId
        self.lastSync = Date()
        self.createdAt = Date()
        self.jwtToken = nil
        self.serverVersion = nil
    }
}

// MARK: - DownloadStatus

public enum DownloadStatus: String, Codable, Sendable {
    case pending
    case downloading
    case paused
    case downloaded
    case failed
    case canceling
    case canceled
}

// MARK: - DownloadedAlbum

@Model
public final class DownloadedAlbum {
    public var id: UUID
    public var albumID: String       // Jellyfin item ID
    public var title: String
    public var artistName: String
    public var releaseYear: Int?

    public var downloadedAt: Date
    public var storagePath: String   // Absolute path to album directory on disk

    /// Total size in bytes
    public var totalBytes: Int64

    // NOTE: no @Relationship back to [DownloadTask] — to avoid the macro
    // expansion circular reference.  Query DownloadedAlbum separately when
    // needing the list of tasks for it.
    public init(
        albumID: String,
        title: String,
        artistName: String,
        storagePath: String,
        releaseYear: Int? = nil,
        downloadedAt: Date = Date(),
        totalBytes: Int64 = 0
    ) {
        self.id = UUID()
        self.albumID = albumID
        self.title = title
        self.artistName = artistName
        self.releaseYear = releaseYear
        self.downloadedAt = downloadedAt
        self.storagePath = storagePath
        self.totalBytes = totalBytes
    }
}

// MARK: - DownloadTask

@Model
public final class DownloadTask {
    public var id: UUID
    public var albumID: String       // Jellyfin album ID — not a SwiftData relationship

    public var formatRaw: String     // audio format raw value
    public var statusRaw: String     // DownloadStatus raw value
    public var progress: Double      // 0.0–1.0
    public var startedAt: Date
    public var completedAt: Date?

    /// Error message if .failed
    public var errorMessage: String?

    public init(
        id: UUID = UUID(),
        albumID: String,
        format: AudioFormat = .flac,
        status: DownloadStatus = .pending,
        progress: Double = 0.0,
        startedAt: Date = Date(),
        completedAt: Date? = nil,
        errorMessage: String? = nil
    ) {
        self.id = id
        self.albumID = albumID
        self.formatRaw = format.rawValue
        self.statusRaw = status.rawValue
        self.progress = progress
        self.startedAt = startedAt
        self.completedAt = completedAt
        self.errorMessage = errorMessage
    }

    var status: DownloadStatus {
        get { DownloadStatus(rawValue: statusRaw) ?? .pending }
        set { statusRaw = newValue.rawValue }
    }

    var format: AudioFormat {
        get { AudioFormat(rawValue: formatRaw) ?? .flac }
        set { formatRaw = newValue.rawValue }
    }
}

// MARK: - PlaybackHistoryEntry

@Model
public final class PlaybackHistoryEntry {
    public var id: UUID
    public var trackID: String        // Jellyfin item ID

    public var title: String
    public var artist: String
    public var albumTitle: String

    public var playedAt: Date
    public var duration: TimeInterval  // total duration
    public var positionAtEnd: Double   // where user stopped (for resume)

    public init(
        trackID: String,
        title: String,
        artist: String,
        albumTitle: String,
        playedAt: Date = Date(),
        duration: TimeInterval,
        positionAtEnd: Double
    ) {
        self.id = UUID()
        self.trackID = trackID
        self.title = title
        self.artist = artist
        self.albumTitle = albumTitle
        self.playedAt = playedAt
        self.duration = duration
        self.positionAtEnd = positionAtEnd
    }
}
