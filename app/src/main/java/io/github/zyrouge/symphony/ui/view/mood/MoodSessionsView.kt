package io.github.zyrouge.symphony.ui.view.mood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.mood.*
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.ReadableText
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.NowPlayingViewRoute
import io.github.zyrouge.symphony.ui.view.settings.IndexSongsSettingsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object MoodSessionsRoute

@Composable
fun MoodSessionsView(context: ViewContext) {
    val service = context.symphony.moods
    val state by service.state.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val playing by context.symphony.radio.observatory.isPlaying.collectAsState()
    var picker by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<MoodProfile?>(null) }
    var deleting by remember { mutableStateOf<MoodProfile?>(null) }
    val profile = state.profile
    val run = state.run
    val currentIndex = if (state.attached) queueIndex else run?.index ?: 0
    val current = run?.queue?.getOrNull(currentIndex)

    GlassSettingsScaffold(context, "Mood Session · Experimental", topBarActions = {
        if (profile != null) TextButton(onClick = { settings = true }) { Text("Settings") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Find what fits right now", style = MaterialTheme.typography.headlineSmall)
                Text("Choose a few songs. Your reactions shape what comes next. Uses analyzed music on this device; no AI provider needed.")
                Button(enabled = !state.busy, onClick = { picker = true }) { Text("New mood") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
            }
            if (profile != null && run != null) {
                item {
                    HorizontalDivider()
                    ReadableText(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (state.attached) "Current session · changes are saved automatically" else "Session saved on this device")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { naming = profile }) { Text(if (profile.saved) "Rename" else "Save mood") }
                        TextButton(enabled = !state.busy, onClick = { service.resume(profile.id, fresh = true) }) { Text("Start fresh") }
                        if (state.attached) TextButton(onClick = service::normalPlayback) { Text("Normal playback") }
                    }
                }
                if (current != null) item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Current song", style = MaterialTheme.typography.labelLarge)
                        ReadableText(current.track.title, style = MaterialTheme.typography.titleMedium)
                        ReadableText(current.track.artists.joinToString(), style = MaterialTheme.typography.bodySmall)
                        MoodReactionButtons(context, profile, current.track)
                        Row {
                            Button(onClick = {
                                if (state.attached) context.symphony.radio.shorty.playPause() else service.resume(profile.id)
                            }) { Text(if (state.attached && playing) "Pause" else "Continue") }
                            TextButton(enabled = state.attached, onClick = { context.symphony.radio.shorty.skip() }) { Text("Next") }
                            TextButton(onClick = { context.navController.navigate(NowPlayingViewRoute) }) { Text("Open player") }
                        }
                    } }
                }
                item {
                    Text("Reactions also tune your general recommendations. “Not now” records your opinion without skipping. Shuffle and repeat are unavailable while this mood controls playback.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Up next", style = MaterialTheme.typography.titleMedium)
                }
                items(run.queue.drop(currentIndex + 1), key = { "queue:${it.id}" }) { entry ->
                    ListItem(headlineContent = { ReadableText(entry.track.title) },
                        supportingContent = { Text(when (entry.kind) { "seed" -> "Your starting choice"; "manual" -> "Added by you"; else -> "Suggested for this mood" }) })
                }
                if (state.exhausted) item {
                    Text("No more matching songs in this run. Start fresh for a new queue with the same learned mood.")
                }
                val visits = state.visits.filter { it.runId == run.id }.asReversed()
                if (visits.isNotEmpty()) item { Text("Recently played · you can still react after skipping", style = MaterialTheme.typography.titleMedium) }
                items(visits, key = { "visit:${it.id}" }) { visit ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        ReadableText(visit.track.title, style = MaterialTheme.typography.titleSmall)
                        ReadableText(visit.track.artists.joinToString(), style = MaterialTheme.typography.bodySmall)
                        MoodReactionButtons(context, profile, visit.track)
                    } }
                }
            }
            item { HorizontalDivider(); Text("Your moods", style = MaterialTheme.typography.titleLarge) }
            if (state.profiles.isEmpty()) item { Text("Your latest session is kept automatically. Save a name when you find a mood you want to return to.") }
            items(state.profiles, key = { "profile:${it.id}" }) { saved ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    ReadableText(saved.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (saved.saved) "Saved mood" else "Recent session", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !state.busy, onClick = { service.resume(saved.id) }) { Text("Continue") }
                        TextButton(enabled = !state.busy, onClick = { service.resume(saved.id, fresh = true) }) { Text("Fresh queue") }
                        TextButton(enabled = !state.busy, onClick = { naming = saved }) { Text(if (saved.saved) "Rename" else "Save") }
                        TextButton(enabled = !state.busy, onClick = { deleting = saved }) { Text("Delete") }
                    }
                } }
            }
        }
    }
    if (picker) MoodSeedPicker(context, dismiss = { picker = false }) { ids -> picker = false; service.create(ids) }
    if (settings && profile != null) MoodSettings(profile, dismiss = { settings = false }) { discovery, days ->
        settings = false; service.configure(profile.id, discovery, days)
    }
    naming?.let { selected ->
        var name by remember(selected.id) { mutableStateOf(if (selected.saved) selected.name else "") }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text("Save mood") },
            text = { Column { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }); Text("Your reactions and settings keep learning automatically.") } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { service.rename(selected.id, name); naming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } })
    }
    deleting?.let { selected -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this mood?") },
        text = { Text("Deletes its sessions and reactions. Its reactions will also stop contributing to general recommendations. Listening history is kept.") },
        confirmButton = { TextButton(onClick = { service.delete(selected.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
fun MoodPlayerReactions(context: ViewContext, songId: String) {
    val state by context.symphony.moods.state.collectAsState()
    val profile = state.profile ?: return
    if (!state.attached) return
    val tag = context.symphony.moods.playbackTag(songId) ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = { context.navController.navigate(MoodSessionsRoute) }) { Text("Mood session · ${profile.name}") }
        MoodReactionButtons(context, profile, tag.track)
    }
}

@Composable
private fun MoodReactionButtons(context: ViewContext, profile: MoodProfile, track: MoodTrackRef) {
    val reaction = profile.reactions[track.key]?.value
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = reaction == 1,
            onClick = { context.symphony.moods.react(profile.id, track, if (reaction == 1) null else 1) },
            label = { Text("Good for now") }, leadingIcon = { Icon(Icons.Filled.ThumbUp, null, Modifier.size(16.dp)) })
        FilterChip(selected = reaction == -1,
            onClick = { context.symphony.moods.react(profile.id, track, if (reaction == -1) null else -1) },
            label = { Text("Not now") }, leadingIcon = { Icon(Icons.Filled.ThumbDown, null, Modifier.size(16.dp)) })
    }
}

@Composable
private fun MoodSettings(profile: MoodProfile, dismiss: () -> Unit, save: (Float, Int) -> Unit) {
    var discovery by remember(profile.id) { mutableFloatStateOf(profile.discovery) }
    var days by remember(profile.id) { mutableIntStateOf(profile.familiarDays) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("This mood's settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Discovery: ${(discovery * 100).toInt()}%")
            Slider(discovery, { discovery = it }, valueRange = 0f..1f, steps = 9)
            Text("Familiar ← → Discovery")
            Text("Familiar means listened to recently. Discovery is music on this device you haven't heard within this window.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(3, 7, 14).forEach { n ->
                FilterChip(selected = days == n, onClick = { days = n }, label = { Text("$n days") })
            } }
            Text("This mood's learned reactions do not fade with time. The requested mix is approximate when a group has too few matches.")
        }
    }, confirmButton = { TextButton(onClick = { save(discovery, days) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun MoodSeedPicker(context: ViewContext, dismiss: () -> Unit, start: (List<String>) -> Unit) {
    val allIds by context.symphony.groove.song.all.collectAsState()
    val indexing by context.symphony.semanticSearch.indexingState.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var rows by remember { mutableStateOf<List<Pair<Song, Boolean>>>(emptyList()) }
    LaunchedEffect(query, allIds, indexing.isActive) {
        rows = withContext(Dispatchers.IO) {
            context.symphony.groove.song.get(allIds).filter { song ->
                query.isBlank() || song.title.contains(query, true) || song.artists.any { it.contains(query, true) }
            }.sortedBy { it.title.lowercase() }.take(40).map { song -> song to
                (context.symphony.semanticSearch.getMoodEmbedding(song.path, song.title, song.artists.joinToString(), song.duration) != null)
            }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Choose 1–5 starting songs") }, text = {
        Column(Modifier.heightIn(max = 520.dp)) {
            Text("Three is a good start. These songs play first, in the order below.")
            selected.forEachIndexed { index, id -> context.symphony.groove.song.get(id)?.let { song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReadableText("${index + 1}. ${song.title}", Modifier.weight(1f))
                    TextButton(enabled = index > 0, onClick = {
                        selected = selected.toMutableList().apply { add(index - 1, removeAt(index)) }
                    }) { Text("↑") }
                    TextButton(onClick = { selected = selected - id }) { Text("×") }
                }
            } }
            OutlinedTextField(query, { query = it }, label = { Text("Search your library") }, singleLine = true)
            TextButton(onClick = { dismiss(); context.navController.navigate(IndexSongsSettingsRoute) }) { Text("Analyze songs first") }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(rows, key = { it.first.id }) { (song, indexed) ->
                    ListItem(headlineContent = { ReadableText(song.title) }, supportingContent = {
                        Text(if (indexed) song.artists.joinToString() else "Not analyzed yet")
                    }, leadingContent = {
                        Checkbox(checked = song.id in selected, enabled = indexed && (selected.size < 5 || song.id in selected),
                            onCheckedChange = { checked -> selected = if (checked) selected + song.id else selected - song.id })
                    })
                }
            }
        }
    }, confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { start(selected) }) { Text("Start mood") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
