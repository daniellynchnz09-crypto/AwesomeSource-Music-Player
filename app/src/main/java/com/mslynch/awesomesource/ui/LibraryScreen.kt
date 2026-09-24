package com.mslynch.awesomesource.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mslynch.awesomesource.organize.model.FileStatus
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity

/**
 * Returning-user library screen: the organized track list plus a button to
 * re-scan/add more, matching `legacy-expo-attempt/app/library.tsx`. Shown once
 * [MainViewModel.trackCount] is non-zero; [SetupScreen] handles the first run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val tracks by viewModel.tracks.collectAsState(initial = emptyList())
    val progress = viewModel.progress
    val error = viewModel.error
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::organize)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Button(
                onClick = { pickFolder.launch(null) },
                enabled = progress == null,
                modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
            ) {
                Text(if (progress != null) "Organizing…" else "Choose Folder & Organize")
            }

            if (progress != null) {
                OrganizeProgressBar(
                    progress,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tracks scanned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn {
                    items(tracks, key = { it.path }) { track -> TrackRow(track) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: TrackEntity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            track.title ?: track.path.substringAfterLast('/'),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = buildString {
            append(track.artist ?: "(no artist)")
            if (!track.album.isNullOrEmpty()) append(" — ${track.album}")
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            track.status.name,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(track.status),
            fontWeight = FontWeight.SemiBold,
        )
        if (track.statusDetail.isNotEmpty()) {
            Text(
                track.statusDetail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}

private fun statusColor(status: FileStatus): Color = when (status) {
    FileStatus.AUTO_MATCHED, FileStatus.APPLIED -> Color(0xFF2E7D32)
    FileStatus.NEEDS_REVIEW, FileStatus.FLAGGED -> Color(0xFFE65100)
    FileStatus.UNREADABLE, FileStatus.ERROR, FileStatus.LOOKUP_FAILED -> Color(0xFFC62828)
    else -> Color(0xFF616161)
}
