package io.github.zyrouge.symphony.ui.view.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import io.github.zyrouge.symphony.services.groove.repositories.SongRepository
import io.github.zyrouge.symphony.utils.subListNonStrict
import kotlinx.coroutines.flow.first
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.zyrouge.symphony.services.groove.MediaExposer
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.ui.components.LoaderScaffold
import io.github.zyrouge.symphony.ui.components.NewPlaylistDialog
import io.github.zyrouge.symphony.ui.components.PlaylistGrid
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.ActivityUtils
import io.github.zyrouge.symphony.utils.Logger

@Composable
fun PlaylistsView(context: ViewContext) {
    val isUpdating by context.symphony.groove.playlist.isUpdating.collectAsState()
    val playlists by context.symphony.groove.playlist.all.collectAsState()
    val playlistsCount by context.symphony.groove.playlist.count.collectAsState()
    var showPlaylistCreator by remember { mutableStateOf(false) }

    val openPlaylistLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { x ->
            try {
                ActivityUtils.makePersistableReadableUri(context.symphony.applicationContext, x)
                val playlist = Playlist.parse(context.symphony, null, x)
                context.symphony.groove.playlist.add(playlist)
            } catch (err: Exception) {
                Logger.error("PlaylistView", "import failed (activity result)", err)
                Toast.makeText(
                    context.symphony.applicationContext,
                    context.symphony.t.InvalidM3UFile,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    LoaderScaffold(context, isLoading = isUpdating) {
        PlaylistGrid(
            context,
            playlistIds = playlists,
            playlistsCount = playlistsCount,
            leadingContent = {
                PlaylistControlBar(
                    context,
                    showPlaylistCreator = {
                        showPlaylistCreator = true
                    },
                    showPlaylistPicker = {
                        openPlaylistLauncher.launch(arrayOf(MediaExposer.MIMETYPE_M3U))
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SmartCollectionsRow(context)
            }
        )
    }

    if (showPlaylistCreator) {
        NewPlaylistDialog(
            context,
            onDone = { playlist ->
                showPlaylistCreator = false
                context.symphony.groove.playlist.add(playlist)
            },
            onDismissRequest = {
                showPlaylistCreator = false
            }
        )
    }
}

@Composable
private fun PlaylistControlBar(
    context: ViewContext,
    showPlaylistCreator: () -> Unit,
    showPlaylistPicker: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(20.dp, 0.dp),
    ) {
        ElevatedButton(
            modifier = Modifier.weight(1f),
            onClick = showPlaylistCreator,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Add,
                    null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(context.symphony.t.NewPlaylist)
            }
        }
        ElevatedButton(
            modifier = Modifier.weight(1f),
            onClick = showPlaylistPicker,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ImportExport,
                    null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(context.symphony.t.ImportPlaylist)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartCollectionsRow(context: ViewContext) {
    var opened by remember { mutableStateOf<String?>(null) }
    var mostIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var addedIds by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        mostIds = context.symphony.database.playbackHistory.getMostPlayedSongs(4).first()
        recentIds = context.symphony.database.playbackHistory.getRecentlyPlayedSongs(4).first()
        addedIds = context.symphony.groove.song.sort(
            context.symphony.groove.song.all.value.toList(),
            SongRepository.SortBy.DATE_MODIFIED,
            true,
        ).subListNonStrict(4)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(20.dp, 0.dp),
    ) {
        SmartCard(context, "⭐", "Most Played", mostIds, Modifier.weight(1f)) { opened = "most" }
        SmartCard(context, "🕐", "Recent", recentIds, Modifier.weight(1f)) { opened = "recent" }
        SmartCard(context, "🆕", "New", addedIds, Modifier.weight(1f)) { opened = "added" }
    }
    opened?.let { kind ->
        MixSheet(
            context = context,
            title = when (kind) {
                "most" -> "⭐ Most Played"
                "recent" -> "🕐 Recently Played"
                else -> "🆕 Recently Added"
            },
            source = "smart_$kind",
            loadSongIds = {
                when (kind) {
                    "most" -> context.symphony.database.playbackHistory
                        .getMostPlayedSongs(50).first()
                    "recent" -> context.symphony.database.playbackHistory
                        .getRecentlyPlayedSongs(50).first()
                    else -> context.symphony.groove.song.sort(
                        context.symphony.groove.song.all.value.toList(),
                        SongRepository.SortBy.DATE_MODIFIED,
                        true,
                    ).subListNonStrict(50)
                }
            },
            onDismiss = { opened = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartCard(
    context: ViewContext,
    icon: String,
    label: String,
    coverSongIds: List<String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(modifier = modifier, onClick = onClick) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            MixCoverCollage(context, coverSongIds, Modifier.matchParentSize())
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
            Text(
                icon,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}
