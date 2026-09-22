/**
 * TypeScript/`fetch` re-port of
 * `legacy-android-native-attempt/.../organize/metadata/AcoustIdClient.kt`.
 * Resolves a Chromaprint fingerprint (see `organize/fingerprint/` - fingerprint
 * *generation* is a separate, not-yet-implemented spike, see that module) to
 * MusicBrainz recording IDs via AcoustID, for the classical-music renamed-title
 * fallback in Claude/MUSIC ORGANIZATION.md. `apiKey` is the user's own free
 * AcoustID client key, from `organize/settings/secureSettings.ts` - never hardcoded.
 *
 * Note: AcoustID's API defaults to XML unless `format=json` is passed explicitly -
 * the Kotlin version omitted this (a latent bug never caught since nothing ever
 * actually called it against the live API); fixed here.
 */

export interface AcoustIdMatch {
  recordingId: string;
  title: string | null;
  artist: string | null;
  score: number;
}

interface AcoustIdRecording {
  id?: string;
  title?: string;
  artists?: Array<{ id?: string; name?: string }>;
}

interface AcoustIdResult {
  id?: string;
  score?: number;
  recordings?: AcoustIdRecording[];
}

interface AcoustIdLookupResponse {
  status?: string;
  results?: AcoustIdResult[];
}

/** Returns matches sorted best-first, or an empty list if no key is configured,
 * nothing matched, or the lookup failed. */
export async function lookupFingerprint(
  apiKey: string | null,
  fingerprint: string,
  durationSeconds: number,
): Promise<AcoustIdMatch[]> {
  if (!apiKey) return [];

  const params = new URLSearchParams({
    client: apiKey,
    duration: String(durationSeconds),
    fingerprint,
    meta: 'recordings',
    format: 'json',
  });

  let data: AcoustIdLookupResponse;
  try {
    const response = await fetch(`https://api.acoustid.org/v2/lookup?${params.toString()}`);
    if (!response.ok) return [];
    data = await response.json();
  } catch {
    return [];
  }

  return (data.results ?? [])
    .slice()
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
    .flatMap((result) =>
      (result.recordings ?? [])
        .filter((recording) => Boolean(recording.id))
        .map(
          (recording): AcoustIdMatch => ({
            recordingId: recording.id!,
            title: recording.title ?? null,
            artist: recording.artists?.map((a) => a.name ?? '').join(', ') || null,
            score: result.score ?? 0,
          }),
        ),
    );
}
