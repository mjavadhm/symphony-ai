package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.spotizer.SpotizerServer
import io.github.zyrouge.symphony.services.spotizer.SpotizerSettings
import io.github.zyrouge.symphony.ui.components.readingStyle

@Composable
fun SpotizerServerEditor(settings: SpotizerSettings) {
    val active by settings.serverBaseUrl.collectAsState()
    var input by remember(active) { mutableStateOf(active) }
    var message by remember { mutableStateOf("") }
    val normalized = remember(input) { runCatching { SpotizerServer.normalize(input) }.getOrNull() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it; message = "" }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Spotizer service URL") }, isError = normalized == null,
            textStyle = readingStyle(input, LocalTextStyle.current),
            supportingText = { Text(if (normalized == null) "Enter a valid HTTP(S) base URL without credentials or query parameters" else "Shared by Home, Spotizer search, streaming and downloads") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = normalized != null && normalized != active, onClick = {
                settings.setServerBaseUrl(input)
                message = "Server saved. New requests use this address; existing playback/downloads may need to be restarted."
            }) { Text("Save server") }
            TextButton(onClick = { input = SpotizerServer.DEFAULT; message = "" }) { Text("Use default") }
        }
        Text("Active server: $active", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
