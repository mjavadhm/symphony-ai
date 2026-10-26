package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.flow.flowOf

/** The everyday reading/generation/chat surface lives inside the player, not on a separate route. */
@Composable
fun InlineSongAnalysis(context: ViewContext, songId: String, trackId: String, error: String, padding: PaddingValues) {
    val service = context.symphony.songCompanion
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val profile by remember(trackId) { if (trackId.isEmpty()) flowOf<CompanionTrack?>(null) else service.dao.observeTrack(trackId) }.collectAsState(initial = null)
    val revisions by remember(trackId) { if (trackId.isEmpty()) flowOf(emptyList<CompanionRevision>()) else service.dao.revisions(trackId) }.collectAsState(initial = emptyList())
    val sources by remember(trackId) { if (trackId.isEmpty()) flowOf(emptyList<CompanionSource>()) else service.dao.sources(trackId) }.collectAsState(initial = emptyList())
    val messages by remember(trackId) { if (trackId.isEmpty()) flowOf(emptyList<CompanionMessage>()) else service.dao.messages(trackId) }.collectAsState(initial = emptyList())
    val prefs by service.preferences.collectAsState()
    val task by service.task.collectAsState()
    var refs by remember(songId) { mutableStateOf(false) }
    var chatSection by rememberSaveable(songId) { mutableStateOf("MEANING") }
    var question by rememberSaveable(songId) { mutableStateOf("") }
    val track = profile?.takeIf { it.id == trackId }
    val activeRevisions = revisions.filter { it.trackId == trackId }
    val activeSources = sources.filter { it.trackId == trackId }
    val snapshot = remember(track?.snapshot) { track?.let { CompanionCore.decodeSnapshot(it.snapshot) } }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("About this song", style = MaterialTheme.typography.titleLarge)
            Text("Saved on this device · ${prefs.language}. Nothing is generated just by opening this view.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error)
            if (track == null && error.isBlank()) Text("Preparing local dossier…")
            if (!prefs.consent) TextButton(onClick = { context.navController.navigate(CompanionSettingsRoute) }) { Text("Enable AI / choose provider") }
            if (task.trackId == trackId) {
                Text(task.message)
                if (task.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = service::stop) { Text("Stop") } }
            } else if (task.busy) Text("Another dossier is being processed.")
        }
        if (track != null && snapshot != null) {
            listOf(CompanionSection.MEANING, CompanionSection.STORY).forEach { section ->
                item(key = section.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (section == CompanionSection.MEANING) "Meaning & interpretation" else "The story behind the song", style = MaterialTheme.typography.titleMedium)
                        Text(if (section == CompanionSection.MEANING) "Possible interpretations, not proven artist intent." else "Summary of reviewed sources, not independently verified history.", style = MaterialTheme.typography.bodySmall)
                        if (section == CompanionSection.STORY) TextButton(onClick = { refs = true }) { Text("References · ${activeSources.size} · add / review") }
                        val scoped = activeRevisions.filter { it.section == section.name && it.target.isEmpty() && it.language == prefs.language }
                        val saved = scoped.firstOrNull { it.state == "saved" }
                        val draft = scoped.firstOrNull { it.state == "draft" }
                        if (saved != null) RevisionCard(service, saved, snapshot, activeSources, task.busy)
                        else Text(if (section == CompanionSection.STORY && activeSources.isEmpty()) "Add a supporting reference to generate the story." else "No saved explanation yet.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !task.busy && (section != CompanionSection.STORY || activeSources.isNotEmpty()), onClick = { service.generate(trackId, section) }) { Text(if (saved == null) "Generate draft" else "New draft") }
                            TextButton(onClick = { chatSection = section.name; scope.launch { scroll.animateScrollToItem(3) } }) { Text("Ask about this") }
                        }
                        if (draft != null) {
                            Text("New draft · review before applying", style = MaterialTheme.typography.labelMedium)
                            RevisionCard(service, draft, snapshot, activeSources, task.busy)
                        }
                        HorizontalDivider()
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask or correct", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("MEANING" to "Meaning", "STORY" to "Story").forEach { (key, label) -> FilterChip(selected = chatSection == key, onClick = { chatSection = key }, label = { Text(label) }) }
                    }
                    Text("Chat is saved. Proposed corrections only change the explanation after Apply.", style = MaterialTheme.typography.bodySmall)
                    messages.filter { it.trackId == trackId && it.section == chatSection && it.target.isEmpty() && it.language == prefs.language }.takeLast(10).forEach { message ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message.role, style = MaterialTheme.typography.labelSmall)
                            Text(message.text)
                            message.revisionId?.let { id -> activeRevisions.firstOrNull { it.id == id }?.let { RevisionCard(service, it, snapshot, activeSources, task.busy) } }
                        } }
                    }
                    OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("Question or correction") })
                    Button(enabled = !task.busy && question.isNotBlank(), onClick = { service.chat(trackId, CompanionSection.valueOf(chatSection), "", question); question = "" }) { Text("Send") }
                }
            }
        }
        item {
            TextButton(onClick = { context.navController.navigate(SongCompanionRoute(songId = songId)) }) { Text("Versions, manual editor & full chat") }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (refs && track != null) ReferenceDialog(service, track, activeSources) { refs = false }
}
