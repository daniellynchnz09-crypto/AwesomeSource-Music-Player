package com.mslynch.awesomesource.organize.model

/**
 * A POSIX-style path *relative to the user's chosen library root folder*, e.g.
 * "EDM/SVDDEN DEATH - ACT I.mp3" or "Classical/Beethoven/Disc 1/01 Allegro.flac".
 *
 * The legacy desktop tool (see legacy-desktop-tagger/musictagger) used
 * `pathlib.Path` directly for both real filesystem access and path-string logic
 * (`.stem`, `.parent`, `.name`). On Android, a user-picked folder is only reachable
 * through Storage Access Framework `content://` URIs (via `DocumentFile`), not a raw
 * filesystem path - java.nio.file.Path also has known gaps on Android before
 * API 34, so we don't lean on it either. This type carries the pure path-string
 * logic every ported algorithm (filename parser, album grouper) actually needs,
 * while [TrackMetadata.uri] separately carries the real, openable URI for I/O. This
 * keeps the ported logic almost line-for-line comparable to the Python original.
 */
@JvmInline
value class LibraryPath(val value: String) {

    /** Equivalent to Python's `Path.name`. */
    fun name(): String = value.substringAfterLast('/')

    /** Equivalent to Python's `Path.stem`. */
    fun stem(): String {
        val n = name()
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    /** Equivalent to Python's `Path.suffix` (including the leading dot, lowercased). */
    fun suffix(): String {
        val n = name()
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(dot).lowercase() else ""
    }

    /** Equivalent to Python's `Path.parent`; null at the library root. */
    fun parent(): LibraryPath? {
        val idx = value.lastIndexOf('/')
        return if (idx < 0) null else LibraryPath(value.substring(0, idx))
    }

    /** The parent folder's own name (empty string at the library root). */
    fun parentName(): String = parent()?.name() ?: ""

    /** The grandparent folder's name (empty string if there is none). */
    fun grandparentName(): String = parent()?.parent()?.name() ?: ""

    fun hasGrandparent(): Boolean = parent()?.parent() != null

    override fun toString(): String = value
}

fun libraryPathOf(vararg segments: String): LibraryPath =
    LibraryPath(segments.filter { it.isNotEmpty() }.joinToString("/"))
