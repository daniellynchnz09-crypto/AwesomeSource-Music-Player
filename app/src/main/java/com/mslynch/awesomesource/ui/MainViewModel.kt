package com.mslynch.awesomesource.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslynch.awesomesource.organize.persistence.AppDatabase
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import com.mslynch.awesomesource.organize.pipeline.OrganizeLibrary
import com.mslynch.awesomesource.organize.settings.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
