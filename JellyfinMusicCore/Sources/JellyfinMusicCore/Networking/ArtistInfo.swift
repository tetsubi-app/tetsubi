// MARK: - ArtistInfo — "About <artist>" from MusicBrainz (+ Wikipedia)

import Foundation

/// What the album page shows under "About <artist>".
///
/// Facts (type, origin, years, genres) come from MusicBrainz. MusicBrainz
/// has no biographies, so the summary is the Wikipedia introduction that
/// MusicBrainz links to (via Wikidata).
public struct ArtistInfo: Codable, Sendable, Equatable {
    public var name: String
    /// MusicBrainz type: "Group", "Person", "Orchestra", …
    public var type: String?
    public var disambiguation: String?
    /// Where they're from, e.g. "Cleveland, United States".
    public var origin: String?
    public var begin: String?
    public var end: String?
    public var ended: Bool
    /// Most-voted genres first, at most five.
    public var genres: [String]
    public var summary: String?
    public var wikipediaURL: URL?
    public var musicBrainzURL: URL
    public var fetched: Date

    /// One line, e.g. "Group · Cleveland, United States · 1988–present".
    public var facts: String? {
        var parts: [String] = []
        if let type { parts.append(type) }
        if let origin { parts.append(origin) }
        if let years = Self.years(begin: begin, end: end, ended: ended) { parts.append(years) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// "1988–present", "1988–2003", or nil without a start year.
    static func years(begin: String?, end: String?, ended: Bool) -> String? {
        guard let start = begin?.prefix(4), start.count == 4 else { return nil }
        if let finish = end?.prefix(4), finish.count == 4 {
            return finish == start ? String(start) : "\(start)–\(finish)"
        }
        return ended ? String(start) : "\(start)–present"
    }
}

// MARK: - MusicBrainz responses

struct MBSearchResponse: Decodable {
    struct Artist: Decodable { let id: String; let name: String; let score: Int? }
    let artists: [Artist]
}

struct MBArtist: Decodable {
    struct Area: Decodable { let name: String }
    struct LifeSpan: Decodable { let begin: String?; let end: String?; let ended: Bool? }
    struct Genre: Decodable { let name: String; let count: Int? }
    struct Relation: Decodable {
        struct URLResource: Decodable { let resource: String }
        let type: String
        let url: URLResource?
    }
    let id: String
    let name: String
    let type: String?
    let disambiguation: String?
    let area: Area?
    let beginArea: Area?
    let lifeSpan: LifeSpan?
    let genres: [Genre]?
    let relations: [Relation]?

    enum CodingKeys: String, CodingKey {
        case id, name, type, disambiguation, area, genres, relations
        case beginArea = "begin-area"
        case lifeSpan = "life-span"
    }
}

// MARK: - Client

/// Fetches and caches artist info. Follows MusicBrainz's rules: an
/// identifying User-Agent and no more than one request a second.
public actor ArtistInfoClient {

    public static let shared = ArtistInfoClient()

    static let userAgent = "Tetsubi/1.0 ( https://tetsubi.app )"
    /// Re-fetch after this long.
    static let maxAge: TimeInterval = 30 * 24 * 3600

    private var nextMusicBrainzSlot = Date.distantPast
    private var memory: [String: ArtistInfo] = [:]
    /// Lookups that definitely found nothing this run (not saved, so
    /// they're retried next launch). Network failures aren't recorded.
    private var misses: Set<String> = []
    /// Set when a request failed (offline, server busy) during a fetch.
    private var requestFailed = false
    private let session: URLSession
    private let cacheDir: URL

    init(session: URLSession = .shared,
         cacheDir: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ArtistInfo", isDirectory: true)) {
        self.session = session
        self.cacheDir = cacheDir
    }

    /// Info for an artist, by MusicBrainz ID when known (exact), else by
    /// name (only a confident match). Nil when nothing's found or offline.
    public func info(name: String, musicBrainzID: String?) async -> ArtistInfo? {
        let key = musicBrainzID ?? "name-" + name.lowercased()
        if let hit = memory[key] { return hit }
        if misses.contains(key) { return nil }
        if let saved = loadCached(key) {
            memory[key] = saved
            return saved
        }
        requestFailed = false
        guard let info = await fetch(name: name, musicBrainzID: musicBrainzID) else {
            if !requestFailed { misses.insert(key) }
            return nil
        }
        memory[key] = info
        saveCached(info, key: key)
        return info
    }

    private func fetch(name: String, musicBrainzID: String?) async -> ArtistInfo? {
        var mbid = musicBrainzID
        if mbid == nil {
            let quoted = name.replacingOccurrences(of: "\"", with: "\\\"")
            var comps = URLComponents(string: "https://musicbrainz.org/ws/2/artist")!
            comps.queryItems = [URLQueryItem(name: "query", value: "artist:\"\(quoted)\""),
                                URLQueryItem(name: "fmt", value: "json"),
                                URLQueryItem(name: "limit", value: "5")]
            guard let found: MBSearchResponse = await musicBrainz(comps.url!) else { return nil }
            mbid = Self.bestMatch(for: name, in: found.artists)
        }
        guard let mbid,
              let url = URL(string: "https://musicbrainz.org/ws/2/artist/\(mbid)?inc=url-rels+genres&fmt=json"),
              let artist: MBArtist = await musicBrainz(url) else { return nil }

        var info = Self.info(from: artist)
        if let title = await wikipediaTitle(relations: artist.relations ?? []) {
            (info.summary, info.wikipediaURL) = await wikipediaSummary(title: title)
        }
        return info
    }

    // MARK: Pure helpers (tested)

    /// The search result to trust: the same name (ignoring case) with a
    /// high score. Never a guess.
    static func bestMatch(for name: String, in artists: [MBSearchResponse.Artist]) -> String? {
        artists.first {
            $0.name.compare(name, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
                && ($0.score ?? 0) >= 90
        }?.id
    }

    static func info(from a: MBArtist) -> ArtistInfo {
        let places = [a.beginArea?.name, a.area?.name].compactMap { $0 }
        let origin = places.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        let genres = (a.genres ?? [])
            .sorted { ($0.count ?? 0) > ($1.count ?? 0) }
            .prefix(5)
            .map(\.name)
        return ArtistInfo(
            name: a.name,
            type: a.type,
            disambiguation: (a.disambiguation?.isEmpty ?? true) ? nil : a.disambiguation,
            origin: origin.isEmpty ? nil : origin.joined(separator: ", "),
            begin: a.lifeSpan?.begin,
            end: a.lifeSpan?.end,
            ended: a.lifeSpan?.ended ?? false,
            genres: Array(genres),
            summary: nil,
            wikipediaURL: nil,
            musicBrainzURL: URL(string: "https://musicbrainz.org/artist/\(a.id)")!,
            fetched: Date())
    }

    /// Wikidata ID ("Q11647") from MusicBrainz relations.
    static func wikidataID(in relations: [MBArtist.Relation]) -> String? {
        relations.first { $0.type == "wikidata" }?.url?.resource
            .split(separator: "/").last.map(String.init)
    }

    /// English Wikipedia title from an old-style direct Wikipedia link.
    static func wikipediaTitleFromLink(in relations: [MBArtist.Relation]) -> String? {
        guard let link = relations.first(where: {
            $0.type == "wikipedia" && ($0.url?.resource.contains("en.wikipedia.org") ?? false)
        })?.url?.resource else { return nil }
        return link.components(separatedBy: "/wiki/").last?.removingPercentEncoding
    }

    // MARK: Network

    private func wikipediaTitle(relations: [MBArtist.Relation]) async -> String? {
        if let q = Self.wikidataID(in: relations),
           let url = URL(string: "https://www.wikidata.org/wiki/Special:EntityData/\(q).json"),
           let data = await get(url),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let entity = (json["entities"] as? [String: Any])?.values.first as? [String: Any],
           let enwiki = (entity["sitelinks"] as? [String: Any])?["enwiki"] as? [String: Any],
           let title = enwiki["title"] as? String {
            return title
        }
        return Self.wikipediaTitleFromLink(in: relations)
    }

    private func wikipediaSummary(title: String) async -> (String?, URL?) {
        let path = title.replacingOccurrences(of: " ", with: "_")
            .addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?")))
        guard let path,
              let url = URL(string: "https://en.wikipedia.org/api/rest_v1/page/summary/\(path)"),
              let data = await get(url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["type"] as? String != "disambiguation",
              let extract = json["extract"] as? String, !extract.isEmpty else { return (nil, nil) }
        let page = ((json["content_urls"] as? [String: Any])?["desktop"] as? [String: Any])?["page"] as? String
        return (extract, page.flatMap(URL.init(string:)))
    }

    /// A MusicBrainz request, spaced at least 1.1 s from the last one.
    /// "Busy" (503, its rate limit) is retried once after a pause.
    private func musicBrainz<T: Decodable>(_ url: URL) async -> T? {
        for attempt in 0..<2 {
            let now = Date()
            let slot = max(now, nextMusicBrainzSlot)
            nextMusicBrainzSlot = slot.addingTimeInterval(attempt == 0 ? 1.1 : 3)
            if slot > now { try? await Task.sleep(for: .seconds(slot.timeIntervalSince(now))) }
            let (data, status) = await fetchData(url)
            if status == 503, attempt == 0 {
                nextMusicBrainzSlot = Date().addingTimeInterval(2)
                continue
            }
            guard let data else { return nil }
            return try? JSONDecoder().decode(T.self, from: data)
        }
        return nil
    }

    private func get(_ url: URL) async -> Data? {
        await fetchData(url).data
    }

    /// Data for a 200 response. Anything other than 200 or 404 counts as a
    /// failed request (so "not found" isn't remembered).
    private func fetchData(_ url: URL) async -> (data: Data?, status: Int) {
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await session.data(for: request) else {
            requestFailed = true
            return (nil, 0)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status != 200 && status != 404 { requestFailed = true }
        return (status == 200 ? data : nil, status)
    }

    // MARK: Disk cache

    private func file(_ key: String) -> URL {
        let safe = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key
        return cacheDir.appendingPathComponent(safe + ".json")
    }

    private func loadCached(_ key: String) -> ArtistInfo? {
        guard let data = try? Data(contentsOf: file(key)),
              let info = try? JSONDecoder().decode(ArtistInfo.self, from: data),
              Date().timeIntervalSince(info.fetched) < Self.maxAge else { return nil }
        return info
    }

    private func saveCached(_ info: ArtistInfo, key: String) {
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(info) { try? data.write(to: file(key)) }
    }
}
