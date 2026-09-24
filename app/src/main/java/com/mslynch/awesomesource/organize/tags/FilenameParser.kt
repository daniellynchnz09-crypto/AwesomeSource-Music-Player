package com.mslynch.awesomesource.organize.tags

import com.mslynch.awesomesource.organize.model.LibraryPath
import com.mslynch.awesomesource.organize.model.TrackMetadata

/**
 * Ported from `legacy-desktop-tagger/musictagger/tags/filename_parser.py`. Derives
 * candidate artist/album/title text from a file's path when tags are sparse. See
 * that file's module doc comment for the two-tier design (structured patterns for
 * conventionally-organized libraries, a loose junk-token-filtering fallback for
 * gibberish filenames) - every regex, constant, and real-world bug fix documented
 * there is preserved here rather than re-derived, since each one encodes an actual
 * observed failure case (cited inline below).
 */
object FilenameParser {

    private val NOISE_PATTERNS = listOf(
        Regex("""\[[^\]]*]"""), // [Explicit], [HQ], ...
        // Newgrounds Audio Portal rips tack the submission's numeric ID onto the
        // TITLE tag itself, e.g. "Dr. Finkelfracken's Cure (ID: 383158)".
        Regex("""\(id:\s*\d+\)""", RegexOption.IGNORE_CASE),
        Regex("""\{[^}]*}"""), // {tag}-style bracket noise
        Regex("""\((?:remaster(?:ed)?|deluxe|explicit|clean|hq|official)[^)]*\)""", RegexOption.IGNORE_CASE),
        Regex("""\((?:free|original\s+mix)\)""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{2,4}\s?kbps\b""", RegexOption.IGNORE_CASE),
        Regex("""\(\s*\d+\s*\)"""), // trailing (1), (2) copy markers
        Regex("""\s*-\s*copy\b""", RegexOption.IGNORE_CASE),
        // YouTube rips: "Song Name | Insolito (4K music visualizer)" - a bare "|"
        // essentially never appears in a real song title.
        Regex("""\s*\|.*$"""),
        Regex("""\([^)]*\b(?:video|audio|visualizer)\b[^)]*\)""", RegexOption.IGNORE_CASE),
        // A bare (non-parenthesized) "Ft. X"/"feat. X" suffix is upload-added
        // credit text, not part of the recording's own title (see scorer.py's
        // parallel note); a parenthesized "(feat. X)" is deliberately left alone.
        Regex("""\s+(?:ft\.?|feat\.?|featuring)\s+.+$""", RegexOption.IGNORE_CASE),
        // DJ-mix compilations: "Album Vol. N - Mixed by DJ Name" - the "Mixed by X"
        // tail is a credit line, not the title, and was corrupting the dash-split
        // artist guess below.
        Regex("""\s*-\s*(?:mixed|remixed|hosted|selected|compiled)\s+by\s+.+$""", RegexOption.IGNORE_CASE),
    )

    private val TRACK_PREFIX_RE = Regex("""^\s*(\d{1,3})\s*[-.\s]+""")
    private val ARTIST_TITLE_RE = Regex("""^(.+?)\s*-\s*(.+)$""")

    // "Album Name/Act 1/track.mp3" or ".../Disc 2/...", ".../CD1/...": the immediate
    // parent is a disc/act subdivision of ONE album, not a real "Album" level.
    private val DISC_SUBFOLDER_RE = Regex("""^(?:disc|cd|act|part|volume|vol)\.?\s*\d+$""", RegexOption.IGNORE_CASE)

    private val GENERIC_ANCESTOR_NAMES = setOf(
        "music", "songs", "downloads", "itunes", "mp3", "mp3s", "library",
        "unsorted", "new music", "audio", "tracks", "media", "my music",
    )

    private val JUNK_WORDS = setOf(
        "copy", "final", "new", "track", "file", "audio", "untitled", "download",
        "downloaded", "unknown", "song", "music", "temp", "tmp", "v2", "v3",
    )

    private val TOKEN_SPLIT_RE = Regex("""[^A-Za-z0-9']+""")

    data class FilenameGuess(
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null,
        val trackNumber: Int? = null,
        val confidence: Confidence = Confidence.STRUCTURED,
        val searchText: String? = null,
    )

    enum class Confidence { STRUCTURED, LOOSE }

    private fun stripNoise(text: String): String {
        var result = text
        for (pattern in NOISE_PATTERNS) result = pattern.replace(result, "")
        return result.trim(' ', '-', '_', '.')
    }

    /** Public entry point for noise-stripping arbitrary tag text (not just a
     * filename) that may have no "Artist - Title" dash to split on at all. */
    fun cleanNoiseText(text: String?): String = stripNoise(text ?: "")

    private fun looksLikeJunkToken(token: String): Boolean {
        if (token.isEmpty()) return true
        val lowered = token.lowercase()
        if (lowered in JUNK_WORDS) return true
        if (token.all { it.isDigit() }) return true
        val hasDigit = token.any { it.isDigit() }
        val vowels = lowered.count { it in "aeiou" }
        // A short alphanumeric mix with a digit and zero vowels reads as a
        // machine-generated fragment (e.g. "xY7", "2gK9"), not a real word.
        if (hasDigit && vowels == 0) return true
        if (token.length >= 5 && hasDigit && vowels.toDouble() / token.length < 0.15) return true
        return false
    }

