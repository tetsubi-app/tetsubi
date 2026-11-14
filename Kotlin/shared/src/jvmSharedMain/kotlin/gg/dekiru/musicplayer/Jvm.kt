package gg.dekiru.musicplayer

import gg.dekiru.musicplayer.data.artist.ArtistInfoCache
import gg.dekiru.musicplayer.data.artist.FileArtistInfoCache

internal actual fun artistInfoCache(cacheDir: String): ArtistInfoCache = FileArtistInfoCache(cacheDir)
