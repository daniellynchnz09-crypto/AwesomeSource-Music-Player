import { useCallback, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { Link, Stack, router, useFocusEffect } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { pickLibraryFolder } from '@/organize/scanner/scanner';
import { organizeLibrary, OrganizeProgress } from '@/organize/pipeline/organizeLibrary';
import { getTrackCount } from '@/organize/persistence/database';
import { getGeminiApiKey, getMusicBrainzContact } from '@/organize/settings/secureSettings';
import { ProgressBar } from '@/components/ProgressBar';

/**
 * First-run setup screen. Shown only until the user has organized a library at
 * least once - after that, `/library` (the returning-user track list) becomes
 * the home screen instead. See `app/library.tsx`.
 *
 * Note: reading embedded tags depends on `@missingcore/audio-metadata`, a
 * third-party native module not bundled into the stock Expo Go app - scanning a
 * real folder needs a custom Expo dev client (EAS Build), not plain Expo Go. If
 * that module isn't available, `readTags` already catches the failure per-file
 * (see `tags/audioTagReader.ts`) rather than crashing.
 */
export default function SetupScreen() {
  const [checking, setChecking] = useState(true);
  const [progress, setProgress] = useState<OrganizeProgress | null>(null);
  const [error, setError] = useState<string | null>(null);

  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      getTrackCount()
        .then((count) => {
          if (cancelled) return;
          if (count > 0) {
            router.replace('/library');
          } else {
            setChecking(false);
          }
        })
        .catch((e) => {
          if (!cancelled) {
            setError(String(e));
            setChecking(false);
          }
        });
      return () => {
        cancelled = true;
      };
    }, [])
  );

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
      router.replace('/library');
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

  if (checking) {
    return (
      <View style={styles.centered}>
        <Stack.Screen options={{ title: 'Setup', headerTitleAlign: 'center' }} />
        <ActivityIndicator />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Stack.Screen options={{ title: 'Setup', headerTitleAlign: 'center' }} />

      <Link href="/settings" asChild>
        <Pressable style={styles.settingsButton} hitSlop={12}>
          <Ionicons name="settings-outline" size={26} color="#333" />
        </Pressable>
      </Link>

      <View style={styles.centered}>
        <Text style={styles.heading}>Let's add your Library</Text>
        <Pressable style={styles.button} onPress={onOrganize} disabled={progress !== null}>
          <Text style={styles.buttonText}>{progress ? 'Organizing…' : 'Choose Folder & Organize'}</Text>
        </Pressable>
        {progress && (
          <ProgressBar phase={progress.phase} processed={progress.processed} total={progress.total} />
        )}
        {error && <Text style={styles.errorText}>{error}</Text>}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  settingsButton: { position: 'absolute', top: 12, right: 16, zIndex: 1, padding: 4 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24, gap: 16 },
  heading: { fontSize: 20, fontWeight: '600', textAlign: 'center' },
  button: { backgroundColor: '#1565c0', paddingVertical: 12, paddingHorizontal: 20, borderRadius: 8 },
  buttonText: { color: 'white', fontWeight: '600' },
  errorText: { textAlign: 'center', color: '#c62828' },
});
