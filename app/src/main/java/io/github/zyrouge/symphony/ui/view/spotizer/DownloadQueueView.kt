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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.spotizer.SpotizerDownloadManager

/**
 * The download queue screen: shows the full two-phase process per item
 * (checking -> preparing on server -> downloading -> saving -> done),
 * with cancel/retry and a clear-finished action.
 */
@Composable
fun DownloadQueueView(
    downloads: SpotizerDownloadManager,
    modifier: Modifier = Modifier,
) {
    val items by downloads.items.collectAsState()

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No downloads yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { downloads.clearFinished() }) { Text("Clear finished") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items.reversed(), key = { it.id }) { item ->
                DownloadItemRow(item = item, downloads = downloads)
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: SpotizerDownloadManager.Item,
    downloads: SpotizerDownloadManager,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(cornerRadius = 16.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = item.track.listCover,
                contentDescription = item.track.title,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.track.title ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        item.track.artist,
                        item.albumGroup?.let { "Album: $it" },
                        item.quality,
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                item.isActive -> IconButton(onClick = { downloads.cancel(item.id) }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
                item.phase == SpotizerDownloadManager.Phase.Failed ||
                    item.phase == SpotizerDownloadManager.Phase.Cancelled ->
                    IconButton(onClick = { downloads.retry(item.id) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
                    }
            }
        }

        // --- phase indicator ---
        val (label, color) = when (item.phase) {
            SpotizerDownloadManager.Phase.Queued ->
                "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
            SpotizerDownloadManager.Phase.CheckingLocal ->
                "Checking device library..." to MaterialTheme.colorScheme.onSurfaceVariant
            SpotizerDownloadManager.Phase.CheckingServer ->
                "Checking server..." to MaterialTheme.colorScheme.onSurfaceVariant
            SpotizerDownloadManager.Phase.PreparingOnServer ->
                "Preparing on server..." to MaterialTheme.colorScheme.tertiary
            SpotizerDownloadManager.Phase.Downloading ->
                "Downloading..." to MaterialTheme.colorScheme.primary
            SpotizerDownloadManager.Phase.Saving ->
                "Saving to music library..." to MaterialTheme.colorScheme.primary
            SpotizerDownloadManager.Phase.Done ->
                "Done" to MaterialTheme.colorScheme.primary
            SpotizerDownloadManager.Phase.SkippedExists ->
                "Skipped - already on device" to MaterialTheme.colorScheme.onSurfaceVariant
            SpotizerDownloadManager.Phase.Failed ->
                (item.error ?: "Failed") to MaterialTheme.colorScheme.error
            SpotizerDownloadManager.Phase.Cancelled ->
                "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2)

        when (item.phase) {
            SpotizerDownloadManager.Phase.PreparingOnServer,
            SpotizerDownloadManager.Phase.CheckingServer,
            SpotizerDownloadManager.Phase.CheckingLocal,
            SpotizerDownloadManager.Phase.Saving ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            SpotizerDownloadManager.Phase.Downloading -> {
                if (item.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val downloadedMb = item.downloadedBytes / 1024f / 1024f
                    val totalMb = item.totalBytes / 1024f / 1024f
                    Text(
                        text = "%.1f / %.1f MB".format(downloadedMb, totalMb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            else -> {}
        }
    }
}
