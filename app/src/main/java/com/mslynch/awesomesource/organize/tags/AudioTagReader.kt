package com.mslynch.awesomesource.organize.tags

import android.content.Context
import android.net.Uri
import com.mslynch.awesomesource.organize.model.LibraryPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File

/**
 * Reads embedded tags from an audio file - the Kotlin equivalent of
 * `legacy-desktop-tagger/musictagger/tags/reader.py`'s `mutagen`-based abstraction,
 * and a real improvement over the Expo attempt's `@missingcore/audio-metadata`
 * (see `legacy-expo-attempt/src/organize/tags/audioTagReader.ts`): `jaudiotagger`
 * (the actively-maintained `net.jthink` fork, not the stale `org.jaudiotagger`
 * original) reads genre, composer, disc number, and track-total, which that
 * library couldn't - closing the exact gap flagged during that attempt.
 *
 * `jaudiotagger`'s `AudioFileIO.read` takes a real `java.io.File`, but a SAF-picked
 * folder only gives `content://` URIs. Rather than trying to fight that, each file
 * is copied to a small cache file first (deleted immediately after reading) - this
 * is the standard, real-world pattern for using file-based JVM tagging libraries
 * against SAF content, not a workaround invented here.
 */
class AudioTagReader(private val context: Context) {

    data class ReadTags(
        val fileFormat: String,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val title: String?,
        val trackNumber: Int?,
        val trackTotal: Int?,
        val discNumber: Int?,
        val discTotal: Int?,
        val year: Int?,
        val genre: String?,
        val composer: String?,
        val durationSeconds: Double?,
        val hasCoverArt: Boolean,
        val coverArtMime: String?,
    )

    sealed class Outcome {
        data class Ok(val tags: ReadTags) : Outcome()
        /** WAV/OGG aren't attempted at all - see the sidecar-metadata fallback in
         * `pipeline/OrganizeLibrary.kt` instead. */
        object UnsupportedFormat : Outcome()
        /** Mirrors the Python original's "one corrupt file can't crash a batch"
         * rule - caught here and reported, not thrown. */
        data class Unreadable(val error: String) : Outcome()
    }

    /** `path` is only used to check the file extension; `uri` is what's actually
     * read (via a temp-file copy - see the class doc comment). */
    suspend fun readTags(uri: Uri, path: LibraryPath): Outcome = withContext(Dispatchers.IO) {
        val extension = path.suffix()
        if (extension !in SUPPORTED_EXTENSIONS) return@withContext Outcome.UnsupportedFormat

        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("tagread", extension, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Outcome.Unreadable("could not open input stream for $uri")

            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tag
            val header = audioFile.audioHeader

            Outcome.Ok(
                ReadTags(
                    fileFormat = extension.removePrefix("."),
                    artist = safeGet(tag, FieldKey.ARTIST),
                    albumArtist = safeGet(tag, FieldKey.ALBUM_ARTIST),
                    album = safeGet(tag, FieldKey.ALBUM),
                    title = safeGet(tag, FieldKey.TITLE),
                    trackNumber = safeGet(tag, FieldKey.TRACK)?.toIntOrNull(),
                    trackTotal = safeGet(tag, FieldKey.TRACK_TOTAL)?.toIntOrNull(),
                    discNumber = safeGet(tag, FieldKey.DISC_NO)?.toIntOrNull(),
                    discTotal = safeGet(tag, FieldKey.DISC_TOTAL)?.toIntOrNull(),
                    year = safeGet(tag, FieldKey.YEAR)?.take(4)?.toIntOrNull(),
                    genre = safeGet(tag, FieldKey.GENRE),
                    composer = safeGet(tag, FieldKey.COMPOSER),
                    durationSeconds = header?.preciseTrackLength,
                    hasCoverArt = tag?.firstArtwork != null,
                    coverArtMime = tag?.firstArtwork?.mimeType,
                )
            )
        } catch (e: Exception) {
            Outcome.Unreadable(e.message ?: e.javaClass.simpleName)
        } finally {
            tempFile?.delete()
        }
    }

    /** `Tag.getFirst(FieldKey)` throws `KeyNotFoundException` when a tag format
     * doesn't support a given generic key at all (as opposed to supporting it but
     * having no value set, which just returns ""). A field the format doesn't
     * support at all shouldn't fail the whole read, so it's treated the same as
     * "blank" here - both become null. */
    private fun safeGet(tag: Tag?, key: FieldKey): String? =
        if (tag == null) null else runCatching { tag.getFirst(key) }.getOrNull()?.ifBlank { null }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf(".mp3", ".flac", ".m4a", ".mp4", ".aac", ".ogg")
    }
}
