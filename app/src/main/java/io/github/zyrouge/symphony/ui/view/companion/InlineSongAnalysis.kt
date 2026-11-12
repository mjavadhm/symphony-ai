package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.components.ReadableText
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.flow.flowOf

/** Player reading surface: the prose is the content, not a technical revision card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineSongAnalysis(context: ViewContext, songId: String, trackId: String, error: String, padding: PaddingValues, request: String? = null, onRequestHandled: () -> Unit = {}) {
    val service = context.symphony.songCompanion
    val profile by remember(trackId) { if (trackId.isEmpty()) flowOf<CompanionTrack?>(null) else service.dao.observeTrack(trackId) }.collectAsState(initial = null)
    val revisions by remember(trackId) { if (trackId.isEmpty()) flowOf(emptyList<CompanionRevision>()) else service.dao.revisions(trackId) }.collectAsState(initial = emptyList())
    val messages by remember(trackId) { if (trackId.isEmpty()) flowOf(emptyList<CompanionMessage>()) else service.dao.messages(trackId) }.collectAsState(initial = emptyList())
    val prefs by service.preferences.collectAsState()
    val task by service.task.collectAsState()
    var chat by rememberSaveable(songId) { mutableStateOf(false) }
    var rewrite by rememberSaveable(songId) { mutableStateOf(false) }
    var instruction by rememberSaveable(songId) { mutableStateOf("") }
    var question by rememberSaveable(songId) { mutableStateOf("") }
    LaunchedEffect(request) {
        when (request) { "chat" -> chat = true; "rewrite" -> rewrite = true }
        if (request != null) onRequestHandled()
    }
    val track = profile?.takeIf { it.id == trackId }
    val currentHash = track?.let { CompanionCore.decodeSnapshot(it.snapshot).hash }
    val scoped = revisions.filter { it.trackId == trackId && it.section == "MEANING" && it.target.isEmpty() && it.language == prefs.language }
    val saved = scoped.firstOrNull { it.state == "saved" }
    // Only the newest applicable proposal is a player preview. Keeping/discarding it must
    // not unexpectedly reveal an older, superseded draft over the saved article.
    val draft = CompanionRevisionPolicy.playerDraft(scoped)
    val shown = draft ?: saved
    val stale = shown != null && CompanionCore.decodeSnapshot(shown.snapshot).hash != currentHash
    val canAct = track != null && !task.busy && prefs.consent
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            if (error.isNotBlank()) ReadableText(error, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error))
            if (track == null && error.isBlank()) Text("Preparing…", style = MaterialTheme.typography.bodySmall)
            if (!prefs.consent) TextButton(onClick = { context.navController.navigate(CompanionSettingsRoute) }) { Text("Set up song stories") }
            if (task.trackId == trackId && (task.busy || task.error)) {
                ReadableText(task.message, style = MaterialTheme.typography.bodySmall.copy(color = if (task.error) MaterialTheme.colorScheme.error else LocalContentColor.current))
                if (task.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = service::stop) { Text("Cancel") }
                }
            } else if (task.busy) Text("Another song is being processed", style = MaterialTheme.typography.bodySmall)
        }
        if (shown != null) {
            if (draft != null) item { Text("Preview · your saved text is unchanged", style = MaterialTheme.typography.labelMedium) }
            if (stale) item { Text("Lyrics changed since this version. Generate a fresh draft before saving.", color = MaterialTheme.colorScheme.error) }
            val text = SongStoryPrompt.readable(CompanionCore.display(CompanionSection.MEANING, shown.payload))
            val paragraphs = text.split(Regex("\\n\\s*\\n"))
            items(paragraphs.size) { index -> ReadableText(paragraphs[index], style = MaterialTheme.typography.bodyLarge) }
            if (draft != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !task.busy && !stale, onClick = { service.apply(draft.id, trackId) }) { Text("Keep this version") }
                    TextButton(enabled = !task.busy, onClick = { service.discard(draft.id, trackId) }) { Text("Discard") }
                }
            }
        } else if (track != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The story behind the song", style = MaterialTheme.typography.titleMedium)
                Text("Create once, read anytime. AI can be wrong about an artist’s history.", style = MaterialTheme.typography.bodyMedium)
                Button(enabled = canAct, onClick = { service.generate(trackId, CompanionSection.MEANING) }) { Text("Write the story") }
            }
        }
        if (track != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { chat = true }) { Text("Ask / correct") }
                TextButton(enabled = canAct, onClick = { rewrite = true }) { Text("Rewrite") }
            }
        }
    }
    if (rewrite) AlertDialog(onDismissRequest = { rewrite = false }, title = { Text("Rewrite the story") }, text = {
        OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(), textStyle = io.github.zyrouge.symphony.ui.components.readingStyle(instruction, LocalTextStyle.current),
            label = { Text("What should change? (optional)") }, minLines = 3)
    }, confirmButton = { TextButton(enabled = canAct, onClick = { service.generate(trackId, CompanionSection.MEANING, instruction = instruction); rewrite = false }) { Text("Create draft") } },
        dismissButton = { TextButton(onClick = { rewrite = false }) { Text("Cancel") } })
    if (chat && track != null) ModalBottomSheet(onDismissRequest = { chat = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ask / correct", style = MaterialTheme.typography.titleLarge)
            Text("Suggestions become saved text only after you keep the new version.", style = MaterialTheme.typography.bodySmall)
            messages.filter { it.trackId == trackId && it.section == "MEANING" && it.target.isEmpty() && it.language == prefs.language }.takeLast(30).forEach { message ->
                Text(if (message.role == "user") "You" else "Assistant", style = MaterialTheme.typography.labelSmall)
                ReadableText(SongStoryPrompt.readable(message.text))
            }
            if (draft != null) {
                Text("A new draft is ready in the player", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { chat = false }) { Text("Review draft") }
            }
            if (task.trackId == trackId && (task.busy || task.error)) {
                ReadableText(task.message)
                if (task.busy) TextButton(onClick = service::stop) { Text("Cancel request") }
            }
            OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), textStyle = io.github.zyrouge.symphony.ui.components.readingStyle(question, LocalTextStyle.current), label = { Text("Ask or explain what is wrong") })
            Button(enabled = canAct && question.isNotBlank(), onClick = { service.chat(trackId, CompanionSection.MEANING, "", question); question = "" }) { Text("Send") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
