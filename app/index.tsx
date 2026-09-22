import { useCallback, useState } from 'react';
import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { Link, useFocusEffect } from 'expo-router';
import { pickLibraryFolder } from '@/organize/scanner/scanner';
import { organizeLibrary, OrganizeProgress } from '@/organize/pipeline/organizeLibrary';
import { getAllTracks, TrackRow } from '@/organize/persistence/database';
import { getGeminiApiKey, getMusicBrainzContact } from '@/organize/settings/secureSettings';
import { FileStatus } from '@/organize/model/types';

/**
 * Basic library screen: pick a folder, run it through the organization pipeline,
 * and show the resulting per-file status. This is a functional view onto
 * `pipeline/organizeLibrary.ts`, not the real Tracks/Album/Artist pages from
 * Claude/Design.md (those come in a later phase) - its purpose is to let the
 * pipeline actually be watched working end-to-end on a real device.
 *
 * Note: reading embedded tags depends on `@missingcore/audio-metadata`, a
 * third-party native module not bundled into the stock Expo Go app - scanning a
 * real folder needs a custom Expo dev client (EAS Build), not plain Expo Go. If
 * that module isn't available, `readTags` already catches the failure per-file
 * (see `tags/audioTagReader.ts`) and this screen shows it as an per-file error
 * status rather than crashing - screens/navigation still work fine in Expo Go.
 */
export default function LibraryScreen() {
  const [tracks, setTracks] = useState<TrackRow[]>([]);
  const [progress, setProgress] = useState<OrganizeProgress | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    getAllTracks()
      .then(setTracks)
      .catch((e) => setError(String(e)));
  }, []);

  useFocusEffect(reload);

  const onOrganize = async () => {
    setError(null);
    try {
      const root = await pickLibraryFolder();
      const [geminiApiKey, musicBrainzContact] = await Promise.all([getGeminiApiKey(), getMusicBrainzContact()]);

      setProgress({ phase: 'scanning', processed: 0, total: 0 });
      await organizeLibrary(root, {
        geminiApiKey,
        musicBrainzContact,
        onProgress: setProgress,
      });
      setProgress(null);
      reload();
    } catch (e) {
      setProgress(null);
      const message = e instanceof Error ? e.message : String(e);
      // A cancelled folder picker rejects too - don't show that as an error.
      if (!/cancel/i.test(message)) {
        setError(message);
        Alert.alert('Organize failed', message);
      }
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Pressable style={styles.button} onPress={onOrganize} disabled={progress !== null}>
          <Text style={styles.buttonText}>{progress ? 'Organizing…' : 'Choose Folder & Organize'}</Text>
        </Pressable>
        <Link href="/settings" style={styles.settingsLink}>
          <Text>Settings</Text>
        </Link>
      </View>

      {progress && (
        <Text style={styles.progressText}>
          {progress.phase}: {progress.processed}/{progress.total || '?'}
        </Text>
      )}
      {error && <Text style={styles.errorText}>{error}</Text>}

      <FlatList
        data={tracks}
        keyExtractor={(item) => item.path}
        ListEmptyComponent={<Text style={styles.empty}>No tracks scanned yet.</Text>}
        renderItem={({ item }) => <TrackRowView track={item} />}
      />
    </View>
  );
}

function TrackRowView({ track }: { track: TrackRow }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowTitle} numberOfLines={1}>
        {track.title ?? track.path.split('/').pop()}
      </Text>
      <Text style={styles.rowSubtitle} numberOfLines={1}>
        {track.artist ?? '(no artist)'} {track.album ? `— ${track.album}` : ''}
      </Text>
      <Text style={[styles.rowStatus, statusStyle(track.status)]}>{track.status}</Text>
      {track.statusDetail ? (
        <Text style={styles.rowDetail} numberOfLines={2}>
          {track.statusDetail}
        </Text>
      ) : null}
    </View>
  );
}

function statusStyle(status: FileStatus) {
  switch (status) {
    case FileStatus.AutoMatched:
      return { color: '#2e7d32' };
    case FileStatus.NeedsReview:
    case FileStatus.Flagged:
      return { color: '#e65100' };
    case FileStatus.Unreadable:
    case FileStatus.Error:
    case FileStatus.LookupFailed:
      return { color: '#c62828' };
    default:
      return { color: '#616161' };
  }
}

const styles = StyleSheet.create({
  container: { flex: 1, paddingTop: 12 },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, gap: 16, marginBottom: 8 },
  button: { backgroundColor: '#1565c0', paddingVertical: 10, paddingHorizontal: 16, borderRadius: 8 },
  buttonText: { color: 'white', fontWeight: '600' },
  settingsLink: { marginLeft: 'auto' },
  progressText: { paddingHorizontal: 16, marginBottom: 8, opacity: 0.7 },
  errorText: { paddingHorizontal: 16, marginBottom: 8, color: '#c62828' },
  empty: { padding: 24, textAlign: 'center', opacity: 0.6 },
  row: { paddingHorizontal: 16, paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, borderColor: '#ccc' },
  rowTitle: { fontSize: 15, fontWeight: '600' },
  rowSubtitle: { fontSize: 13, opacity: 0.7 },
  rowStatus: { fontSize: 12, marginTop: 2, fontWeight: '600' },
  rowDetail: { fontSize: 11, opacity: 0.6, marginTop: 2 },
});
