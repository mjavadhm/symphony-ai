package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.spotizer.Spotizer
import io.github.zyrouge.symphony.services.spotizer.SpotizerQuality

/**
 * Spotizer section for Symphony's settings screen. Either embed
 * [SpotizerSettingsBody] inside the existing SettingsView, or route to it
 * as its own page.
 */
@Composable
fun SpotizerSettingsBody(
    spotizer: Spotizer,
    modifier: Modifier = Modifier,
) {
    val settings = spotizer.settings
    val downloadQuality by settings.downloadQuality.collectAsState()
    val streamQuality by settings.streamQuality.collectAsState()
    val skipExisting by settings.skipExistingTracks.collectAsState()
    val wifiOnly by settings.wifiOnlyDownloads.collectAsState()
    val maxConcurrent by settings.maxConcurrentDownloads.collectAsState()
    val folderName by settings.downloadFolderName.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- download quality ---
        SettingsCard(title = "Download quality") {
            SpotizerQuality.ALL.forEach { quality ->
                QualityRow(
                    label = SpotizerQuality.label(quality),
                    selected = quality == downloadQuality,
                    onClick = { settings.setDownloadQuality(quality) },
                )
            }
        }

        // --- stream quality ---
        SettingsCard(title = "Streaming quality") {
            SpotizerQuality.ALL.forEach { quality ->
                QualityRow(
                    label = SpotizerQuality.label(quality),
                    selected = quality == streamQuality,
                    onClick = { settings.setStreamQuality(quality) },
                )
            }
        }

        // --- toggles ---
        SettingsCard(title = "Downloads") {
            ToggleRow(
                title = "Skip tracks already on device",
                subtitle = "When downloading albums, tracks matched in the local library are skipped",
                checked = skipExisting,
                onCheckedChange = { settings.setSkipExistingTracks(it) },
            )
            ToggleRow(
                title = "Download on Wi-Fi only",
                subtitle = null,
                checked = wifiOnly,
                onCheckedChange = { settings.setWifiOnlyDownloads(it) },
            )
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    "Concurrent downloads: $maxConcurrent",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = maxConcurrent.toFloat(),
                    onValueChange = { settings.setMaxConcurrentDownloads(it.toInt()) },
                    valueRange = 1f..3f,
                    steps = 1,
                )
                Text(
                    "Saved to Music/$folderName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(cornerRadius = 16.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun QualityRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
