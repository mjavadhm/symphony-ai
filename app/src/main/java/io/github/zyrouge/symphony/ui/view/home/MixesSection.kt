package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import io.github.zyrouge.symphony.ui.components.GlassSurface
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.services.database.entities.MixContext
import io.github.zyrouge.symphony.services.recommendation.RecommendationEngine
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
    val contexts by context.symphony.database.mixContexts.getAll()
        .collectAsState(initial = emptyList())

    var dailyMixes by remember { mutableStateOf<List<RecommendationEngine.DailyMix>?>(null) }
    var openedMix by remember { mutableStateOf<CustomMix?>(null) }
    var openedDaily by remember { mutableStateOf<RecommendationEngine.DailyMix?>(null) }
    var openedContext by remember { mutableStateOf<MixContext?>(null) }
    var editorTarget by remember { mutableStateOf<CustomMix?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val activeCtx = remember(contexts) {
        context.symphony.recommendation.activeContext(contexts)
    }

    LaunchedEffect(Unit) {
        context.symphony.recommendation.seedDefaultMixesIfNeeded()
        context.symphony.recommendation.seedDefaultContextsIfNeeded()
        dailyMixes = context.symphony.recommendation.getDailyMixes()
    }

    val refreshDaily: () -> Unit = {
        coroutineScope.launch {
            dailyMixes = context.symphony.recommendation.getDailyMixes(forceRefresh = true)
        }
    }

    Column {
        // ---- Daily Mixes ----
        dailyMixes?.takeIf { it.isNotEmpty() }?.let { list ->
            when (list.size) {
                1 -> Box(modifier = Modifier.padding(20.dp, 0.dp)) {
                    DailyMixCard(
                        context = context,
                        mix = list[0],
                        modifier = Modifier.fillMaxWidth(),
                        onPlay = { playMix(context, "daily_mix", list[0].songIds) },
                        onOpen = { openedDaily = list[0] },
                        onRefresh = refreshDaily,
                    )
                }

                else -> Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(modifier = Modifier.width(12.dp))
                    list.forEach { mix ->
                        DailyMixCard(
                            context = context,
                            mix = mix,
                            modifier = Modifier.width(300.dp),
                            onPlay = { playMix(context, "daily_mix", mix.songIds) },
                            onOpen = { openedDaily = mix },
                            onRefresh = refreshDaily,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ---- Context Mix (فقط وقتی ساعت فعلی توی یکی از بازههاست) ----
        activeCtx?.let { ctx ->
            Box(modifier = Modifier.padding(20.dp, 0.dp)) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openedContext = ctx }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ctx.icon, style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${ctx.name} Mix", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "What you usually listen to around this time",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.PlayArrow, null)
                    }
                }
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
                    context = context,
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
            subtitle = mix.description.takeIf { it.isNotBlank() },
            source = "mood_mix",
            loadSongIds = { context.symphony.recommendation.getMixSongIds(mix) },
            onDismiss = { openedMix = null },
            onReload = { context.symphony.recommendation.getMixSongIds(mix, reroll = true) },
        )
    }
    openedDaily?.let { mix ->
        MixSheet(
            context = context,
            title = "🌅 ${mix.name}",
            source = "daily_mix",
            loadSongIds = { mix.songIds },
            onDismiss = { openedDaily = null },
        )
    }
    openedContext?.let { ctx ->
        MixSheet(
            context = context,
            title = "${ctx.icon} ${ctx.name} Mix",
            source = "context_mix",
            loadSongIds = { context.symphony.recommendation.getContextMixSongIds(ctx) },
            onDismiss = { openedContext = null },
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

private fun playMix(context: ViewContext, source: String, songIds: List<String>) {
    context.symphony.radio.playbackSource = source
    context.symphony.radio.shorty.playQueue(songIds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyMixCard(
    context: ViewContext,
    mix: RecommendationEngine.DailyMix,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
) {
    GlassSurface(
        modifier = modifier.height(132.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpen),
        ) {
            MixCoverCollage(
                context = context,
                songIds = mix.songIds,
                modifier = Modifier
                    .width(132.dp)
                    .fillMaxHeight(),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        mix.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Based on your recent listening · ${mix.songIds.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, null)
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh, null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoodMixCard(
    context: ViewContext,
    mix: CustomMix,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    var coverIds by remember(mix.prompt) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(mix.prompt) {
        coverIds = context.symphony.recommendation.getMixCoverSongIds(mix)
    }
    ElevatedCard(
        modifier = Modifier.size(width = 120.dp, height = 120.dp),
        shape = RoundedCornerShape(24.dp),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MixCoverCollage(context, coverIds, Modifier.matchParentSize())
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.75f),
                            ),
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(mix.icon, style = MaterialTheme.typography.titleLarge)
                Text(
                    mix.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
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
                    tint = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMixCard(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.size(width = 120.dp, height = 120.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
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
fun MixSheet(
    context: ViewContext,
    title: String,
    source: String,
    loadSongIds: suspend () -> List<String>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onReload: (suspend () -> List<String>)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<io.github.zyrouge.symphony.services.groove.Song>?>(null) }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                onReload?.let { reload ->
                    IconButton(onClick = {
                        coroutineScope.launch {
                            songs = null
                            songs = reload().mapNotNull {
                                context.symphony.groove.song.get(it)
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, null)
                    }
                }
                songs?.takeIf { it.isNotEmpty() }?.let { list ->
                    TextButton(onClick = { showAddToPlaylist = true }) { Text("Save") }
                    Button(onClick = {
                        playMix(context, source, list.map { it.id })
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
                            "Not enough data for this mix yet",
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
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var promptsText by remember {
        mutableStateOf(
            existing?.let { mix ->
                if (mix.prompts.isNotBlank()) mix.prompts else mix.prompt
            } ?: ""
        )
    }
    var icon by remember { mutableStateOf(existing?.icon ?: "🎵") }
    var trackCount by remember { mutableStateOf((existing?.trackCount ?: 25).toFloat()) }
    var preview by remember { mutableStateOf<List<io.github.zyrouge.symphony.services.groove.Song>?>(null) }
    var isPreviewing by remember { mutableStateOf(false) }

    fun promptLines() = promptsText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

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
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (what is this mix for?)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = promptsText,
                    onValueChange = { promptsText = it },
                    label = { Text("Prompts — one per line, English works best") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Track count: ${trackCount.toInt()}")
                Slider(value = trackCount, onValueChange = { trackCount = it }, valueRange = 10f..100f)
                TextButton(
                    enabled = promptLines().isNotEmpty() && !isPreviewing,
                    onClick = {
                        coroutineScope.launch {
                            isPreviewing = true
                            preview = context.symphony.recommendation.getMixSongIds(
                                CustomMix(
                                    name = name,
                                    prompt = promptLines().first(),
                                    prompts = promptsText,
                                    trackCount = 5,
                                )
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
                enabled = name.isNotBlank() && promptLines().isNotEmpty(),
                onClick = {
                    coroutineScope.launch {
                        val store = context.symphony.database.customMixes
                        val firstPrompt = promptLines().first()
                        when (existing) {
                            null -> store.insert(
                                CustomMix(
                                    name = name,
                                    prompt = firstPrompt,
                                    prompts = promptsText.trim(),
                                    description = description.trim(),
                                    icon = icon,
                                    trackCount = trackCount.toInt(),
                                )
                            )
                            else -> store.update(
                                existing.copy(
                                    name = name,
                                    prompt = firstPrompt,
                                    prompts = promptsText.trim(),
                                    description = description.trim(),
                                    icon = icon,
                                    trackCount = trackCount.toInt(),
                                )
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

@Composable
fun MixCoverCollage(
    context: ViewContext,
    songIds: List<String>,
    modifier: Modifier = Modifier,
) {
    val songs = remember(songIds) {
        songIds.mapNotNull { context.symphony.groove.song.get(it) }.take(4)
    }
    Box(modifier = modifier) {
        // پسزمینه گرادیان — فقط وقتی دیده میشه که هیچ کاوری نباشه
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    )
                )
        )
        when {
            songs.size >= 4 -> Column(modifier = Modifier.matchParentSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    CollageTile(context, songs[0], Modifier.weight(1f))
                    CollageTile(context, songs[1], Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f)) {
                    CollageTile(context, songs[2], Modifier.weight(1f))
                    CollageTile(context, songs[3], Modifier.weight(1f))
                }
            }
            songs.isNotEmpty() -> CollageTile(context, songs[0], Modifier.matchParentSize())
        }
    }
}

@Composable
private fun CollageTile(
    context: ViewContext,
    song: io.github.zyrouge.symphony.services.groove.Song,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        song.createArtworkImageRequest(context.symphony).build(),
        null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}
