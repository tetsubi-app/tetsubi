package gg.dekiru.musicplayer.data.models

/** Cleans track names that are really file names, e.g. "01 - BrokenMan - Follow" (as on iOS). */
object TrackTitle {

    private val leadingNumber = Regex("""^\d{1,3}\s*[-–.]\s+""")

    /**
     * Strips a leading "NN - " track number, then a leading "<artist> - " when
     * it matches one of `artists`. Never returns an empty title.
     */
    fun clean(raw: String, artists: List<String>): Pair<String, Int?> {
        var title = raw.trim()
        var number: Int? = null
        leadingNumber.find(title)?.let { match ->
            number = match.value.filter { it.isDigit() }.toIntOrNull()
            title = title.substring(match.range.last + 1)
        }
        for (artist in artists.filter { it.isNotEmpty() }) {
            val sep = listOf(" - ", " – ").firstOrNull { title.startsWith(artist + it, ignoreCase = true) }
            if (sep != null) {
                title = title.substring(artist.length + sep.length)
                break
            }
        }
        title = title.trim()
        return if (title.isEmpty()) raw to null else title to number
    }
}
