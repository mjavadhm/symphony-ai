package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object LibraryStatsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryStatsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()

    val allSongIds by context.symphony.groove.song.all.collectAsState()
    val mixes by context.symphony.database.customMixes.getAll()
        .collectAsState(initial = emptyList())

    var indexedCount by remember { mutableLongStateOf(0L) }
    var flowCount by remember { mutableIntStateOf(0) }
    var historyCount by remember { mutableIntStateOf(0) }
    var lyricsCount by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        indexedCount = context.symphony.semanticSearch.indexedTrackCount()
        flowCount = context.symphony.database.trackFlow.count()
        historyCount = context.symphony.database.playbackHistory.getAllHistory().size
        lyricsCount = context.symphony.database.lyricsCache.keys().size
        loaded = true
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    val totalSongs = allSongIds.size

    GlassSettingsScaffold(
        context,
        title = "Library Stats",
        topBarActions = {
            io.github.zyrouge.symphony.ui.components.GlassSurface(
                modifier = Modifier.width(44.dp).padding(4.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                IconButton(onClick = { coroutineScope.launch { reload() } }) {
                    Icon(Icons.Filled.Refresh, null)
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatRow(
                icon = "🎵",
                label = "Songs in library",
                value = "$totalSongs",
            )
            StatRow(
                icon = "🧠",
                label = "AI-indexed songs",
                value = if (loaded) "$indexedCount / $totalSongs" else "…",
                note = "Used by search, mixes, autoplay and smart shuffle",
            )
            StatRow(
                icon = "🌊",
                label = "Flow-analyzed songs",
                value = if (loaded) "$flowCount / $totalSongs" else "…",
                note = "Used for smooth track transitions",
            )
            StatRow(
                icon = "▶️",
                label = "Playback history records",
                value = if (loaded) "$historyCount" else "…",
                note = "Plays and skips collected so far",
            )
            StatRow(
                icon = "🎛️",
                label = "Custom mixes",
                value = "${mixes.size}",
            )
            StatRow(
                icon = "📝",
                label = "Cached lyrics",
                value = if (loaded) "$lyricsCount" else "…",
                note = "Lyrics fetched so far, not every song that has lyrics",
            )
        }
    }
}

@Composable
private fun StatRow(
    icon: String,
    label: String,
    value: String,
    note: String? = null,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
