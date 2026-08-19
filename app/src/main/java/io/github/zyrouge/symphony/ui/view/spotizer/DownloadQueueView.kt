package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.spotizer.SpotizerDownloadManager
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.LocalHazeState
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
fun DownloadQueueView(context: ViewContext) {
    val downloads = context.symphony.spotizer.downloads
    val items by downloads.items.collectAsState()

    SpotizerPageScaffold(
        context = context,
        title = "Spotizer downloads",
        topBarActions = {
            GlassSurface(shape = CircleShape) {
                IconButton(onClick = { downloads.clearFinished() }) {
                    Icon(Icons.Filled.DeleteSweep, "Clear finished")
                }
            }
        },
        bottomBar = { SpotizerStreamBar(context) },
    ) { contentPadding ->
        if (items.isEmpty()) {
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
                items(ordered.size) { index ->
                    val item = ordered[index]
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
            }
        }
    }
}
