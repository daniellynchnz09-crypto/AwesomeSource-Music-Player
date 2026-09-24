package com.mslynch.awesomesource.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mslynch.awesomesource.organize.pipeline.OrganizeLibrary

/**
 * One bar spanning the whole organize run, not one bar per phase - each
 * [OrganizeLibrary.Phase] owns an equal-width segment of it and its own color, so
 * the bar fills left-to-right across the entire operation instead of resetting per
 * phase. Direct Kotlin port of `legacy-expo-attempt/src/components/ProgressBar.tsx`.
 */
@Composable
fun OrganizeProgressBar(progress: OrganizeLibrary.Progress, modifier: Modifier = Modifier) {
    val phases = OrganizeLibrary.Phase.entries
    val phaseIndex = phases.indexOf(progress.phase)
    val phaseFraction = if (progress.total > 0) {
        (progress.processed.toFloat() / progress.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    val overallFraction = (phaseIndex + phaseFraction) / phases.size

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(phaseLabel(progress.phase), style = MaterialTheme.typography.bodySmall)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinearProgressIndicator(
                progress = { overallFraction },
                color = phaseColor(progress.phase),
                trackColor = Color(0xFFE0E0E0),
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
            )
            val percentText = "%.1f".format(overallFraction * 100)
            val totalText = if (progress.total > 0) progress.total.toString() else "?"
            Text(
                "${progress.processed}/$totalText ($percentText%)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun phaseLabel(phase: OrganizeLibrary.Phase): String = when (phase) {
    OrganizeLibrary.Phase.SCANNING -> "Scanning files"
    OrganizeLibrary.Phase.READING_TAGS -> "Reading tags"
    OrganizeLibrary.Phase.GROUPING -> "Grouping albums"
    OrganizeLibrary.Phase.QUERYING -> "Looking up matches"
}

private fun phaseColor(phase: OrganizeLibrary.Phase): Color = when (phase) {
    OrganizeLibrary.Phase.SCANNING -> Color(0xFF1565C0)
    OrganizeLibrary.Phase.READING_TAGS -> Color(0xFF6A1B9A)
    OrganizeLibrary.Phase.GROUPING -> Color(0xFF00897B)
    OrganizeLibrary.Phase.QUERYING -> Color(0xFFEF6C00)
}
