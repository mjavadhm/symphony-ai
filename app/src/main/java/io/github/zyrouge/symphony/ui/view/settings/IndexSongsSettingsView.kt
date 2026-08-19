package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.search.IndexingState
import io.github.zyrouge.symphony.ui.components.*
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.hazeSource

@Serializable
object IndexSongsSettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexSongsSettingsView(context: ViewContext) {
    val snackbarHostState = remember { SnackbarHostState() }

    val allSongIds by context.symphony.groove.song.all.collectAsState()
    val repository = context.symphony.semanticSearch.repository
    val isReady by context.symphony.semanticSearch.isReady.collectAsState()
    val indexingState by context.symphony.semanticSearch.indexingState.collectAsState()

    var unembeddedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedSongs by remember { mutableStateOf<Set<String>>(emptySet()) }

    var filterQuery by remember { mutableStateOf("") }
    val visibleSongs = remember(unembeddedSongs, filterQuery) {
        if (filterQuery.isBlank()) unembeddedSongs
        else unembeddedSongs.filter {
            it.title.contains(filterQuery, true) ||
                it.artists.joinToString().contains(filterQuery, true)
        }
    }
    val indexedCount = remember(unembeddedSongs) {
        context.symphony.semanticSearch.indexedTrackCount()
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Refresh the list of unindexed songs whenever indexing finishes or is cancelled
    LaunchedEffect(allSongIds, isReady, indexingState.isActive) {
        if (!isReady || repository == null || indexingState.isActive) return@LaunchedEffect
        val filtered = allSongIds
            .mapNotNull { id -> context.symphony.groove.song.get(id) }
            .filter { song ->
                !repository.isTrackEmbedded(
                    title = song.title,
                    artist = song.artists.joinToString(),
                    durationMs = song.duration
                )
            }
        unembeddedSongs = filtered
        selectedSongs = selectedSongs.intersect(filtered.map { it.id }.toSet())
    }

    // Completion snackbar (only when going from active to inactive)
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(indexingState.isActive) {
        if (wasActive && !indexingState.isActive && indexingState.total > 0) {
            val ok = indexingState.current - indexingState.failedCount
            snackbarHostState.showSnackbar(
                if (indexingState.failedCount == 0) "Finished indexing $ok songs!"
                else "Indexed $ok songs, ${indexingState.failedCount} failed"
            )
        }
        wasActive = indexingState.isActive
    }

    GlassSettingsScaffold(
        context = context,
        title = "Manage AI Index",
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                AdaptiveSnackbar(it)
            }
        },
        floatingActionButton = {
            if (!indexingState.isActive && selectedSongs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val songsToProcess = unembeddedSongs.filter { selectedSongs.contains(it.id) }
                        context.symphony.semanticSearch.startIndexing(songsToProcess)
                        selectedSongs = emptySet()
                    },
                    icon = { Icon(Icons.Filled.AutoAwesome, null) },
                    text = { Text("Index ${selectedSongs.size} songs") },
                )
            }
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Non-blocking progress card
            if (indexingState.isActive) {
                item {
                    IndexingProgressCard(
                        state = indexingState,
                        onCancel = { context.symphony.semanticSearch.cancelIndexing() },
                    )
                }
            }

            // Summary of the last run (shown when it finished with failures)
            if (!indexingState.isActive && indexingState.total > 0 && indexingState.failedCount > 0) {
                item {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Last run: ${indexingState.current - indexingState.failedCount} indexed, " +
                                        "${indexingState.failedCount} failed",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { context.symphony.semanticSearch.clearIndexingResult() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    val failedSongs = indexingState.failedSongIds
                                        .mapNotNull { context.symphony.groove.song.get(it) }
                                    context.symphony.semanticSearch.startIndexing(failedSongs)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Retry failed songs")
                            }
                        }
                    }
                }
            }

            item {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "$indexedCount indexed • ${unembeddedSongs.size} remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Filter songs…") },
                        singleLine = true,
                    )
                }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${visibleSongs.size} shown",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        enabled = !indexingState.isActive,
                        onClick = {
                            val visibleIds = visibleSongs.map { it.id }.toSet()
                            selectedSongs = if (selectedSongs.containsAll(visibleIds) && visibleIds.isNotEmpty()) {
                                selectedSongs - visibleIds
                            } else {
                                selectedSongs + visibleIds
                            }
                        }
                    ) {
                        val visibleIds = visibleSongs.map { it.id }.toSet()
                        Text(
                            if (selectedSongs.containsAll(visibleIds) && visibleIds.isNotEmpty()) "Deselect All" else "Select All"
                        )
                    }
                }
            }

            items(visibleSongs, key = { it.id }) { song ->
                val isSelected = selectedSongs.contains(song.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isSelected,
                            enabled = !indexingState.isActive,
                            onValueChange = { checked ->
                                selectedSongs = if (checked) selectedSongs + song.id
                                else selectedSongs - song.id
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        enabled = !indexingState.isActive,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        SongCard(
                            context = context,
                            song = song,
                            disableHeartIcon = true,
                            onClick = {
                                if (indexingState.isActive) return@SongCard
                                selectedSongs = if (isSelected) selectedSongs - song.id
                                else selectedSongs + song.id
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexingProgressCard(
    state: IndexingState,
    onCancel: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Indexing ${state.current} / ${state.total}",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, "Cancel")
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (state.total > 0) state.current.toFloat() / state.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.currentTitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Estimated time remaining
            if (state.current > 0) {
                val elapsed = System.currentTimeMillis() - state.startedAt
                val avgPerSong = elapsed / state.current
                val remainingMin = (avgPerSong * (state.total - state.current)) / 60000
                Text(
                    if (remainingMin < 1) "Less than a minute left"
                    else "About $remainingMin min left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "You can leave this screen — indexing continues in background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.failedCount > 0) {
                Text(
                    "${state.failedCount} failed so far",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
