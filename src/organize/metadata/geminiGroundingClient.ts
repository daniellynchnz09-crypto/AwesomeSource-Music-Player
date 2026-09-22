/**
 * TypeScript/`fetch` re-port of
 * `legacy-android-native-attempt/.../organize/metadata/GeminiGroundingClient.kt`.
 * Implements the "LLM double-check" pass from Claude/MUSIC ORGANIZATION.md: "if I
 * get Music Brains to scan the album and then get the claude model ... to double
 * check the album, the [model] would get it right every time based on the
 * information from music brains and the files themselves." The user chose Google
 * Gemini over Claude for this specifically for its free tier - the prompt/verdict
 * shape below is otherwise model-agnostic and could point at a different provider
 * later without changing any other pipeline code.
 *
 * This is advisory input to the app's own auto-apply/needs-review/no-match
 * decision, not verbatim external website data - Claude.md's "data from websites
 * must be used verbatim" rule covers reference text like the aesthetics
 * definitions quoted in Claude/App DESIGN.md, not runtime metadata matching.
 *
 * `apiKey` comes from `organize/settings/secureSettings.ts` - see that module's
 * doc comment for why it's never read from anywhere else. **Unverified against a
 * live response** - tracked in Claude/To Do list.md.
 */

import { MbCandidate } from '../model/types';

// Verified live against the real API on 2026-09-22: 'gemini-2.0-flash' (the
// original choice) is decommissioned and returns 404 telling callers to switch to
// this model. Gemini model names get retired periodically - if this starts 404ing
// again, check https://ai.google.dev/gemini-api/docs/models for the current
// lightweight/fast model name.
const DEFAULT_MODEL = 'gemini-3.6-flash';

export interface LocalEvidence {
  artist: string | null;
  album: string | null;
  title: string | null;
  trackCount: number | null;
  year: number | null;
  filenameHint: string | null;
}

export interface GroundingVerdict {
  chosen: MbCandidate | null;
  confident: boolean;
  reasoning: string;
}

interface RawVerdict {
  chosen_index: number | null;
  confident: boolean;
  reasoning: string;
}

function buildPrompt(local: LocalEvidence, candidates: MbCandidate[]): string {
  const candidateLines = candidates
    .map(
      (c, index) =>
        `  ${index}. artist="${c.artistCredit}" title="${c.title}" ` +
        `date=${c.firstReleaseDate ?? '?'} trackCount=${c.trackCount ?? '?'} score=${c.score.toFixed(1)}`,
    )
    .join('\n');

  return `You are double-checking a MusicBrainz metadata match for a personal music library organizer.
Local file evidence (from embedded tags and/or filename): artist="${local.artist ?? '?'}",
album/title="${local.album ?? local.title ?? '?'}", trackCount=${local.trackCount ?? '?'},
year=${local.year ?? '?'}, filenameHint="${local.filenameHint ?? '?'}".

Candidate MusicBrainz matches:
${candidateLines}

Pick the candidate index that best matches the local evidence, accounting for
spelling variants, inconsistent formatting, and human error on either side. If none
of the candidates plausibly match, or you are not confident, say so honestly rather
than guessing.

Respond with ONLY a JSON object of this exact shape, no other text:
{"chosen_index": <integer index or null>, "confident": <true|false>, "reasoning": "<one sentence>"}`;
}

/** Returns null (rather than throwing) when no API key is configured, so callers
 * can treat "not set up" the same as "declined to ground this one" and fall back to
 * the MusicBrainz-only scorer decision. */
export async function groundMatch(
  apiKey: string | null,
  local: LocalEvidence,
  candidates: MbCandidate[],
  model: string = DEFAULT_MODEL,
): Promise<GroundingVerdict | null> {
  if (!apiKey || candidates.length === 0) return null;

  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: buildPrompt(local, candidates) }] }],
        generationConfig: { responseMimeType: 'application/json', temperature: 0.1 },
      }),
    },
  );
  if (!response.ok) return null;

  const data = await response.json();
  const text: string | undefined = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) return null;

  let verdict: RawVerdict;
  try {
    verdict = JSON.parse(text);
  } catch {
    return null;
  }

  const chosen = verdict.chosen_index !== null ? candidates[verdict.chosen_index] ?? null : null;
  return { chosen, confident: verdict.confident, reasoning: verdict.reasoning };
}