    private fun looksPlausible(text: String): Boolean {
        val tokens = text.split(TOKEN_SPLIT_RE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val junkCount = tokens.count { t ->
            // A short (1-2 digit) standalone numeral is real title text ("Memories
            // 2", "Part 3"), not the gibberish looksLikeJunkToken targets.
            looksLikeJunkToken(t) && !(t.all { it.isDigit() } && t.length <= 2)
        }
        return junkCount < tokens.size / 2.0
    }

    /** Splits "Artist - Title" style text into (artist, title) when the pattern
     * plausibly applies; returns (null, null) otherwise. Also used on TITLE tag
     * text itself - see filename_parser.py's identical doc comment on why
     * YouTube-sourced rips dump the whole "Artist - Track" string into the title
     * tag rather than splitting it properly. */
    fun splitArtistTitleText(text: String): Pair<String?, String?> {
        val cleaned = stripNoise(text)
        val match = ARTIST_TITLE_RE.find(cleaned) ?: return null to null
        val artist = match.groupValues[1]
        return if (looksPlausible(artist)) artist.trim() to match.groupValues[2].trim() else null to null
    }

    /** Splits a leading "## " track-position prefix off raw text, e.g. a TITLE tag
     * literally "1 Goldilocks Zone" -> (1, "Goldilocks Zone"). */
    fun stripLeadingTrackNumber(text: String?): Pair<Int?, String> {
        if (text.isNullOrEmpty()) return null to (text ?: "")
        val match = TRACK_PREFIX_RE.find(text) ?: return null to text
        val remainder = text.substring(match.range.last + 1).trim()
        if (remainder.isEmpty()) return null to text
        return match.groupValues[1].toInt() to remainder
    }

    /** Best-known track number: tag value, else filename-parsed, else a leading
     * "## " prefix baked into the TITLE tag's own text. See filename_parser.py's
     * identical comment on why filename-only track numbers can't be skipped. */
    fun resolveTrackNumber(track: TrackMetadata): Int? {
        track.trackNumber?.let { return it }
        parseFilename(track.path).trackNumber?.let { return it }
        return stripLeadingTrackNumber(track.title).first
    }

    private fun looseFallback(stem: String, trackNumber: Int?, album: String?): FilenameGuess {
        val tokens = stem.split(TOKEN_SPLIT_RE)
        val kept = tokens.filter { it.isNotEmpty() && !looksLikeJunkToken(it) }
        val searchText = kept.joinToString(" ").trim()
        return FilenameGuess(
            album = album,
            confidence = Confidence.LOOSE,
            searchText = searchText.ifEmpty { null },
            trackNumber = trackNumber,
        )
    }

    fun parseFilename(path: LibraryPath): FilenameGuess {
        val stem = stripNoise(path.stem())
        val parentName = path.parentName()
        val hasGrandparent = path.hasGrandparent()
        val grandparentName = path.grandparentName()

        var trackNumber: Int? = null
        var remainder = stem
        TRACK_PREFIX_RE.find(stem)?.let { match ->
            trackNumber = match.groupValues[1].toInt()
            remainder = stem.substring(match.range.last + 1).trim()
        }

        // Pattern 2 checked before Pattern 1: an explicit "Artist - Album" folder
        // name is a more specific, deliberate signal than merely having two
        // ancestor folders, which just as often means <library root>/<album>/ with
        // no real artist level.
        val folderMatch = ARTIST_TITLE_RE.find(stripNoise(parentName))
        if (folderMatch != null && remainder.isNotEmpty() && looksPlausible(folderMatch.groupValues[1])) {
            return FilenameGuess(
                artist = folderMatch.groupValues[1].trim(),
                album = folderMatch.groupValues[2].trim(),
                title = remainder,
                trackNumber = trackNumber,
                confidence = Confidence.STRUCTURED,
            )
        }

        // "Album Name/Act 1/track.mp3" etc: the immediate parent is a disc/act
        // subdivision, not a real "Album" level - the grandparent is the actual
        // album, and it is NOT a plausible artist name at all, so Pattern 1 below
        // is skipped entirely for this layout.
        var albumFromDiscSubfolder: String? = null
        if (hasGrandparent && DISC_SUBFOLDER_RE.matches(parentName.trim())) {
            val candidateAlbum = stripNoise(grandparentName)
            if (candidateAlbum.isNotEmpty() && looksPlausible(candidateAlbum)) {
                albumFromDiscSubfolder = candidateAlbum
            }
        }

        // Pattern 1: Artist/Album/## - Title.ext
        if (hasGrandparent && albumFromDiscSubfolder == null) {
            val artistCandidate = stripNoise(grandparentName)
            val albumCandidate = stripNoise(parentName)
            if (
                artistCandidate.isNotEmpty() && albumCandidate.isNotEmpty() && remainder.isNotEmpty() &&
                artistCandidate.lowercase() !in GENERIC_ANCESTOR_NAMES && looksPlausible(artistCandidate)
            ) {
                return FilenameGuess(
                    artist = artistCandidate,
                    album = albumCandidate,
                    title = remainder,
                    trackNumber = trackNumber,
                    confidence = Confidence.STRUCTURED,
                )
            }
        }

        // Pattern 3: "Artist - Title.ext" (loose single files)
        val fileMatch = ARTIST_TITLE_RE.find(remainder)
        if (fileMatch != null && looksPlausible(fileMatch.groupValues[1])) {
            return FilenameGuess(
                artist = fileMatch.groupValues[1].trim(),
                album = albumFromDiscSubfolder,
                title = fileMatch.groupValues[2].trim(),
                trackNumber = trackNumber,
                confidence = Confidence.STRUCTURED,
            )
        }

        if (remainder.isNotEmpty() && looksPlausible(remainder)) {
            return FilenameGuess(
                album = albumFromDiscSubfolder,
                title = remainder,
                trackNumber = trackNumber,
                confidence = Confidence.STRUCTURED,
            )
        }

        // Nothing structured and plausible matched - likely gibberish; fall back to
        // junk-token filtering and a free-text search guess.
        return looseFallback(stem, trackNumber, albumFromDiscSubfolder)
    }
}
