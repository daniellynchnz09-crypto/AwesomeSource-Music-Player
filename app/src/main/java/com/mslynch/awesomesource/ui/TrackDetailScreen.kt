package com.mslynch.awesomesource.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mslynch.awesomesource.organize.model.ReviewStatus
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import com.mslynch.awesomesource.organize.persistence.entity.reviewStatus

/**
 * Manual add/edit screen for a single track, opened by tapping a row in
 * [LibraryScreen]. A [ReviewStatus.MATCH_FOUND] track additionally shows the
 * system's drafted proposal (see `TrackEntity.proposed*`) with a one-tap "Accept"
 * action, since that draft is exactly what the user asked to be able to review
 * before anything is written into the real fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(viewModel: MainViewModel, path: String, onBack: () -> Unit) {
    val track by viewModel.observeTrack(path).collectAsState(initial = null)
    // Without this, the system back gesture/button had no in-app screen left to pop
    // to (this Composable is swapped in over Library, not pushed onto a nav
    // back stack) and fell through to finishing the whole Activity instead - a real
    // gap that surfaced while testing on-device: pressing back here exited the app
    // to the launcher rather than returning to the Library list.
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track?.title ?: track?.path?.substringAfterLast('/') ?: "Track") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val current = track
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        TrackDetailForm(
            modifier = Modifier.padding(innerPadding),
            track = current,
            onAcceptProposed = {
                viewModel.acceptProposedMatch(current.path)
                onBack()
            },
            onSave = { artist, albumArtist, album, title, trackNumber, year, genre, composer ->
                viewModel.updateTrackDetails(current.path, artist, albumArtist, album, title, trackNumber, year, genre, composer)
                onBack()
            },
        )
    }
}

@Composable
private fun TrackDetailForm(
    modifier: Modifier = Modifier,
    track: TrackEntity,
    onAcceptProposed: () -> Unit,
    onSave: (
        artist: String?,
        albumArtist: String?,
        album: String?,
        title: String?,
        trackNumber: Int?,
        year: Int?,
        genre: String?,
        composer: String?,
    ) -> Unit,
) {
    var title by remember(track.path) { mutableStateOf(track.title ?: "") }
    var artist by remember(track.path) { mutableStateOf(track.artist ?: "") }
    var albumArtist by remember(track.path) { mutableStateOf(track.albumArtist ?: "") }
    var album by remember(track.path) { mutableStateOf(track.album ?: "") }
    var trackNumberText by remember(track.path) { mutableStateOf(track.trackNumber?.toString() ?: "") }
    var yearText by remember(track.path) { mutableStateOf(track.year?.toString() ?: "") }
    var genre by remember(track.path) { mutableStateOf(track.genre ?: "") }
    var composer by remember(track.path) { mutableStateOf(track.composer ?: "") }

    val reviewStatus = track.reviewStatus()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            reviewStatusLabel(reviewStatus),
            style = MaterialTheme.typography.labelLarge,
            color = reviewStatusColor(reviewStatus),
        )
        Text(track.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (reviewStatus == ReviewStatus.MATCH_FOUND) {
            ProposedMatchCard(track, onAccept = onAcceptProposed)
        }

        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("Album artist") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = trackNumberText,
            onValueChange = { trackNumberText = it.filter(Char::isDigit) },
            label = { Text("Track number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = yearText,
            onValueChange = { yearText = it.filter(Char::isDigit) },
            label = { Text("Year") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = composer, onValueChange = { composer = it }, label = { Text("Composer") }, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                onSave(artist, albumArtist, album, title, trackNumberText.toIntOrNull(), yearText.toIntOrNull(), genre, composer)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun ProposedMatchCard(track: TrackEntity, onAccept: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Proposed match (not yet applied)", style = MaterialTheme.typography.titleSmall)
            ProposedRow("Title", track.proposedTitle)
            ProposedRow("Artist", track.proposedArtist)
            ProposedRow("Album artist", track.proposedAlbumArtist)
            ProposedRow("Album", track.proposedAlbum)
            ProposedRow("Track number", track.proposedTrackNumber?.toString())
            ProposedRow("Year", track.proposedYear?.toString())
            OutlinedButton(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text("Accept proposed match")
            }
        }
    }
}

@Composable
private fun ProposedRow(label: String, value: String?) {
    if (value.isNullOrEmpty()) return
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
}
