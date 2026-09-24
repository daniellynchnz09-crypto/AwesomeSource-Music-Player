package com.mslynch.awesomesource.organize.model

/** Mirrors `musictagger.models.FileStatus` (legacy-desktop-tagger/musictagger/models.py). */
enum class FileStatus {
    PENDING,
    TAGS_READ,
    UNREADABLE,
    INSUFFICIENT_INFO,
    NEEDS_REVIEW,
    MANUAL_PENDING,
    AUTO_MATCHED,
    LOOKUP_FAILED,
    NO_MATCH,
    APPLIED,
    SKIPPED,
    ERROR,
    /** Routed to the Flagged queue per Claude/MUSIC ORGANIZATION.md - distinct
     * from NEEDS_REVIEW (a scored-but-ambiguous MusicBrainz match) because a track
     * can be flagged for reasons that have nothing to do with match confidence,
     * e.g. a user manually flagging something the pipeline got wrong. */
    FLAGGED,
}

/** Mirrors `musictagger.models.MetadataSource`. */
enum class MetadataSource {
    EMBEDDED_TAGS,
    FILENAME_GUESS,
    ONLINE_LOOKUP,
    MANUAL_ENTRY,
    /** A Gemini grounding pass picked/confirmed a candidate (see
     * organize/metadata/GeminiGroundingClient.kt) - kept distinct from
     * ONLINE_LOOKUP so the UI can show the user which fields were LLM-assisted. */
    LLM_GROUNDED,
    /** Resolved via AcoustID/Chromaprint audio fingerprinting rather than text
     * search - see Claude/MUSIC ORGANIZATION.md's classical-music renamed-title case. */
    AUDIO_FINGERPRINT,
}

/**
 * Which of the user's separate libraries a track belongs to, per
 * Claude/MUSIC ORGANIZATION.md's library-separation feature. OTHER covers anything
 * that isn't confidently classical or EDM - it still gets its own library rather
 * than being force-fit into one of the two.
 */
enum class LibraryType {
    CLASSICAL,
    EDM,
    OTHER,
}

/**
 * The four-way, user-facing review classification requested for the scan results
 * list - distinct from [FileStatus], which is the pipeline's own internal
 * processing state. Deliberately never persisted as its own column (see
 * `TrackEntity.reviewStatus()`) - it's always recomputed from a track's current
 * field values plus whether a match was ever found, so there is exactly one source
 * of truth and a manual edit can never leave a stale status behind.
 */
enum class ReviewStatus {
    /** All core details (artist/album/title/track number) were already present,
     * and the system (MusicBrainz, optionally Gemini-confirmed) recognized the
     * track - nothing to do. */
    APPROVED,
    /** All core details were already present, but the system could not confidently
     * recognize the track - left unmodified, flagged for the user to double-check. */
    VERIFY,
    /** Some core details were missing, but the system found a match - the matched
     * metadata is held as a proposed draft (see `TrackEntity.proposed*` fields) and
     * is not written into the track's own fields or the file until the user
     * accepts it. */
    MATCH_FOUND,
    /** Some core details were missing and the system found no match - left
     * unmodified, needs manual entry. */
    NO_MATCH_FOUND;

    companion object {
        /** The single formula every persist path uses, so "approved"/"verify"/
         * "match found"/"no match found" always mean the same thing regardless of
         * which pipeline stage or manual edit produced the current field values. */
        fun compute(hasAllDetails: Boolean, recognized: Boolean): ReviewStatus = when {
            hasAllDetails && recognized -> APPROVED
            hasAllDetails && !recognized -> VERIFY
            !hasAllDetails && recognized -> MATCH_FOUND
            else -> NO_MATCH_FOUND
        }
    }
}

/** A track counts as having "all details" only with artist, album, title, AND a
 * track number all present - missing any one of these means real lookup/entry work
 * still has to happen, per the four-way model [ReviewStatus] implements. */
fun TrackMetadata.hasAllDetails(): Boolean =
    !artist.isNullOrBlank() && !album.isNullOrBlank() && !title.isNullOrBlank() && trackNumber != null
