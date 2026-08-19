package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.spotizer.SpotizerQuality
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object SpotizerSettingsViewRoute

@Composable
private fun SpotizerSettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun QualityRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SpotizerSettingsView(context: ViewContext) {
    val spotizer = context.symphony.spotizer
    val settings = spotizer.settings
    val coroutineScope = rememberCoroutineScope()

    val downloadQuality by settings.downloadQuality.collectAsState()
    val streamQuality by settings.streamQuality.collectAsState()
    val skipExisting by settings.skipExistingTracks.collectAsState()
    val wifiOnly by settings.wifiOnlyDownloads.collectAsState()
    val maxConcurrent by settings.maxConcurrentDownloads.collectAsState()
    val folderName by settings.downloadFolderName.collectAsState()
    val serverBaseUrl by settings.serverBaseUrl.collectAsState()

    SpotizerPageScaffold(
        context = context,
        title = "Spotizer (Online)",
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(vertical = 8.dp),
        ) {
            SpotizerSettingsGroup(title = "Download quality") {
                SpotizerQuality.all.forEach { quality ->
                    QualityRadioRow(
                        label = SpotizerQuality.label(quality),
                        selected = downloadQuality == quality,
                    ) {
                        settings.setDownloadQuality(quality)
                        coroutineScope.launch {
                            spotizer.users.syncQualityToServer()
                        }
                    }
                }
            }

            SpotizerSettingsGroup(title = "Streaming quality") {
                SpotizerQuality.all.forEach { quality ->
                    QualityRadioRow(
                        label = SpotizerQuality.label(quality),
                        selected = streamQuality == quality,
                    ) {
                        settings.setStreamQuality(quality)
                    }
                }
            }

            SpotizerSettingsGroup(title = "Downloads") {
                SwitchRow(
                    title = "Skip tracks already on this device",
                    subtitle = "Album downloads will not re-download songs found in your local library",
                    checked = skipExisting,
                ) { settings.setSkipExistingTracks(it) }
                SwitchRow(
                    title = "Download on Wi-Fi only",
                    subtitle = "Queued downloads fail fast when Wi-Fi is unavailable",
                    checked = wifiOnly,
                ) { settings.setWifiOnlyDownloads(it) }
                Text(
                    "Concurrent downloads: " + maxConcurrent,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = maxConcurrent.toFloat(),
                    onValueChange = { settings.setMaxConcurrentDownloads(it.toInt()) },
                    valueRange = 1f..3f,
                    steps = 1,
                )
                Text(
                    "Applies to downloads queued after the change",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            SpotizerSettingsGroup(title = "About") {
                Text(
                    "Server: " + serverBaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    "Saved to: Music/" + folderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    "Tracks missing from the server cache are prepared on demand; " +
                            "the first download or stream of such tracks takes a bit longer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
