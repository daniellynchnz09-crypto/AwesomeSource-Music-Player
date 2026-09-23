/**
 * TypeScript/`expo-secure-store` re-port of
 * `legacy-android-native-attempt/.../organize/settings/SecureSettings.kt`.
 * Holds every secret the organization pipeline needs (Gemini API key, AcoustID API
 * key, MusicBrainz contact string) in Keystore-backed encrypted storage.
 *
 * Per Claude.md's security requirement: these are entered by the user in the app's
 * Settings screen at runtime, never hardcoded, and never committed to source
 * control (see the root `.gitignore`'s "Secrets" section). This module is the
 * *only* place these values are read from or written to - callers
 * (MusicBrainzClient, GeminiGroundingClient, AcoustIdClient) take the value as a
 * parameter rather than reading storage themselves, so it's obvious from the
 * function signatures alone that no secret leaks into a log, an analytics call, or
 * a network request that isn't the one API it belongs to.
 */

import * as SecureStore from 'expo-secure-store';

const KEY_GEMINI = 'gemini_api_key';
const KEY_ACOUSTID = 'acoustid_api_key';
const KEY_MB_CONTACT = 'musicbrainz_contact';

export async function getGeminiApiKey(): Promise<string | null> {
  return SecureStore.getItemAsync(KEY_GEMINI);
}

export async function setGeminiApiKey(value: string | null): Promise<void> {
  if (value === null) {
    await SecureStore.deleteItemAsync(KEY_GEMINI);
  } else {
    await SecureStore.setItemAsync(KEY_GEMINI, value);
  }
}

export async function getAcoustIdApiKey(): Promise<string | null> {
  return SecureStore.getItemAsync(KEY_ACOUSTID);
}

export async function setAcoustIdApiKey(value: string | null): Promise<void> {
  if (value === null) {
    await SecureStore.deleteItemAsync(KEY_ACOUSTID);
  } else {
    await SecureStore.setItemAsync(KEY_ACOUSTID, value);
  }
}

/** MusicBrainz's API etiquette asks for a contact string in the User-Agent;
 * optional, but recommended to avoid stricter throttling. */
export async function getMusicBrainzContact(): Promise<string | null> {
  return SecureStore.getItemAsync(KEY_MB_CONTACT);
}

export async function setMusicBrainzContact(value: string | null): Promise<void> {
  if (value === null) {
    await SecureStore.deleteItemAsync(KEY_MB_CONTACT);
  } else {
    await SecureStore.setItemAsync(KEY_MB_CONTACT, value);
  }
}
