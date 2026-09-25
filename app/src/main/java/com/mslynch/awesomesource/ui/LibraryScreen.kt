package com.mslynch.awesomesource.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mslynch.awesomesource.R
import com.mslynch.awesomesource.organize.model.ReviewStatus
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import com.mslynch.awesomesource.organize.persistence.entity.reviewStatus
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Returning-user library screen: the organized track list plus a button to
 * re-scan/add more, matching `legacy-expo-attempt/app/library.tsx` - extended with
 * the review-status stats bar, search/filter, scrollbar, and tap-to-edit detail
 * screen requested on top of that original design. Shown once
 * [MainViewModel.trackCount] is non-zero; [SetupScreen] handles the first run.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // A search query searches the WHOLE library regardless of which status chips
    // are active - the status filter only narrows the plain browse view. Without
    // this, typing a search query while a stats tile is isolated (e.g. tapped
    // "Match Found" earlier) silently searches only that one status with no visual
    // reminder why, which looked exactly like "search is broken" - it wasn't; it was
    // quietly AND-ed with whatever filter happened to still be active.
    val filtered = remember(tracks, viewModel.searchQuery, viewModel.searchField, viewModel.statusFilter, viewModel.sortMode) {
        tracks
            .filter { matchesSearch(it, viewModel.searchQuery, viewModel.searchField) }
            .filter { viewModel.searchQuery.isNotBlank() || it.reviewStatus() in viewModel.statusFilter }
            .let { list ->
                when (viewModel.sortMode) {
                    SortMode.ALPHABETICAL -> list.sortedBy { (it.title ?: it.path.substringAfterLast('/')).lowercase() }
                    SortMode.RECENTLY_UPDATED -> list.sortedByDescending { it.updatedAt ?: "" }
                }
            }
    }
    val listState = rememberLazyListState()

    // Long-pressing a row enters selection mode (per the "select multiple entries
    // and edit their details in bulk" request); a plain tap then toggles selection
    // instead of opening the detail screen, until every row is deselected again.
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showBulkEditDialog by remember { mutableStateOf(false) }

    fun toggleSelection(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    Scaffold(
        topBar = {
            if (selectedPaths.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selectedPaths.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedPaths = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Library") },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            // A long rectangular row rather than a small corner icon, per the
            // user's own request that the old tiny edit button was easy to miss -
            // "Approve" sits alongside it since selecting tracks to accept their
            // drafted matches in bulk is exactly as common a next step as bulk-
            // editing them. Approve only ever applies a draft a track already has
            // (see MainViewModel.bulkAcceptProposedMatches) - it can't force a
            // status, so it can't make the review-status filters meaningless.
            if (selectedPaths.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            viewModel.bulkAcceptProposedMatches(selectedPaths)
                            selectedPaths = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Approve")
                    }
                    Button(
                        onClick = { showBulkEditDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Edit")
                    }
                }
            }
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
                SortRow(mode = viewModel.sortMode, onModeChange = viewModel::updateSortMode)
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
                            TrackRow(
                                track,
                                selected = track.path in selectedPaths,
                                inSelectionMode = selectedPaths.isNotEmpty(),
                                onClick = {
                                    if (selectedPaths.isNotEmpty()) toggleSelection(track.path) else viewModel.selectTrack(track.path)
                                },
                                onLongClick = { toggleSelection(track.path) },
                            )
                        }
                    }
                    TrackScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    if (showBulkEditDialog) {
        BulkEditDialog(
            trackCount = selectedPaths.size,
            onDismiss = { showBulkEditDialog = false },
            onSave = { artist, albumArtist, album, genre, composer, year ->
                viewModel.bulkUpdateTrackDetails(selectedPaths, artist, albumArtist, album, genre, composer, year)
                showBulkEditDialog = false
                selectedPaths = emptySet()
            },
        )
    }
}

/** Every field starts blank and stays optional - see [MainViewModel.bulkUpdateTrackDetails]'s
 * doc comment for why a blank field must mean "leave this track's value alone" here,
 * unlike the single-track edit form. */
