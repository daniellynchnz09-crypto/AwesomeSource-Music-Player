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
import java.time.Instant

/** Which field(s) the Library screen's search box matches against - the "search by
 * name, artist, album, match status" the user asked for, picked via a dropdown next
 * to the search field rather than one box per field. */
enum class SearchField { ALL, TITLE, ARTIST, ALBUM, STATUS }

/** How the Library screen orders its (filtered) track list. */
enum class SortMode { ALPHABETICAL, RECENTLY_UPDATED }

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

    var sortMode by mutableStateOf(SortMode.ALPHABETICAL)
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
     * unchecking Approved to filter it out. Never leaves the filter empty - toggling
     * off the last remaining status resets to "show everything" instead, since an
     * empty filter set can only ever mean an empty (and confusing, dead-end-looking)
     * list. */
    fun toggleStatusFilter(status: ReviewStatus) {
        val next = if (status in statusFilter) statusFilter - status else statusFilter + status
        statusFilter = next.ifEmpty { ReviewStatus.entries.toSet() }
    }

    /** Tapping a stats tile "hides the other entries and only shows the entries
     * that have that status" - a single-status select, distinct from the
     * multi-select chip toggle above. Tapping the *already*-isolated tile again
     * resets to showing every status, so isolating is a reversible tap rather than a
     * one-way trip that then needs four individual chip taps to undo. */
    fun isolateStatusFilter(status: ReviewStatus) {
        statusFilter = if (statusFilter == setOf(status)) ReviewStatus.entries.toSet() else setOf(status)
    }

    fun updateSortMode(mode: SortMode) {
        sortMode = mode
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
                    updatedAt = Instant.now().toString(),
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
                    updatedAt = Instant.now().toString(),
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

    /** Copies a track's drafted `proposed*` fields into its real fields - the
     * "accept this draft" action a match-found row's own field values were always
     * meant to feed, without ever touching the file itself (still nothing in this
     * pipeline writes tags back to files - see Claude/To Do list.md). A no-op for a
     * track with nothing proposed (every `proposed*` field null) - `?:` just keeps
     * the existing value - so calling this on a track that was never a match-found
     * draft (e.g. a plain No Match row) is always safe. */
    private fun TrackEntity.withProposedAccepted(): TrackEntity = copy(
        artist = proposedArtist ?: artist,
        albumArtist = proposedAlbumArtist ?: albumArtist,
        album = proposedAlbum ?: album,
        title = proposedTitle ?: title,
        trackNumber = proposedTrackNumber ?: trackNumber,
        year = proposedYear ?: year,
        source = MetadataSource.ONLINE_LOOKUP,
        updatedAt = Instant.now().toString(),
    )

    fun acceptProposedMatch(path: String) {
        viewModelScope.launch {
            val existing = db.trackDao().getByPath(path) ?: return@launch
            db.trackDao().upsert(existing.withProposedAccepted())
        }
    }

    /** The opposite of [acceptProposedMatch] - clears a wrong draft (`matchedReleaseId`
     * and every `proposed*` field) without touching any of the track's own real
     * fields, so it falls back to whatever status its own data honestly supports
     * (usually Verify or No Match) instead of sitting on a match that isn't real.
     * Added after a real false positive: `OrganizeLibrary.discoverAlbumSiblings`
     * proposed an untagged, unrelated file as a specific Tipper "Cloaked" track
     * whose position was already correctly claimed by a different file elsewhere in
     * the library (see that function's doc comment for the actual fix) - clearing
     * the bad row this way, through the app's own normal write path, is also how
     * any future false positive gets un-stuck, not just this one. */
    fun rejectProposedMatch(path: String) {
        viewModelScope.launch {
            val existing = db.trackDao().getByPath(path) ?: return@launch
            db.trackDao().upsert(
                existing.copy(
                    matchedReleaseId = null,
                    proposedArtist = null,
                    proposedAlbumArtist = null,
                    proposedAlbum = null,
                    proposedTitle = null,
                    proposedTrackNumber = null,
                    proposedYear = null,
                    statusDetail = "",
                    updatedAt = Instant.now().toString(),
                )
            )
        }
    }

    /** The multi-select "Approve" action - applies the exact same accept-a-draft
     * logic as [acceptProposedMatch] to every selected track in one batched write,
     * rather than issuing one coroutine/DB-write/reactive-Flow-refresh per track (a
     * large selection accepted one row at a time is what's suspected to have caused
     * a real reported app hang/crash). This deliberately does NOT let the user force
     * any status directly - it only ever applies a draft a track already has, so it
     * can't make the status filters meaningless: a track with no proposed match
     * (e.g. a genuine No Match row) is untouched, since every field in
     * [withProposedAccepted] falls back to its own existing value. */
    fun bulkAcceptProposedMatches(paths: Set<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val updated = db.trackDao().getByPaths(paths.toList()).map { it.withProposedAccepted() }
            db.trackDao().upsertAll(updated)
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
