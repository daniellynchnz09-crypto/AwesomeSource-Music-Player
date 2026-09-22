/**
 * Core organization-pipeline types. TypeScript re-port of
 * `legacy-desktop-tagger/musictagger/models.py` (see that file), extended with
 * fields Claude/MUSIC ORGANIZATION.md calls for that the legacy tool didn't need:
 * `composer` and `libraryType` on TrackMetadata, and the ArtistCredit type for EDM
 * collab/VIP/remix crediting (see organize/tags/edmCreditParser.ts).
 *
 * This is the second port of this logic in one session - the first was to Kotlin
 * (see legacy-android-native-attempt/), before the user switched the project to
 * Expo/React Native to reuse their existing Expo workflow and avoid a local Android
 * Studio install. See Claude/ANDROID ARCHITECTURE.md for the full history.
 */

export enum FileStatus {
  Pending = 'pending',
  TagsRead = 'tags_read',
  Unreadable = 'unreadable',
  InsufficientInfo = 'insufficient_info',
  NeedsReview = 'needs_review',
  ManualPending = 'manual_pending',
  AutoMatched = 'auto_matched',
  LookupFailed = 'lookup_failed',
  NoMatch = 'no_match',
  Applied = 'applied',
  Skipped = 'skipped',
  Error = 'error',
  /** Routed to the Flagged queue per Claude/MUSIC ORGANIZATION.md - distinct from
   * NeedsReview (a scored-but-ambiguous MusicBrainz match) since a track can be
   * flagged for reasons unrelated to match confidence, e.g. a user manually
   * flagging something the pipeline got wrong. */
  Flagged = 'flagged',
}

export enum MetadataSource {
  EmbeddedTags = 'embedded_tags',
  FilenameGuess = 'filename_guess',
  OnlineLookup = 'online_lookup',
  ManualEntry = 'manual_entry',
  /** A Gemini grounding pass picked/confirmed a candidate. */
  LlmGrounded = 'llm_grounded',
  /** Resolved via AcoustID/Chromaprint audio fingerprinting rather than text
   * search - see Claude/MUSIC ORGANIZATION.md's classical-music renamed-title case. */
  AudioFingerprint = 'audio_fingerprint',
}

/** Which of the user's separate libraries a track belongs to, per
 * Claude/MUSIC ORGANIZATION.md's library-separation feature. Other covers anything
 * that isn't confidently classical or EDM. */
export enum LibraryType {
  Classical = 'classical',
  Edm = 'edm',
  Other = 'other',
}

export enum ArtistCreditRole {
  Artist = 'artist',
  Composer = 'composer',
}

/** One artist credit on a track - see organize/tags/edmCreditParser.ts for the
 * collab/VIP/remix splitting rules that produce these. */
export interface ArtistCredit {
  name: string;
  role: ArtistCreditRole;
}

export interface TrackMetadata {
  /** The real, openable SAF content URI for I/O (from expo-document-picker /
   * expo-file-system) - see LibraryPath's doc comment in libraryPath.ts for why
   * this is kept separate from the path-string logic. */
  uri: string;
  path: string; // LibraryPath string - see libraryPath.ts
  fileFormat?: string;
  artist?: string;
  albumArtist?: string;
  album?: string;
  title?: string;
  trackNumber?: number;
  trackTotal?: number;
  discNumber?: number;
  discTotal?: number;
  year?: number;
  genre?: string;
  durationSeconds?: number;
  hasCoverArt?: boolean;
  coverArtMime?: string;
  composer?: string;
  libraryType?: LibraryType;
  artistCredits?: ArtistCredit[];
  fileSizeBytes?: number;
  source?: MetadataSource;
  status?: FileStatus;
  statusDetail?: string;
}

export interface MbCandidate {
  releaseId: string;
  title: string;
  artistCredit: string;
  firstReleaseDate?: string | null;
  trackCount?: number | null;
  score: number;
  isRecording?: boolean;
  album?: string | null;
}

export interface AlbumGroup {
  groupKey: string;
  files: TrackMetadata[];
  bestGuessArtist?: string;
  bestGuessAlbum?: string;
  isSingleton?: boolean;
  flaggedInconsistent?: boolean;
  status?: FileStatus;
  statusDetail?: string;
  candidates?: MbCandidate[];
  chosenReleaseId?: string;
}
