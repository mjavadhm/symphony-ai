package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.components.ReadableText
import io.github.zyrouge.symphony.ui.components.readingStyle
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.settings.LlmSettingsRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What a Story/Meaning tab needs to know about one section of the current song. */
internal data class SectionState(
    val loaded: Boolean,
    val saved: CompanionRevision?,
    val pending: Boolean,
    val failure: String?,
)

@Composable
internal fun rememberSectionState(context: ViewContext, trackId: String, section: CompanionSection): SectionState {
    val service = context.symphony.songCompanion
    val prefs by service.preferences.collectAsState()
    val pending by service.pending.collectAsState()
    val failures by service.failures.collectAsState()
    // Emits (request, saved revision). collectAsState keeps the previous song's value until the new
    // query emits, so a result only counts once it answers this exact song, section and language.
    val request = Triple(trackId, section, prefs.language)
    val result by remember(request) {
        if (trackId.isEmpty()) flowOf(null) else service.dao.revisions(trackId).map { revisions ->
            request to revisions.firstOrNull {
                it.section == section.name && it.target.isEmpty() && it.language == prefs.language && it.state == "saved"
            }
        }
    }.collectAsState(initial = null)
    val current = result?.takeIf { it.first == request }
    val key = service.key(trackId, section, language = prefs.language)
    return SectionState(current != null, current?.second, key in pending, failures[key])
}

/**
 * Writes the section the first time it is shown for a song. Waits a moment first, so skipping through
 * songs with the tab open does not start (and pay for) a request for every song passed.
 */
@Composable
internal fun AutoGenerate(context: ViewContext, trackId: String, section: CompanionSection, state: SectionState, enabled: Boolean = true) {
    val service = context.symphony.songCompanion
    val prefs by service.preferences.collectAsState()
    LaunchedEffect(trackId, section, prefs.language, prefs.consent, state.loaded, state.saved == null, enabled) {
        if (!enabled || trackId.isEmpty() || !state.loaded || state.saved != null || state.pending || state.failure != null) return@LaunchedEffect
        delay(1200)
        service.autoGenerate(trackId, section)
    }
}

/** Shown instead of content until AI is turned on and a provider is configured. */
@Composable
internal fun CompanionSetup(context: ViewContext, modifier: Modifier = Modifier) {
    val service = context.symphony.songCompanion
    val prefs by service.preferences.collectAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Stories, meanings & translations", style = MaterialTheme.typography.titleMedium)
        if (!prefs.consent) {
            Text("Written by your AI provider. The song's title, artist and lyrics are sent to it; your audio stays on this device. " +
                "Nothing is sent until you open a tab or tap Translate, and results are saved so each song is written once.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { service.configure(consent = true) }) { Text("Turn on") }
        } else {
            Text("Choose an AI provider, model and API key first.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { context.navController.navigate(LlmSettingsRoute) }) { Text("Set up AI provider") }
        }
    }
}

/** "Writing…", or the error with a retry button. */
@Composable
internal fun SectionStatus(context: ViewContext, trackId: String, state: SectionState, busyLabel: String, onRetry: () -> Unit) {
    val service = context.symphony.songCompanion
    val task by service.task.collectAsState()
    when {
        state.pending -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(busyLabel, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            // Requests run one at a time; say so instead of looking stuck behind another song.
            if (task.busy && task.trackId != trackId) Muted("Waiting for another song to finish…")
            TextButton(onClick = service::stop) { Text("Cancel") }
        }
        state.failure != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadableText(state.failure, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Regenerate, with an optional note on what to change. */
@Composable
internal fun RegenerateButton(context: ViewContext, trackId: String, section: CompanionSection, enabled: Boolean, title: String) {
    val service = context.symphony.songCompanion
    var open by rememberSaveable(trackId, section) { mutableStateOf(false) }
    var instruction by rememberSaveable(trackId, section) { mutableStateOf("") }
    TextButton(enabled = enabled, onClick = { open = true }) { Text("Regenerate") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The current version stays in version history.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(),
                    textStyle = readingStyle(instruction, LocalTextStyle.current),
                    label = { Text("What should change? (optional)") }, minLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = {
            service.generate(trackId, section, instruction = instruction.trim()); instruction = ""; open = false
        }) { Text("Regenerate") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.6f))
}

/** True when AI can run. Reads preferences so it updates as soon as AI is turned on. */
@Composable
internal fun rememberReady(context: ViewContext): Boolean {
    val service = context.symphony.songCompanion
    val prefs by service.preferences.collectAsState()
    // The provider settings are not observable; they are re-read whenever this recomposes (e.g. returning from settings).
    return prefs.consent && service.readiness() == null
}

/** Common frame for the Story and Meaning tabs: song-matching errors and the loading state. */
@Composable
internal fun CompanionTabFrame(trackId: String, error: String, content: @Composable () -> Unit) {
    when {
        error.isNotBlank() -> ReadableText(error, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error))
        trackId.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Muted("Loading…") }
        else -> content()
    }
}
