package com.mslynch.awesomesource.organize.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mslynch.awesomesource.organize.model.LibraryPath
import com.mslynch.awesomesource.organize.model.TrackMetadata
import com.mslynch.awesomesource.organize.model.libraryPathOf

/**
 * Kotlin/Storage Access Framework re-port of
 * `legacy-desktop-tagger/musictagger/scanner/filesystem.py`. Lets the user pick a
 * library folder via Android's SAF (persisted permission, via
 * `ContentResolver.takePersistableUriPermission` - call that on the `Uri` returned
 * by the `ACTION_OPEN_DOCUMENT_TREE` intent before passing it here) and recursively
 * walks it for audio files using `DocumentFile`.
 *
 * Builds each file's [LibraryPath] by accumulating folder names during its own
 * recursive walk, rather than trying to parse one back out of the file's
 * `content://` URI - SAF document URIs are provider-dependent and not reliably
 * parseable as a hierarchical path string in general, but this walk already knows
 * the real hierarchy as it descends. Same design as the Expo attempt's
 * `scanner/scanner.ts`.
 */
object Scanner {

    private val AUDIO_EXTENSIONS = setOf(".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav")

    /**
     * Recursively walks `root`, yielding one bare [TrackMetadata] per audio file
     * found (uri + path + fileSizeBytes only - tag reading is a separate stage,
     * same scan/read split as the Python original, so the UI can show scan
     * progress incrementally on large libraries before the slower tag-reading pass
     * starts).
     *
     * Skips hidden/AppleDouble files (e.g. macOS's "._Track.mp3" resource-fork
     * siblings, seen in the user's real library) and zero-byte files, matching
     * filesystem.py's "Skips locked/hidden/zero-byte files" rule.
     */
    fun scanFolder(context: Context, rootUri: Uri): List<TrackMetadata> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val results = mutableListOf<TrackMetadata>()
        walk(root, "", results)
        return results
    }

    private fun walk(dir: DocumentFile, relativePrefix: String, results: MutableList<TrackMetadata>) {
        // A folder that can't be listed (permission revoked mid-scan, provider
        // hiccup) is skipped rather than aborting the whole scan - matches the
        // Python original's "one bad entry can't crash a batch" philosophy.
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return

        for (entry in entries) {
            val name = entry.name ?: continue
            if (name.startsWith(".")) continue

            if (entry.isDirectory) {
                walk(entry, joinPath(relativePrefix, name), results)
                continue
            }

            if (!entry.isFile) continue
            val extension = extensionOf(name)
            if (extension !in AUDIO_EXTENSIONS) continue

            val sizeBytes = entry.length()
            if (sizeBytes <= 0L) continue

            results.add(
                TrackMetadata(
                    uri = entry.uri,
                    path = libraryPathOf(*splitPath(joinPath(relativePrefix, name))),
                    fileSizeBytes = sizeBytes,
                )
            )
        }
    }

    private fun joinPath(prefix: String, name: String): String = if (prefix.isEmpty()) name else "$prefix/$name"

    private fun splitPath(path: String): Array<String> = path.split("/").toTypedArray()

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot).lowercase() else ""
    }
}
