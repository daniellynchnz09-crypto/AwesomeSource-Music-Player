import { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { Stack } from 'expo-router';
import {
  getAcoustIdApiKey,
  getGeminiApiKey,
  getMusicBrainzContact,
  setAcoustIdApiKey,
  setGeminiApiKey,
  setMusicBrainzContact,
} from '@/organize/settings/secureSettings';

/**
 * Basic settings screen for the three values the organization pipeline needs -
 * see `src/organize/settings/secureSettings.ts` for why these are only ever read
 * from/written to Keystore-backed encrypted storage, never hardcoded or logged.
 * Each field auto-saves on blur rather than needing a separate "Save" button, so
 * partial progress is never lost.
 */
export default function SettingsScreen() {
  const [loading, setLoading] = useState(true);
  const [geminiApiKey, setGeminiApiKeyState] = useState('');
  const [acoustIdApiKey, setAcoustIdApiKeyState] = useState('');
  const [musicBrainzContact, setMusicBrainzContactState] = useState('');

  useEffect(() => {
    (async () => {
      const [gemini, acoustId, contact] = await Promise.all([
        getGeminiApiKey(),
        getAcoustIdApiKey(),
        getMusicBrainzContact(),
      ]);
      setGeminiApiKeyState(gemini ?? '');
      setAcoustIdApiKeyState(acoustId ?? '');
      setMusicBrainzContactState(contact ?? '');
      setLoading(false);
    })().catch((error) => Alert.alert('Failed to load settings', String(error)));
  }, []);

  if (loading) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator />
      </View>
    );
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Stack.Screen options={{ title: 'Settings' }} />

      <Field
        label="Gemini API key"
        hint="Free tier, from Google AI Studio. Used for the LLM double-check pass on ambiguous MusicBrainz matches."
        value={geminiApiKey}
        onChangeText={setGeminiApiKeyState}
        onSave={(value) => setGeminiApiKey(value || null)}
        secure
      />

      <Field
        label="AcoustID API key"
        hint="Must be an application key from acoustid.org/new-applications, not your personal account key - lookups reject the latter."
        value={acoustIdApiKey}
        onChangeText={setAcoustIdApiKeyState}
        onSave={(value) => setAcoustIdApiKey(value || null)}
        secure
      />

      <Field
        label="MusicBrainz contact"
        hint="Optional but recommended - any string (email/URL) identifying this app's requests, per MusicBrainz's API etiquette."
        value={musicBrainzContact}
        onChangeText={setMusicBrainzContactState}
        onSave={(value) => setMusicBrainzContact(value || null)}
      />

      <Text style={styles.footnote}>
        These are stored only on this device (Android Keystore-backed encrypted storage) and are
        never written to any file in the project.
      </Text>
    </ScrollView>
  );
}

function Field({
  label,
  hint,
  value,
  onChangeText,
  onSave,
  secure,
}: {
  label: string;
  hint: string;
  value: string;
  onChangeText: (value: string) => void;
  onSave: (value: string) => Promise<void>;
  secure?: boolean;
}) {
  const [saving, setSaving] = useState(false);

  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        onBlur={() => {
          setSaving(true);
          onSave(value)
            .catch((error) => Alert.alert(`Failed to save ${label}`, String(error)))
            .finally(() => setSaving(false));
        }}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        placeholder="(not set)"
      />
      <Text style={styles.hint}>
        {hint} {saving ? 'Saving...' : ''}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  content: { padding: 16, gap: 20 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  field: { gap: 6 },
  label: { fontSize: 15, fontWeight: '600' },
  input: {
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#888',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
  },
  hint: { fontSize: 12, opacity: 0.65 },
  footnote: { fontSize: 12, opacity: 0.6, marginTop: 8 },
});
