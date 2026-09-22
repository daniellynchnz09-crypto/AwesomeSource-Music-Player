package com.mslynch.awesomesource.organize.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds every secret the organization pipeline needs (Gemini API key, AcoustID API
 * key, MusicBrainz contact string) in Android Keystore-backed encrypted storage.
 *
 * Per Claude.md's security requirement and the plan's "LLM grounding" decision:
 * these are entered by the user in the app's Settings screen at runtime, never
 * hardcoded, and never committed to source control (see the root `.gitignore`'s
 * "Secrets" section). This class is the *only* place those values are read from or
 * written to - callers (MusicBrainzClient, GeminiGroundingClient, AcoustIdClient)
 * take the value as a constructor parameter rather than reading prefs themselves,
 * so it's obvious from the type signatures alone that no secret leaks into a log,
 * an analytics call, or a network request that isn't the one API it belongs to.
 */
class SecureSettings(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var geminiApiKey: String?
        get() = prefs.getString(KEY_GEMINI, null)
        set(value) = prefs.edit().putString(KEY_GEMINI, value).apply()

    var acoustIdApiKey: String?
        get() = prefs.getString(KEY_ACOUSTID, null)
        set(value) = prefs.edit().putString(KEY_ACOUSTID, value).apply()

    /** MusicBrainz's API etiquette asks for a contact string in the User-Agent;
     * optional, but recommended to avoid stricter throttling. */
    var musicBrainzContact: String?
        get() = prefs.getString(KEY_MB_CONTACT, null)
        set(value) = prefs.edit().putString(KEY_MB_CONTACT, value).apply()

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_ACOUSTID = "acoustid_api_key"
        private const val KEY_MB_CONTACT = "musicbrainz_contact"
    }
}
