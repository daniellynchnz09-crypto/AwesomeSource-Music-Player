import { Stack } from 'expo-router';

/**
 * Root layout for Expo Router. Real navigation (Music Player / Genres / Album /
 * Artist / Tracks / Playlists / Organization / Discovery / Cover-flow / Analytics
 * per Claude/Design.md) and the Neo-Aero/Dark-Aero theme land in a later phase;
 * this just confirms the router wiring works.
 */
export default function RootLayout() {
  return <Stack />;
}
