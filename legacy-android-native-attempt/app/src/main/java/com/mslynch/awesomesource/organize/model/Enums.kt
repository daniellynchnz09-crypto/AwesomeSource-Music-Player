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
    /** New: routed to the Flagged queue per Claude/MUSIC ORGANIZATION.md - distinct
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
    /** New: a Gemini grounding pass picked/confirmed a candidate (see
     * organize/metadata/GeminiGroundingClient.kt) - kept distinct from
     * ONLINE_LOOKUP so the UI can show the user which fields were LLM-assisted. */
    LLM_GROUNDED,
    /** New: resolved via AcoustID/Chromaprint audio fingerprinting rather than text
     * search - see Claude/MUSIC ORGANIZATION.md's classical-music renamed-title case. */
    AUDIO_FINGERPRINT,
}

/**
 * New concept (not in the legacy tool): which of the user's separate libraries a
 * track belongs to, per Claude/MUSIC ORGANIZATION.md's library-separation feature.
 * OTHER covers anything that isn't confidently classical or EDM - it still gets its
 * own library rather than being force-fit into one of the two.
 */
enum class LibraryType {
    CLASSICAL,
    EDM,
    OTHER,
}
