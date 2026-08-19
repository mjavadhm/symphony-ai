package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.spotizer.SpotizerDownloadManager
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
object SpotizerDownloadsViewRoute

private fun phaseLabel(item: SpotizerDownloadManager.Item): String = when (item.phase) {
    SpotizerDownloadManager.Phase.Queued -> "Queued"
    SpotizerDownloadManager.Phase.CheckingLocal -> "Checking device library…"
    SpotizerDownloadManager.Phase.SkippedExists -> "Skipped — already on this device"
    SpotizerDownloadManager.Phase.CheckingServer -> "Checking server cache…"
    SpotizerDownloadManager.Phase.PreparingOnServer -> "Server is preparing the track…"
    SpotizerDownloadManager.Phase.Downloading -> "Downloading"
    SpotizerDownloadManager.Phase.Saving -> "Saving to Music…"
    SpotizerDownloadManager.Phase.Done ->
        if (item.wasCachedOnServer) "Done (was cached on server)" else "Done"
    SpotizerDownloadManager.Phase.Failed -> "Failed: " + (item.error ?: "Unknown error")
    SpotizerDownloadManager.Phase.Cancelled -> "Cancelled"
}

private fun formatMegabytes(bytes: Long): String =
    String.format("%.1f MB", bytes / (1024f * 1024f))

@Composable
private fun SectionHeading(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun DownloadQueueCard(
    item: SpotizerDownloadManager.Item,
    downloads: SpotizerDownloadManager,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = item.track.smallCover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.track.title ?: "Unknown title",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(item.track.artist, item.quality)
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    item.isActive -> {
                        IconButton(onClick = { downloads.cancel(item.id) }) {
                            Icon(Icons.Filled.Close, "Cancel")
                        }
                    }
                    item.phase == SpotizerDownloadManager.Phase.Failed ||
                            item.phase == SpotizerDownloadManager.Phase.Cancelled -> {
                        IconButton(onClick = { downloads.retry(item.id) }) {
                            Icon(Icons.Filled.Refresh, "Retry")
                        }
                    }
                }
            }
            Text(
                phaseLabel(item),
                style = MaterialTheme.typography.bodySmall,
                color = when (item.phase) {
                    SpotizerDownloadManager.Phase.Failed ->
                        MaterialTheme.colorScheme.error
                    SpotizerDownloadManager.Phase.Done ->
                        MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
            )
            item.savedFileName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (item.phase) {
                SpotizerDownloadManager.Phase.Downloading -> {
                    if (item.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (item.downloadedBytes.toFloat() /
                                        item.totalBytes.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            formatMegabytes(item.downloadedBytes) + " / " +
                                    formatMegabytes(item.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                SpotizerDownloadManager.Phase.Queued,
                SpotizerDownloadManager.Phase.CheckingLocal,
                SpotizerDownloadManager.Phase.CheckingServer,
                SpotizerDownloadManager.Phase.PreparingOnServer,
                SpotizerDownloadManager.Phase.Saving,
                -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {}
            }
        }
    }
}

/**
 * "Spotizer downloads" screen. Shows two things:
 * 1. The live download queue for the current session.
 * 2. Every track already saved into Music/<download folder>, read from the local
 *    library, so the list survives app restarts.
 */
@Composable
fun DownloadQueueView(context: ViewContext) {
    val downloads = context.symphony.spotizer.downloads
    val items by downloads.items.collectAsState()
    val folderName by context.symphony.spotizer.settings.downloadFolderName.collectAsState()
    val songIds by context.symphony.groove.song.all.collectAsState()
    val isScanning by context.symphony.groove.exposer.isUpdating.collectAsState()

    val resolvedFolderName = folderName.ifBlank { "Spotizer" }
    val downloadedSongs = remember(songIds, resolvedFolderName) {
        val needle = "/" + resolvedFolderName + "/"
        songIds
            .mapNotNull { context.symphony.groove.song.get(it) }
            .filter {
                it.path.contains(needle, ignoreCase = true) ||
                        it.path.startsWith(resolvedFolderName + "/", ignoreCase = true)
            }
            .sortedByDescending { it.dateModified }
    }

    SpotizerPageScaffold(
        context = context,
        title = "Spotizer downloads",
        topBarActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSurface(shape = CircleShape) {
                    IconButton(
                        enabled = !isScanning,
                        onClick = {
                            context.symphony.groove.fetch(Groove.FetchOptions())
                        },
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, "Re-scan library")
                        }
                    }
                }
                GlassSurface(shape = CircleShape) {
                    IconButton(onClick = { downloads.clearFinished() }) {
                        Icon(Icons.Filled.DeleteSweep, "Clear finished")
                    }
                }
            }
        },
        bottomBar = { SpotizerStreamBar(context) },
    ) { contentPadding ->
        if (items.isEmpty() && downloadedSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                IconTextBody(
                    icon = { modifier -> Icon(Icons.Filled.CloudDownload, null, modifier = modifier) },
                    content = { Text("No downloads yet") },
                )
            }
        } else {
            val ordered = items.asReversed()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = LocalHazeState.current, zIndex = 1f),
                contentPadding = contentPadding,
            ) {
                if (ordered.isNotEmpty()) {
                    item {
                        SectionHeading("Queue", ordered.size.toString())
                    }
                    items(ordered.size) { index ->
                        DownloadQueueCard(ordered[index], downloads)
                    }
                }
                item {
                    SectionHeading(
                        "Downloaded — Music/" + resolvedFolderName,
                        downloadedSongs.size.toString(),
                    )
                }
                if (downloadedSongs.isEmpty()) {
                    item {
                        Text(
                            "Nothing found in Music/" + resolvedFolderName + " yet. " +
                                    "Make sure that folder is part of your music folders, " +
                                    "then re-scan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    items(downloadedSongs.size) { index ->
                        val song = downloadedSongs[index]
                        SongCard(context, song) {
                            context.symphony.radio.shorty.playQueue(song.id)
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}
