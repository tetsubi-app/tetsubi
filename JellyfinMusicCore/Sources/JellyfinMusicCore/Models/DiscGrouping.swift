// MARK: - DiscGrouping — show "Album (Disc 1)" + "Album (Disc 2)" as one album

import Foundation

/// Jellyfin lists each disc of some albums as a separate album, named e.g.
/// "Album (Disc 1)", "Album [CD2]" or "Album - Disc 2". These helpers
/// recognise those names and merge the discs into one album for display.
public enum DiscGrouping {

    /// "Album (Disc 2)" → ("Album", 2, nil). Matches disc / disk / cd, either
    /// in brackets — where a disc subtitle may follow, as in
    /// "Dracula X (Disc 1 Chii no Rondo)" → subtitle "Chii no Rondo" — or
    /// after a separator ("Album - CD 2"); optionally "of N" / "/N".
    /// `nil` for names without a trailing disc marker.
    public static func parse(_ name: String) -> (base: String, disc: Int, subtitle: String?)? {
        let range = NSRange(name.startIndex..., in: name)
        for regex in [bracketed, separated] {
            guard let match = regex.firstMatch(in: name, range: range),
                  let baseRange = Range(match.range(at: 1), in: name),
                  let discRange = Range(match.range(at: 2), in: name),
                  let disc = Int(name[discRange])
            else { continue }
            let base = name[baseRange].trimmingCharacters(in: .whitespaces)
            guard !base.isEmpty else { return nil }
            var subtitle: String?
            if match.numberOfRanges > 3, let r = Range(match.range(at: 3), in: name) {
                let text = name[r].trimmingCharacters(in: .whitespaces)
                subtitle = text.isEmpty ? nil : text
            }
            return (base, disc, subtitle)
        }
        return nil
    }

    private static let bracketed = try! NSRegularExpression(
        pattern: #"^(.*?)\s*[\(\[\{]\s*(?:disc|disk|cd)\s*\.?\s*(\d+)(?:\s*(?:of|/)\s*\d+)?(?:[\s:,\-–—]+([^\)\]\}]*?))?\s*[\)\]\}]\s*$"#,
        options: [.caseInsensitive]
    )

    private static let separated = try! NSRegularExpression(
        pattern: #"^(.*?)[\s\-–—:,]+\b(?:disc|disk|cd)\s*\.?\s*(\d+)(?:\s*(?:of|/)\s*\d+)?\s*$"#,
        options: [.caseInsensitive]
    )

    /// The disc's subtitle from its name, if any ("Chii no Rondo").
    public static func subtitle(of album: JellyfinAlbum) -> String? {
        parse(album.displayName)?.subtitle
    }

    /// Key for "same album": artist + base name, ignoring case and accents.
    static func key(artist: String, base: String) -> String {
        let fold: (String) -> String = {
            $0.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        }
        return fold(artist) + "\u{1F}" + fold(base)
    }

    /// Merges disc albums that appear in `albums` into one entry, at the
    /// position of the first disc seen. Other albums are untouched.
    ///
    /// The merged entry keeps the lowest disc's id (art, navigation), takes
    /// the base name ("Album"), and lists the discs in `discAlbums`. A disc
    /// on its own (e.g. only disc 2 in a random row) still gets the base name;
    /// the album page finds its other discs.
    public static func merge(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        var groups: [String: [JellyfinAlbum]] = [:]
        var order: [(key: String?, album: JellyfinAlbum)] = []
        for album in albums {
            guard let parsed = parse(album.displayName) else {
                order.append((nil, album))
                continue
            }
            let k = key(artist: album.artistName, base: parsed.base)
            if groups[k] == nil { order.append((k, album)) }
            if groups[k]?.contains(where: { $0.id == album.id }) != true {
                groups[k, default: []].append(album)
            }
        }
        return order.map { entry in
            guard let k = entry.key, let discs = groups[k] else { return entry.album }
            return combined(discs)
        }
    }

    /// One album standing for all of `discs` (each a single-disc album).
    public static func combined(_ discs: [JellyfinAlbum]) -> JellyfinAlbum {
        let sorted = discs.sorted { (discNumber(of: $0) ?? 0) < (discNumber(of: $1) ?? 0) }
        guard let first = sorted.first else { preconditionFailure("combined([]) has no discs") }
        let base = parse(first.displayName)?.base ?? first.displayName
        var album = first.renamed(base)
        album.discAlbums = sorted
        return album
    }

    /// The disc number in an album's own name, if it has one.
    public static func discNumber(of album: JellyfinAlbum) -> Int? {
        parse(album.displayName)?.disc
    }

    /// From an artist's albums, the discs that belong with `album`.
    /// Returns `[]` when `album` isn't a disc of a multi-disc set.
    public static func siblings(of album: JellyfinAlbum, among candidates: [JellyfinAlbum]) -> [JellyfinAlbum] {
        let own = album.discAlbums.first ?? album
        guard let parsed = parse(own.displayName) else { return [] }
        let k = key(artist: own.artistName, base: parsed.base)
        return candidates.filter { candidate in
            guard let p = parse(candidate.displayName) else { return false }
            return key(artist: candidate.artistName, base: p.base) == k
        }
    }

    /// Splits one album's tracks by their disc tag (`ParentIndexNumber`).
    /// Returns nil unless the tracks span more than one disc. Untagged
    /// tracks count as disc 1. Order within a disc is kept.
    public static func byDiscNumber(_ tracks: [JellyfinTrack]) -> [(number: Int, tracks: [JellyfinTrack])]? {
        let numbers = Set(tracks.map { $0.discNumber ?? 1 })
        guard numbers.count > 1 else { return nil }
        return numbers.sorted().map { n in (n, tracks.filter { ($0.discNumber ?? 1) == n }) }
    }
}

extension JellyfinAlbum {
    /// Whether this entry stands for more than one disc.
    public var isMultiDisc: Bool { discAlbums.count > 1 }

    /// The real albums behind this entry: its discs, or just itself.
    public var allDiscs: [JellyfinAlbum] { discAlbums.isEmpty ? [self] : discAlbums }

    /// A copy with a different display name (used for the merged entry).
    func renamed(_ newName: String) -> JellyfinAlbum {
        JellyfinAlbum(
            id: id, name: newName, serverId: serverId, premiereDate: premiereDate,
            productionYear: productionYear, albumArtists: albumArtists, album: album,
            imageTags: imageTags, runTimeTicks: runTimeTicks, type: type, userData: userData
        )
    }
}
