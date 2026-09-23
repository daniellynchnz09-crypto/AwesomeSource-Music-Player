import { StyleSheet, Text, View } from 'react-native';
import { OrganizePhase } from '@/organize/pipeline/organizeLibrary';

// Order matters: this fixes each phase's segment of the unified bar (phase i
// spans [i/N, (i+1)/N) of the total width) and its color.
const PHASES: OrganizePhase[] = ['scanning', 'reading_tags', 'grouping', 'querying'];

const PHASE_LABELS: Record<OrganizePhase, string> = {
  scanning: 'Scanning files',
  reading_tags: 'Reading tags',
  grouping: 'Grouping albums',
  querying: 'Looking up matches',
};

const PHASE_COLORS: Record<OrganizePhase, string> = {
  scanning: '#1565c0',
  reading_tags: '#6a1b9a',
  grouping: '#00897b',
  querying: '#ef6c00',
};

/** A single bar spanning the whole organize run (not one bar per phase) - each
 * phase owns an equal-width segment of it and its own color, so the bar fills
 * left-to-right across the entire operation instead of resetting per phase. */
export function ProgressBar({
  phase,
  processed,
  total,
}: {
  phase: OrganizePhase;
  processed: number;
  total: number;
}) {
  const phaseIndex = PHASES.indexOf(phase);
  const phaseFraction = total > 0 ? Math.min(1, processed / total) : 0;
  const overallFraction = (phaseIndex + phaseFraction) / PHASES.length;
  const percentText = (overallFraction * 100).toFixed(1);

  return (
    <View style={styles.container}>
      <Text style={styles.label}>{PHASE_LABELS[phase]}</Text>
      <View style={styles.barRow}>
        <View style={styles.track}>
          <View
            style={[styles.fill, { width: `${overallFraction * 100}%`, backgroundColor: PHASE_COLORS[phase] }]}
          />
        </View>
        <Text style={styles.count}>
          {processed}/{total || '?'} ({percentText}%)
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { alignSelf: 'stretch', gap: 4 },
  label: { fontSize: 13, opacity: 0.8 },
  barRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  track: { flex: 1, height: 10, borderRadius: 5, backgroundColor: '#e0e0e0', overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 5 },
  count: { fontSize: 12, opacity: 0.65 },
});
