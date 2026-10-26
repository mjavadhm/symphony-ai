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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
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
    var lineSnapshot by remember { mutableStateOf<CompanionSnapshot?>(null) }
    val revisions by remember(profileId) {
        if (profileId.isEmpty()) flowOf(emptyList<CompanionRevision>()) else companion.dao.revisions(profileId)
    }.collectAsState(initial = emptyList())
    val acceptedTranslation = remember(revisions, lineSnapshot?.hash, preferences.language) { revisions.firstOrNull {
        it.section == "TRANSLATION" && it.language == preferences.language && it.state == "saved" &&
            CompanionCore.decodeSnapshot(it.snapshot).hash == lineSnapshot?.hash
    } }
    val translations = remember(acceptedTranslation, lineSnapshot) {
        val translatedLines = CompanionCore.translations(acceptedTranslation?.payload)
        lineSnapshot?.lines?.mapNotNull { line -> translatedLines[line.id]?.let { line.index to it } }?.toMap().orEmpty()
    }
    val annotated = remember(revisions, lineSnapshot, preferences.language) {
        val noteIds = revisions.filter {
            it.section == "NOTE" && it.language == preferences.language && it.state == "saved" &&
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
                        profileId = ""; lineSnapshot = null
                        val raw = withContext(Dispatchers.IO) { song?.let { context.symphony.groove.song.getLyrics(it) } }
                        lyrics = raw?.let { TimedContent.fromLyrics(it) }
                        lyricsState = 2
                        if (song != null && raw != null) {
                            try {
                                val snapshot = CompanionCore.snapshot(raw)
                                val profile = companion.resolve(song, raw)
                                profileId = profile.id; lineSnapshot = snapshot
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { /* Too long/ambiguous lyrics still play normally. Open Companion to resolve. */ }
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
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled = song != null, onClick = { song?.let { context.navController.navigate(SongCompanionRoute(songId = it.id)) } }) { Text("✦ Song Companion") }
            if (acceptedTranslation != null) TextButton(onClick = {
                companion.configure(mode = if (preferences.mode == "ORIGINAL") "BILINGUAL" else "ORIGINAL")
            }) { Text(if (preferences.mode == "ORIGINAL") "Show translation" else "Original only") }
        }
        AnimatedContent(
            modifier = Modifier.weight(1f),
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
                    }
                }
            }
        }
    }
}