@Composable
private fun BulkEditDialog(
    trackCount: Int,
    onDismiss: () -> Unit,
    onSave: (artist: String?, albumArtist: String?, album: String?, genre: String?, composer: String?, year: Int?) -> Unit,
) {
    var artist by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var yearText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $trackCount tracks") },
        text = {
            Column {
                Text(
                    "Only fields you fill in will be changed - the rest are left as-is on every selected track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(composer, { composer = it }, label = { Text("Composer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(yearText, { yearText = it }, label = { Text("Year") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    artist.ifBlank { null },
                    albumArtist.ifBlank { null },
                    album.ifBlank { null },
                    genre.ifBlank { null },
                    composer.ifBlank { null },
                    yearText.toIntOrNull(),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortRow(mode: SortMode, onModeChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sort:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(sortModeLabel(mode))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose sort order")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SortMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortModeLabel(option)) },
                        onClick = { onModeChange(option); expanded = false },
                    )
                }
            }
        }
    }
}

private fun sortModeLabel(mode: SortMode): String = when (mode) {
    SortMode.ALPHABETICAL -> "A–Z"
    SortMode.RECENTLY_UPDATED -> "Recently updated"
}

/** A thumb on the right edge sized/positioned from [LazyListState.layoutInfo] and
 * draggable like a Windows scrollbar - Android Compose (unlike Compose for Desktop)
 * has no built-in scrollbar component, so both the visual and the drag behavior are
 * hand-rolled. Grabbing anywhere on the (wide, easy-to-hit) track jumps straight to
 * that position via [LazyListState.scrollToItem] - deliberately not the animated
 * `animateScrollToItem`, since the whole point is moving fast through a long list,
 * not watching it glide there. */
@Composable
private fun TrackScrollbar(listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleCount = layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleCount >= totalItems) return

    var trackHeightPx by remember { mutableStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val thumbFraction = (visibleCount.toFloat() / totalItems).coerceIn(0.08f, 1f)
    val maxTopFraction = 1f - thumbFraction
    val scrollDenominator = (totalItems - visibleCount).coerceAtLeast(1)
    val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / scrollDenominator).coerceIn(0f, 1f)
    val topFraction = (scrollFraction * maxTopFraction).coerceIn(0f, maxTopFraction)

    // Centers the thumb under the finger: a touch at pixel y should put the
    // thumb's own center there, not its top-left corner, or dragging would feel
    // like it's fighting a constant vertical offset. Reads listState.layoutInfo
    // fresh on every call (not the totalItems/visibleCount captured at the
    // enclosing composition) since jumpTo keeps running across many scroll ticks
    // during one continuous drag - it must never work off stale values from
    // whichever recomposition happened to be current when the drag started.
    fun jumpTo(touchY: Float) {
        if (trackHeightPx <= 0) return
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        if (total == 0 || visible >= total) return
        val fraction = (visible.toFloat() / total).coerceIn(0.08f, 1f)
        val denominator = (total - visible).coerceAtLeast(1)
        val thumbHeightPx = trackHeightPx * fraction
        val usableRangePx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val desiredTopPx = (touchY - thumbHeightPx / 2f).coerceIn(0f, usableRangePx)
        val positionFraction = desiredTopPx / usableRangePx
        val targetIndex = (positionFraction * denominator).roundToInt().coerceIn(0, denominator)
        coroutineScope.launch { listState.scrollToItem(targetIndex) }
    }

    Box(
        modifier
            .fillMaxHeight()
            // Wider than the visible thumb so it's actually easy to grab with a
            // finger, matching how a real scrollbar's hit target works.
            .width(28.dp)
            .onSizeChanged { trackHeightPx = it.height }
            // A constant key is deliberate: keying this on totalItems/visibleCount
            // (as an earlier version did) restarted the whole gesture detector mid-
            // drag every time a jumpTo() call changed the scroll position enough to
            // shift visibleItemsInfo.size by even one item - which cancelled the
            // in-progress drag() coroutine mere milliseconds into every real drag,
            // making it look like only the initial touch-down ever registered. A
            // real device/emulator swipe caught this; it would pass unnoticed with
            // single discrete taps alone.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isDragging = true
                    jumpTo(down.position.y)
                    drag(down.id) { change ->
                        jumpTo(change.position.y)
                        change.consume()
                    }
                    isDragging = false
                }
            },
    ) {
        val thumbHeightDp = with(density) { (trackHeightPx * thumbFraction).toDp() }
        val offsetDp = with(density) { (trackHeightPx * topFraction).toDp() }
        val thumbWidth = if (isDragging) 8.dp else 4.dp
        val thumbAlpha = if (isDragging) 0.8f else 0.4f
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = offsetDp)
                .width(thumbWidth)
                .height(thumbHeightDp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = thumbAlpha), RoundedCornerShape(4.dp)),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(track: TrackEntity, selected: Boolean, inSelectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inSelectionMode) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
            )
        }
        TrackArt(track.coverArtPath, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
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
    }
    HorizontalDivider()
}

/** A small square thumbnail like a real music player's track list - embedded
 * artwork extracted to a local cache file by `AudioTagReader.cacheArtwork` (see
 * `TrackEntity.coverArtPath`'s doc comment), loaded via Coil (handles local
 * `file://`-style paths out of the box, with its own memory/disk caching so this
 * doesn't re-decode the same image on every recomposition/scroll). Falls back to a
 * custom placeholder mark (`R.drawable.default_album_art`, a face/X/treble-clef
 * hybrid per the user's own request) for tracks with no embedded art yet, instead of
 * a blank square - this project only depends on `material-icons-core`, not the
 * extended icon set a stock music-note glyph would need, hence the custom drawable. */
@Composable
private fun TrackArt(coverArtPath: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (coverArtPath != null) {
            AsyncImage(
                model = File(coverArtPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.default_album_art),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().padding(9.dp),
            )
        }
    }
}

internal fun reviewStatusLabel(status: ReviewStatus): String = when (status) {
    ReviewStatus.APPROVED -> "Approved"
    ReviewStatus.VERIFY -> "Verify"
    ReviewStatus.MATCH_FOUND -> "Match Found"
    // Shortened from "No Match Found" - the longer label was cramped against the
    // stats tile's own edges at labelSmall size (the shared label, not just the
    // tile display, since search-by-status matches against this same string).
    ReviewStatus.NO_MATCH_FOUND -> "No Match"
}

internal fun reviewStatusColor(status: ReviewStatus): Color = when (status) {
    ReviewStatus.APPROVED -> Color(0xFF2E7D32)
    ReviewStatus.VERIFY -> Color(0xFFEF6C00)
    ReviewStatus.MATCH_FOUND -> Color(0xFF1565C0)
    ReviewStatus.NO_MATCH_FOUND -> Color(0xFFC62828)
}
