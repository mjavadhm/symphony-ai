package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.components.LocalNowPlayingAccent
import io.github.zyrouge.symphony.ui.components.ReadableText
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.settings.LyricsSettingsViewRoute
import kotlinx.coroutines.flow.flowOf

/**
 * Genius-style meaning: a short summary, then the lyrics with the lines that need explaining highlighted.
 * Tapping a highlighted line opens its explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMeaningTab(context: ViewContext, songId: String, trackId: String, error: String, padding: PaddingValues) {
    val service = context.symphony.songCompanion
    val observed by remember(trackId) { if (trackId.isEmpty()) flowOf(null) else service.dao.observeTrack(trackId) }.collectAsState(initial = null)
    // collectAsState briefly keeps the previous song's row after a song change; ignore it.
    val track = observed?.takeIf { it.id == trackId }
    val snapshot = remember(track?.snapshot) { track?.let { CompanionCore.decodeSnapshot(it.snapshot) } }
    val hasLyrics = snapshot?.lines?.isNotEmpty() == true
    val raw = rememberSectionState(context, trackId, CompanionSection.MEANING)
    // An old single-article "Behind the song" counts as missing here, so a line-by-line version is written.
    val state = if (raw.saved != null && CompanionCore.isLegacyMeaning(raw.saved.payload)) raw.copy(saved = null) else raw
    val ready = rememberReady(context)
    AutoGenerate(context, trackId, CompanionSection.MEANING, state, enabled = ready && hasLyrics)
    val meaning = remember(state.saved?.payload) { state.saved?.let { runCatching { CompanionCore.meaning(it.payload) }.getOrNull() } }
    val stale = state.saved != null && snapshot != null && CompanionCore.decodeSnapshot(state.saved.snapshot).hash != snapshot.hash
    var selected by rememberSaveable(trackId) { mutableStateOf<String?>(null) }
    val accent = LocalNowPlayingAccent.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CompanionTabFrame(trackId, error) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when {
                        snapshot != null && !hasLyrics -> {
                            Text("This song has no lyrics yet, so there is nothing to explain.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { context.navController.navigate(LyricsSettingsViewRoute(songId = songId)) }) { Text("Find lyrics") }
                        }
                        meaning == null && !ready -> CompanionSetup(context)
                    }
                    SectionStatus(context, trackId, state, if (meaning == null) "Working out what this song means…" else "Rewriting…") {
                        service.generate(trackId, CompanionSection.MEANING)
                    }
                    meaning?.summary?.split(Regex("\\n\\s*\\n"))?.forEach {
                        ReadableText(it.trim(), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (stale) Muted("The lyrics changed since this was written. Regenerate to update the line explanations.")
                    if (meaning != null && meaning.lines.isNotEmpty()) Muted("Tap a highlighted line to see what it means.")
                }
            }
        }
        if (meaning != null && snapshot != null) {
            items(snapshot.lines, key = { it.id }) { line ->
                val explanation = meaning.lines[line.id]
                if (explanation != null) ReadableText(line.text,
                    Modifier.clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.22f))
                        .clickable { selected = line.id }.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                else ReadableText(line.text, Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current.copy(alpha = 0.55f)))
            }
            item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Muted("Written by AI. Explanations are readings of the lyrics unless they say otherwise.")
                    RegenerateButton(context, trackId, CompanionSection.MEANING, enabled = ready && !state.pending, title = "Rewrite the meaning")
                }
            }
        }
    }

    val line = selected?.let { id -> snapshot?.lines?.firstOrNull { it.id == id } }
    val explanation = selected?.let { meaning?.lines?.get(it) }
    if (line != null && explanation != null) ModalBottomSheet(onDismissRequest = { selected = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadableText(line.text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            ReadableText(explanation, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
        }
    }
}
