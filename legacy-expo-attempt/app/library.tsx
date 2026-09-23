import { useCallback, useState } from 'react';
import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { Link, Stack, useFocusEffect } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { pickLibraryFolder } from '@/organize/scanner/scanner';
import { organizeLibrary, OrganizeProgress } from '@/organize/pipeline/organizeLibrary';
import { getAllTracks, TrackRow } from '@/organize/persistence/database';
import { getGeminiApiKey, getMusicBrainzContact } from '@/organize/settings/secureSettings';
import { FileStatus } from '@/organize/model/types';
import { ProgressBar } from '@/components/ProgressBar';

/**
 * Returning-user library screen: the organized track list plus a button to
 * re-scan/add more. First-time setup lives in `app/index.tsx` instead, which
 * redirects here once at least one track has been organized.
 *
 * See `app/index.tsx` for the Expo Go vs. custom dev client tag-reading caveat.
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
      <Stack.Screen options={{ title: 'Library' }} />

      <View style={styles.header}>
        <Pressable style={styles.button} onPress={onOrganize} disabled={progress !== null}>
          <Text style={styles.buttonText}>{progress ? 'Organizing…' : 'Choose Folder & Organize'}</Text>
        </Pressable>
        <Link href="/settings" asChild>
          <Pressable style={styles.settingsButton} hitSlop={12}>
            <Ionicons name="settings-outline" size={24} color="#333" />
          </Pressable>
        </Link>
      </View>

      {progress && (
        <View style={styles.progressWrap}>
          <ProgressBar phase={progress.phase} processed={progress.processed} total={progress.total} />
        </View>
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
  settingsButton: { marginLeft: 'auto', padding: 4 },
  progressWrap: { paddingHorizontal: 16, marginBottom: 8 },
  errorText: { paddingHorizontal: 16, marginBottom: 8, color: '#c62828' },
  empty: { padding: 24, textAlign: 'center', opacity: 0.6 },
  row: { paddingHorizontal: 16, paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, borderColor: '#ccc' },
  rowTitle: { fontSize: 15, fontWeight: '600' },
  rowSubtitle: { fontSize: 13, opacity: 0.7 },
  rowStatus: { fontSize: 12, marginTop: 2, fontWeight: '600' },
  rowDetail: { fontSize: 11, opacity: 0.6, marginTop: 2 },
});
