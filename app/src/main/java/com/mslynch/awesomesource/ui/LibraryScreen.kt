package com.mslynch.awesomesource.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mslynch.awesomesource.organize.model.ReviewStatus
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import com.mslynch.awesomesource.organize.persistence.entity.reviewStatus

/**
 * Returning-user library screen: the organized track list plus a button to
 * re-scan/add more, matching `legacy-expo-attempt/app/library.tsx` - extended with
 * the review-status stats bar, search/filter, scrollbar, and tap-to-edit detail
 * screen requested on top of that original design. Shown once
 * [MainViewModel.trackCount] is non-zero; [SetupScreen] handles the first run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val selectedPath = viewModel.selectedTrackPath
    if (selectedPath != null) {
        TrackDetailScreen(viewModel, path = selectedPath, onBack = { viewModel.selectTrack(null) })
        return
    }

    val tracks by viewModel.tracks.collectAsState(initial = emptyList())
    val progress = viewModel.progress
    val error = viewModel.error
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::organize)
    }

    // Stats always reflect the whole library regardless of the current filter/
    // search, so the counts stay meaningful even while the list below is narrowed.
    val counts = remember(tracks) {
        ReviewStatus.entries.associateWith { status -> tracks.count { it.reviewStatus() == status } }
    }
    val filtered = remember(tracks, viewModel.searchQuery, viewModel.searchField, viewModel.statusFilter) {
        tracks
            .filter { it.reviewStatus() in viewModel.statusFilter }
            .filter { matchesSearch(it, viewModel.searchQuery, viewModel.searchField) }
    }
    val listState = rememberLazyListState()

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

            if (tracks.isNotEmpty()) {
                StatsBar(counts, viewModel.statusFilter, onStatClick = viewModel::isolateStatusFilter)
                SearchRow(
                    query = viewModel.searchQuery,
                    field = viewModel.searchField,
                    onQueryChange = viewModel::updateSearchQuery,
                    onFieldChange = viewModel::updateSearchField,
                )
                StatusFilterChips(selected = viewModel.statusFilter, onToggle = viewModel::toggleStatusFilter)
            }

            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tracks scanned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tracks match the current filter/search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.path }) { track ->
                            TrackRow(track, onClick = { viewModel.selectTrack(track.path) })
                        }
                    }
                    TrackScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

private fun matchesSearch(track: TrackEntity, query: String, field: SearchField): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return when (field) {
        SearchField.ALL ->
            listOfNotNull(track.title, track.artist, track.album).any { it.lowercase().contains(q) } ||
                reviewStatusLabel(track.reviewStatus()).lowercase().contains(q)
        SearchField.TITLE -> track.title?.lowercase()?.contains(q) == true
        SearchField.ARTIST -> track.artist?.lowercase()?.contains(q) == true
        SearchField.ALBUM -> track.album?.lowercase()?.contains(q) == true
        SearchField.STATUS -> reviewStatusLabel(track.reviewStatus()).lowercase().contains(q)
    }
}

@Composable
private fun StatsBar(counts: Map<ReviewStatus, Int>, selected: Set<ReviewStatus>, onStatClick: (ReviewStatus) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReviewStatus.entries.forEach { status ->
            val color = reviewStatusColor(status)
            val isIsolated = selected == setOf(status)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isIsolated) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                    .border(if (isIsolated) 1.5.dp else 0.dp, color, RoundedCornerShape(8.dp))
                    .clickable { onStatClick(status) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    (counts[status] ?: 0).toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Text(
                    reviewStatusLabel(status),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchRow(query: String, field: SearchField, onQueryChange: (String) -> Unit, onFieldChange: (SearchField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(searchFieldLabel(field))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose which field to search")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SearchField.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(searchFieldLabel(option)) },
                        onClick = { onFieldChange(option); expanded = false },
                    )
                }
            }
        }
    }
}

private fun searchFieldLabel(field: SearchField): String = when (field) {
    SearchField.ALL -> "All"
    SearchField.TITLE -> "Title"
    SearchField.ARTIST -> "Artist"
    SearchField.ALBUM -> "Album"
    SearchField.STATUS -> "Status"
}

@Composable
private fun StatusFilterChips(selected: Set<ReviewStatus>, onToggle: (ReviewStatus) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReviewStatus.entries.forEach { status ->
            FilterChip(
                selected = status in selected,
                onClick = { onToggle(status) },
                label = { Text(reviewStatusLabel(status)) },
            )
        }
    }
}

/** A thin, always-visible thumb on the right edge sized/positioned from
 * [LazyListState.layoutInfo] - Android Compose (unlike Compose for Desktop) has no
 * built-in scrollbar component, so this is hand-rolled rather than assumed to exist. */
@Composable
private fun TrackScrollbar(listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleCount = layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleCount >= totalItems) return

    var trackHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val thumbFraction = (visibleCount.toFloat() / totalItems).coerceIn(0.08f, 1f)
    val maxTopFraction = 1f - thumbFraction
    val scrollDenominator = (totalItems - visibleCount).coerceAtLeast(1)
    val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / scrollDenominator).coerceIn(0f, 1f)
    val topFraction = (scrollFraction * maxTopFraction).coerceIn(0f, maxTopFraction)

    Box(
        modifier
            .fillMaxHeight()
            .width(12.dp)
            .onSizeChanged { trackHeightPx = it.height },
    ) {
        val thumbHeightDp = with(density) { (trackHeightPx * thumbFraction).toDp() }
        val offsetDp = with(density) { (trackHeightPx * topFraction).toDp() }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = offsetDp)
                .width(4.dp)
                .height(thumbHeightDp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun TrackRow(track: TrackEntity, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
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
        val reviewStatus = track.reviewStatus()
        Text(
            reviewStatusLabel(reviewStatus),
            style = MaterialTheme.typography.labelSmall,
            color = reviewStatusColor(reviewStatus),
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

internal fun reviewStatusLabel(status: ReviewStatus): String = when (status) {
    ReviewStatus.APPROVED -> "Approved"
    ReviewStatus.VERIFY -> "Verify"
    ReviewStatus.MATCH_FOUND -> "Match Found"
    ReviewStatus.NO_MATCH_FOUND -> "No Match Found"
}

internal fun reviewStatusColor(status: ReviewStatus): Color = when (status) {
    ReviewStatus.APPROVED -> Color(0xFF2E7D32)
    ReviewStatus.VERIFY -> Color(0xFFEF6C00)
    ReviewStatus.MATCH_FOUND -> Color(0xFF1565C0)
    ReviewStatus.NO_MATCH_FOUND -> Color(0xFFC62828)
}
