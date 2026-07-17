package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.components.AddToPlaylistDialog
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixesSection(context: ViewContext) {
    val isReady by context.symphony.semanticSearch.isReady.collectAsState()
    if (!isReady) return

    val coroutineScope = rememberCoroutineScope()
    val mixes by context.symphony.database.customMixes.getAll()
        .collectAsState(initial = emptyList())

    var dailyMixIds by remember { mutableStateOf<List<String>?>(null) }
    var openedMix by remember { mutableStateOf<CustomMix?>(null) }
    var openedDailyMix by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<CustomMix?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        context.symphony.recommendation.seedDefaultMixesIfNeeded()
        dailyMixIds = context.symphony.recommendation.getDailyMix()
    }

    Column {
        // ---- Daily Mix (فقط وقتی داده کافی هست) ----
        dailyMixIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            Box(modifier = Modifier.padding(20.dp, 0.dp)) {
                DailyMixCard(
                    context = context,
                    songIds = ids,
                    onPlay = {
                        context.symphony.radio.playbackSource = "daily_mix"
                        context.symphony.radio.shorty.playQueue(ids)
                    },
                    onOpen = { openedDailyMix = true },
                    onRefresh = {
                        coroutineScope.launch {
                            dailyMixIds =
                                context.symphony.recommendation.getDailyMix(forceRefresh = true)
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ---- Mood Mixes ----
        Box(modifier = Modifier.padding(20.dp, 0.dp)) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge) { Text("Mood Mixes") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(modifier = Modifier.width(12.dp))
            mixes.forEach { mix ->
                MoodMixCard(
                    mix = mix,
                    onClick = { openedMix = mix },
                    onEdit = { editorTarget = mix; showEditor = true },
                )
            }
            NewMixCard { editorTarget = null; showEditor = true }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // ---- Sheets & Dialogs ----
    openedMix?.let { mix ->
        MixSheet(
            context = context,
            title = "${mix.icon} ${mix.name}",
            source = "mood_mix",
            loadSongIds = { context.symphony.recommendation.getMixSongIds(mix) },
            onDismiss = { openedMix = null },
        )
    }
    if (openedDailyMix) {
        MixSheet(
            context = context,
            title = "🌅 Daily Mix",
            source = "daily_mix",
            loadSongIds = { dailyMixIds ?: emptyList() },
            onDismiss = { openedDailyMix = false },
        )
    }
    if (showEditor) {
        MixEditorDialog(
            context = context,
            existing = editorTarget,
            onDismiss = { showEditor = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyMixCard(
    context: ViewContext,
    songIds: List<String>,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
) {
    val coverSong = songIds.firstNotNullOfOrNull { context.symphony.groove.song.get(it) }
    val backgroundColor = MaterialTheme.colorScheme.surface
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        onClick = onOpen,
    ) {
        Box {
            coverSong?.let {
                AsyncImage(
                    it.createArtworkImageRequest(context.symphony).build(),
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.5f),
                                backgroundColor.copy(alpha = 0.85f),
                            ),
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, null)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Daily Mix",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    Text(
                        "بر اساس شنیده‌های اخیرت · ${songIds.size} آهنگ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoodMixCard(
    mix: CustomMix,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.size(width = 120.dp, height = 120.dp),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(mix.icon, style = MaterialTheme.typography.headlineMedium)
                Text(
                    mix.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Edit, null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMixCard(onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.size(width = 120.dp, height = 120.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, null)
            Spacer(modifier = Modifier.height(8.dp))
            Text("New Mix", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixSheet(
    context: ViewContext,
    title: String,
    source: String,
    loadSongIds: suspend () -> List<String>,
    onDismiss: () -> Unit,
) {
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        songs = loadSongIds().mapNotNull { context.symphony.groove.song.get(it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                songs?.takeIf { it.isNotEmpty() }?.let { list ->
                    TextButton(onClick = { showAddToPlaylist = true }) { Text("Save") }
                    Button(onClick = {
                        context.symphony.radio.playbackSource = source
                        context.symphony.radio.shorty.playQueue(list.map { it.id })
                        onDismiss()
                    }) { Text("Play") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (val list = songs) {
                null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> when {
                    list.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "نتیجه‌ای پیدا نشد — prompt رو تغییر بده",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(list) { song ->
                            SongCard(context = context, song = song, onClick = {
                                context.symphony.radio.playbackSource = source
                                context.symphony.radio.shorty.playQueue(
                                    list.map { it.id },
                                    options = io.github.zyrouge.symphony.services.radio.Radio.PlayOptions(
                                        index = list.indexOf(song),
                                    ),
                                )
                            })
                        }
                    }
                }
            }
        }
        if (showAddToPlaylist) {
            AddToPlaylistDialog(
                context = context,
                songIds = songs?.map { it.id } ?: emptyList(),
                onDismissRequest = { showAddToPlaylist = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixEditorDialog(
    context: ViewContext,
    existing: CustomMix?,
    onDismiss: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var icon by remember { mutableStateOf(existing?.icon ?: "🎵") }
    var trackCount by remember { mutableStateOf((existing?.trackCount ?: 25).toFloat()) }
    var preview by remember { mutableStateOf<List<Song>?>(null) }
    var isPreviewing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Mix" else "Edit Mix") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = icon,
                        onValueChange = { icon = it.take(2) },
                        modifier = Modifier.width(72.dp),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt (English works best)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Track count: ${trackCount.toInt()}")
                Slider(value = trackCount, onValueChange = { trackCount = it }, valueRange = 10f..100f)
                TextButton(
                    enabled = prompt.isNotBlank() && !isPreviewing,
                    onClick = {
                        coroutineScope.launch {
                            isPreviewing = true
                            preview = context.symphony.recommendation.getMixSongIds(
                                CustomMix(name = name, prompt = prompt, trackCount = 5)
                            ).mapNotNull { context.symphony.groove.song.get(it) }
                            isPreviewing = false
                        }
                    },
                ) { Text(if (isPreviewing) "..." else "Preview top 5") }
                preview?.forEach {
                    Text(
                        "· ${it.title} — ${it.artists.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    coroutineScope.launch {
                        val store = context.symphony.database.customMixes
                        when (existing) {
                            null -> store.insert(
                                CustomMix(name = name, prompt = prompt, icon = icon, trackCount = trackCount.toInt())
                            )
                            else -> store.update(
                                existing.copy(name = name, prompt = prompt, icon = icon, trackCount = trackCount.toInt())
                            )
                        }
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null && !existing.isBuiltIn) {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            context.symphony.database.customMixes.delete(existing)
                            onDismiss()
                        }
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
