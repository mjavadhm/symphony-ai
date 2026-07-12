package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.settings.IndexSongsSettingsRoute
import io.github.zyrouge.symphony.ui.view.settings.SemanticSearchSettingsRoute

@Composable
fun AiSetupChecklist(context: ViewContext) {
    val engine = context.symphony.semanticSearch
    val hasAudio = engine.getModelInfo(true) != null
    val hasText = engine.getModelInfo(false) != null
    val indexedCount = remember { engine.indexedTrackCount() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Set up AI search", style = MaterialTheme.typography.titleMedium)
            Text(
                "Complete these steps to unlock Discover:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            ChecklistRow(
                done = hasAudio,
                label = "Import the audio encoder model",
                actionLabel = "Import",
            ) { context.navController.navigate(SemanticSearchSettingsRoute) }

            ChecklistRow(
                done = hasText,
                label = "Import the text encoder model",
                actionLabel = "Import",
            ) { context.navController.navigate(SemanticSearchSettingsRoute) }

            ChecklistRow(
                done = indexedCount > 0L,
                label = if (indexedCount > 0L) "$indexedCount songs indexed"
                else "Index your songs",
                actionLabel = "Index",
                enabled = hasAudio && hasText,
            ) { context.navController.navigate(IndexSongsSettingsRoute) }
        }
    }
}

@Composable
private fun ChecklistRow(
    done: Boolean,
    label: String,
    actionLabel: String,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            null,
            tint = if (done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!done) {
            TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
        }
    }
}
