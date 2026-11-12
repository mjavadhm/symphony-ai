package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import io.github.zyrouge.symphony.ui.view.companion.CompanionSettingsRoute
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.zyrouge.symphony.ui.view.companion.InlineSongAnalysis
import io.github.zyrouge.symphony.ui.view.settings.LyricsSettingsViewRoute
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.view.companion.SongCompanionRoute
import kotlinx.coroutines.flow.flowOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.zyrouge.symphony.services.radio.RadioPlayer
import io.github.zyrouge.symphony.ui.helpers.FadeTransition
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.TimedContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.timer

@Composable
fun LyricsText(
    context: ViewContext,
    padding: PaddingValues,
    style: TimedContentTextStyle,
) {
    val coroutineScope = rememberCoroutineScope()
    var playbackPosition by remember {
        mutableStateOf(
            context.symphony.radio.currentPlaybackPosition ?: RadioPlayer.PlaybackPosition.zero
        )
    }
    var playbackPositionTimer: Timer? = remember { null }
    val lyricsRevision by context.symphony.lyricsManager.revision.collectAsState()
    val queue by context.symphony.radio.observatory.queue.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val song by remember(queue, queueIndex) {
        derivedStateOf {
            queue.getOrNull(queueIndex)?.let { context.symphony.groove.song.get(it) }
        }
    }
    var lyricsState by remember { mutableIntStateOf(0) }
    var lyricsSongId by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<TimedContent?>(null) }

    val companion = context.symphony.songCompanion
    val preferences by companion.preferences.collectAsState()
    var profileId by remember { mutableStateOf("") }
    var profileError by remember { mutableStateOf("") }
    var showAnalysis by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var storyRequest by remember(song?.id) { mutableStateOf<String?>(null) }
    var lineSnapshot by remember { mutableStateOf<CompanionSnapshot?>(null) }
    val revisions by remember(profileId) {
        if (profileId.isEmpty()) flowOf(emptyList<CompanionRevision>()) else companion.dao.revisions(profileId)
    }.collectAsState(initial = emptyList())
    val acceptedTranslation = remember(profileId, revisions, lineSnapshot?.hash, preferences.language) { revisions.firstOrNull {
        it.trackId == profileId && it.section == "TRANSLATION" && it.language == preferences.language && it.state == "saved" &&
            CompanionCore.decodeSnapshot(it.snapshot).hash == lineSnapshot?.hash
    } }
    val translations = remember(acceptedTranslation, lineSnapshot) {
        val translatedLines = CompanionCore.translations(acceptedTranslation?.payload)
        lineSnapshot?.lines?.mapNotNull { line -> translatedLines[line.id]?.let { line.index to it } }?.toMap().orEmpty()
    }
    val annotated = remember(profileId, revisions, lineSnapshot, preferences.language) {
        val noteIds = revisions.filter {
            it.trackId == profileId && it.section == "NOTE" && it.language == preferences.language && it.state == "saved" &&
                CompanionCore.decodeSnapshot(it.snapshot).hash == lineSnapshot?.hash
        }.map { it.target }.toSet()
        lineSnapshot?.lines?.filter { it.id in noteIds }?.map { it.index }?.toSet().orEmpty()
    }

    LaunchedEffect(LocalContext.current) {
        awaitAll(
            async {
                playbackPositionTimer = timer(period = 50L) {
                    playbackPosition = context.symphony.radio.currentPlaybackPosition
                        ?: RadioPlayer.PlaybackPosition.zero
                }
            },
            async {
                snapshotFlow { song to lyricsRevision }
                    .distinctUntilChanged()
                    .collectLatest { (song, _) ->
                        lyricsState = 1
                        lyricsSongId = song?.id
                        lyrics = null
                        profileId = ""; profileError = ""; lineSnapshot = null
                        val raw = withContext(Dispatchers.IO) { song?.let { context.symphony.groove.song.getLyrics(it) } }
                        lyrics = raw?.takeIf { it.isNotBlank() }?.let { TimedContent.fromLyrics(it) }?.takeIf { parsed -> parsed.pairs.any { it.second.isNotBlank() } }
                        lyricsState = 2
                        if (song != null) {
                            try {
                                val snapshot = CompanionCore.snapshot(raw.orEmpty())
                                val profile = companion.resolve(song, raw.orEmpty())
                                profileId = profile.id; lineSnapshot = snapshot
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { profileError = e.message ?: "Could not load dossier. Open the full editor to resolve the match." }
                        }
                    }
            }
        )
    }

    DisposableEffect(LocalContext.current) {
        onDispose {
            playbackPositionTimer?.cancel()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Text tabs instead of chunky filter chips; both retain accessible touch targets.
            listOf(false to "Lyrics", true to "Behind the song").forEach { (analysis, label) ->
                Column {
                    TextButton(onClick = { showAnalysis = analysis }, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current.copy(alpha = if (showAnalysis == analysis) 1f else 0.55f))) {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (showAnalysis == analysis) FontWeight.Bold else FontWeight.Normal)
                    }
                    if (showAnalysis == analysis) HorizontalDivider(Modifier.padding(horizontal = 12.dp).size(width = if (analysis) 104.dp else 40.dp, height = 2.dp), color = LocalContentColor.current)
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Lyrics options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (acceptedTranslation != null) DropdownMenuItem(text = { Text(if (preferences.mode == "ORIGINAL") "Show translation" else "Original only") }, onClick = {
                        menu = false; companion.configure(mode = if (preferences.mode == "ORIGINAL") "BILINGUAL" else "ORIGINAL")
                    })
                    if (showAnalysis) {
                        DropdownMenuItem(text = { Text("Ask / correct") }, onClick = { menu = false; storyRequest = "chat" })
                        DropdownMenuItem(text = { Text("Rewrite the story") }, onClick = { menu = false; storyRequest = "rewrite" })
                    }
                    DropdownMenuItem(text = { Text("Translation, edits & history") }, onClick = {
                        menu = false; song?.let { context.navController.navigate(SongCompanionRoute(songId = it.id)) }
                    })
                    DropdownMenuItem(text = { Text("Story prompt & settings") }, onClick = { menu = false; context.navController.navigate(CompanionSettingsRoute) })
                }
            }
        }
        if (showAnalysis) {
            song?.let { InlineSongAnalysis(context, it.id, if (lyricsSongId == it.id) profileId else "", if (lyricsSongId == it.id) profileError else "", padding, request = storyRequest, onRequestHandled = { storyRequest = null }) }
        } else {
            AnimatedContent(
                modifier = Modifier.weight(1f)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
                label = "lyrics-text",
                targetState = lyricsState to lyrics,
                transitionSpec = {
                    FadeTransition.enterTransition()
                        .togetherWith(FadeTransition.exitTransition())
                },
            ) { targetState ->
                val targetLyricsState = targetState.first
                val targetLyrics = targetState.second

                when {
                    targetLyricsState == 2 && targetLyrics != null -> TimedContentText(
                        content = targetLyrics,
                        duration = playbackPosition.played,
                        padding = padding,
                        style = style,
                        translations = if (lyricsSongId == song?.id) translations else emptyMap(),
                        displayMode = preferences.mode,
                        annotated = annotated,
                        onLongPress = { index ->
                            val line = lineSnapshot?.lines?.firstOrNull { it.index == index }
                            if (lyricsSongId == song?.id && line != null) song?.let {
                                context.navController.navigate(SongCompanionRoute(songId = it.id, lineId = line.id))
                            }
                        },
                        onSeek = {
                            targetLyrics.pairs.getOrNull(it)?.first?.let { to ->
                                context.symphony.radio.seek(to)
                            }
                        }
                    )

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                null,
                                tint = LocalContentColor.current.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                if (targetLyricsState == 1) context.symphony.t.Loading
                                else context.symphony.t.NoLyrics,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                            )
                            if (targetLyricsState == 2 && lyricsSongId == song?.id) TextButton(onClick = {
                                song?.let { context.navController.navigate(LyricsSettingsViewRoute(songId = it.id)) }
                            }) { Text("Find lyrics") }
                        }
                    }
                }
            }
        }
    }
}
