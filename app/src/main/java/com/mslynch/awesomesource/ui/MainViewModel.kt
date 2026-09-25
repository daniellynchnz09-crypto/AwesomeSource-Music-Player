package com.mslynch.awesomesource.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslynch.awesomesource.organize.model.MetadataSource
import com.mslynch.awesomesource.organize.model.ReviewStatus
import com.mslynch.awesomesource.organize.persistence.AppDatabase
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import com.mslynch.awesomesource.organize.pipeline.OrganizeLibrary
import com.mslynch.awesomesource.organize.settings.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which field(s) the Library screen's search box matches against - the "search by
 * name, artist, album, match status" the user asked for, picked via a dropdown next
 * to the search field rather than one box per field. */
enum class SearchField { ALL, TITLE, ARTIST, ALBUM, STATUS }

/**
 * Backs the Setup/Library/Settings screens - the Kotlin/Compose equivalent of the
 * Expo attempt's per-screen `useState`/`useFocusEffect` wiring
 * (`legacy-expo-attempt/app/index.tsx` and `library.tsx`), now centralized so both
 * screens share one in-flight organize run instead of duplicating the call.
 *
 * `organize()` runs on [Dispatchers.IO] because [OrganizeLibrary.organize] calls
 * `Scanner.scanFolder`, a synchronous recursive `DocumentFile` walk that is not
 * itself dispatched off the caller's thread - running it directly on
 * `viewModelScope`'s default (Main) dispatcher would freeze the UI for the whole
 * scan phase on a large library.
 *
 * Search/filter/sort state lives here as plain Compose state rather than combined
 * into a `Flow`, since [tracks] itself stays the single raw source of truth (used
 * for both the filtered list AND the always-whole-library stats bar) - deriving the
 * filtered view is cheap enough to do directly in `LibraryScreen` with `remember`.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val settings = SecureSettings(application)
    private val organizer = OrganizeLibrary(application)

    /** Null until the initial count check finishes - lets the UI show a loading
     * state instead of flashing the Setup screen before Library is known to apply. */
    var trackCount by mutableStateOf<Int?>(null)
        private set

    var progress by mutableStateOf<OrganizeLibrary.Progress?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val tracks: Flow<List<TrackEntity>> = db.trackDao().observeAll()

    var searchQuery by mutableStateOf("")
        private set

    var searchField by mutableStateOf(SearchField.ALL)
        private set

    /** Defaults to just NO_MATCH_FOUND per the requested behavior - tracks that got
     * no match at all are the ones most likely to need the user's attention first. */
    var statusFilter by mutableStateOf(setOf(ReviewStatus.NO_MATCH_FOUND))
        private set

    /** Non-null while the track-detail/manual-edit screen is showing, per its path
     * (the primary key) - kept as plain screen state here rather than a nav route
     * argument, since a real file path can contain characters that would need
     * encoding to survive as a route segment. */
    var selectedTrackPath by mutableStateOf<String?>(null)
        private set

    var geminiApiKey by mutableStateOf(settings.geminiApiKey ?: "")
        private set

    var acoustIdApiKey by mutableStateOf(settings.acoustIdApiKey ?: "")
        private set

    var musicBrainzContact by mutableStateOf(settings.musicBrainzContact ?: "")
        private set

    init {
        refreshTrackCount()
    }

    fun refreshTrackCount() {
        viewModelScope.launch { trackCount = db.trackDao().count() }
    }

    /** `rootUri` comes straight from `ActivityResultContracts.OpenDocumentTree` -
     * the persistable grant is taken here so a later app restart can still read the
     * same folder without asking again. */
    fun organize(rootUri: Uri) {
        if (progress != null) return
        val context = getApplication<Application>()
        context.contentResolver.takePersistableUriPermission(rootUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        error = null
        progress = OrganizeLibrary.Progress(OrganizeLibrary.Phase.SCANNING, 0, 0)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    organizer.organize(
                        rootUri,
                        OrganizeLibrary.Options(
                            musicBrainzContact = musicBrainzContact.ifBlank { null },
                            geminiApiKey = geminiApiKey.ifBlank { null },
                            onProgress = { progress = it },
                        ),
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                progress = null
                refreshTrackCount()
            }
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
    }

    fun updateSearchField(field: SearchField) {
        searchField = field
    }

    /** Chip-row toggle - lets any combination of statuses be shown/hidden, e.g.
     * unchecking Approved to filter it out. */
    fun toggleStatusFilter(status: ReviewStatus) {
        statusFilter = if (status in statusFilter) statusFilter - status else statusFilter + status
    }

    /** Tapping a stats tile "hides the other entries and only shows the entries
     * that have that status" - a single-status select, distinct from the
     * multi-select chip toggle above. */
    fun isolateStatusFilter(status: ReviewStatus) {
        statusFilter = setOf(status)
    }

    fun selectTrack(path: String?) {
        selectedTrackPath = path
    }

    fun observeTrack(path: String): Flow<TrackEntity?> = db.trackDao().observeByPath(path)

    /** A manual edit always sets `source = MANUAL_ENTRY`; [ReviewStatus] is never
     * stored, so it's automatically recomputed correctly the next time anything
     * reads this row - no separate "recompute status" step needed. */
    fun updateTrackDetails(
        path: String,
        artist: String?,
        albumArtist: String?,
        album: String?,
        title: String?,
        trackNumber: Int?,
        year: Int?,
        genre: String?,
        composer: String?,
    ) {
        viewModelScope.launch {
            val existing = db.trackDao().getByPath(path) ?: return@launch
            db.trackDao().upsert(
                existing.copy(
                    artist = artist?.ifBlank { null },
                    albumArtist = albumArtist?.ifBlank { null },
                    album = album?.ifBlank { null },
                    title = title?.ifBlank { null },
                    trackNumber = trackNumber,
                    year = year,
                    genre = genre?.ifBlank { null },
                    composer = composer?.ifBlank { null },
                    source = MetadataSource.MANUAL_ENTRY,
                )
            )
        }
    }

    /** Applies only the fields the user actually typed a value into (per-field, not
     * per-track) across every selected track, leaving every other field on every
     * track exactly as it already was - the bulk-edit equivalent of
     * [updateTrackDetails], for the "select a group of tracks and give them all the
     * same album" case. A blank field in the bulk-edit form means "don't touch this
     * field", not "clear it" - unlike the single-track edit form (which is always
     * pre-filled with the current values, so every field is always an explicit
     * choice), a bulk form starts empty and the tracks it applies to may well
     * already disagree on a field the user isn't trying to change.
     *
     * Once saved, automatically re-queries MusicBrainz/Gemini for exactly these
     * tracks - per the user's own follow-up request, filling in Album/Album Artist
     * is very often exactly the missing piece that turns an unmatchable track into a
     * confidently-matchable one, and re-running that shouldn't need a full library
     * rescan. A confident match also triggers a search for other files in the same
     * folder that plausibly belong to the same album (see
     * `OrganizeLibrary.discoverAlbumSiblings`) - flagged as drafts for the user to
     * accept, never auto-applied. */
    fun bulkUpdateTrackDetails(
        paths: Set<String>,
        artist: String?,
        albumArtist: String?,
        album: String?,
        genre: String?,
        composer: String?,
        year: Int?,
    ) {
        viewModelScope.launch {
            val updated = paths.mapNotNull { path ->
                val existing = db.trackDao().getByPath(path) ?: return@mapNotNull null
                existing.copy(
                    artist = artist?.ifBlank { null } ?: existing.artist,
                    albumArtist = albumArtist?.ifBlank { null } ?: existing.albumArtist,
                    album = album?.ifBlank { null } ?: existing.album,
                    genre = genre?.ifBlank { null } ?: existing.genre,
                    composer = composer?.ifBlank { null } ?: existing.composer,
                    year = year ?: existing.year,
                    source = MetadataSource.MANUAL_ENTRY,
                )
            }
            db.trackDao().upsertAll(updated)
            withContext(Dispatchers.IO) {
                organizer.requeryTracks(
                    paths,
                    OrganizeLibrary.Options(
                        musicBrainzContact = musicBrainzContact.ifBlank { null },
                        geminiApiKey = geminiApiKey.ifBlank { null },
                    ),
                )
            }
        }
    }

    /** Copies a MATCH_FOUND track's drafted `proposed*` fields into its real fields -
     * the "accept this draft" action a match-found row's own field values were
     * always meant to feed, without ever touching the file itself (still nothing in
     * this pipeline writes tags back to files - see Claude/To Do list.md). */
    fun acceptProposedMatch(path: String) {
        viewModelScope.launch {
            val existing = db.trackDao().getByPath(path) ?: return@launch
            db.trackDao().upsert(
                existing.copy(
                    artist = existing.proposedArtist ?: existing.artist,
                    albumArtist = existing.proposedAlbumArtist ?: existing.albumArtist,
                    album = existing.proposedAlbum ?: existing.album,
                    title = existing.proposedTitle ?: existing.title,
                    trackNumber = existing.proposedTrackNumber ?: existing.trackNumber,
                    year = existing.proposedYear ?: existing.year,
                    source = MetadataSource.ONLINE_LOOKUP,
                )
            )
        }
    }

    fun updateGeminiApiKey(value: String) {
        geminiApiKey = value
        settings.geminiApiKey = value.ifBlank { null }
    }

    fun updateAcoustIdApiKey(value: String) {
        acoustIdApiKey = value
        settings.acoustIdApiKey = value.ifBlank { null }
    }

    fun updateMusicBrainzContact(value: String) {
        musicBrainzContact = value
        settings.musicBrainzContact = value.ifBlank { null }
    }
}
