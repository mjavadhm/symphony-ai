package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.components.ReadableText
import io.github.zyrouge.symphony.ui.helpers.ViewContext

/** The story behind the song, written from the model's own knowledge. Writes itself the first time it is opened. */
@Composable
fun SongStoryTab(context: ViewContext, trackId: String, error: String, padding: PaddingValues) {
    val service = context.symphony.songCompanion
    val state = rememberSectionState(context, trackId, CompanionSection.STORY)
    val ready = rememberReady(context)
    AutoGenerate(context, trackId, CompanionSection.STORY, state, enabled = ready)
    val story = remember(state.saved?.payload) { state.saved?.let { runCatching { CompanionCore.story(it.payload) }.getOrNull() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            CompanionTabFrame(trackId, error) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (story == null && !ready) CompanionSetup(context)
                    SectionStatus(context, trackId, state, if (story == null) "Writing the story behind this song…" else "Rewriting…") {
                        service.generate(trackId, CompanionSection.STORY)
                    }
                    if (story != null) BasisLabel(story.basis)
                }
            }
        }
        if (story != null) {
            items(story.paragraphs.size) { index ->
                ReadableText(SongStoryPrompt.readable(story.paragraphs[index]), style = MaterialTheme.typography.bodyLarge)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Muted(if (story.basis == "lyrics") "No well-known background was found for this song, so this is a reading of the lyrics."
                        else "Written by AI from what it knows. It can be wrong, especially about lesser-known songs.")
                    RegenerateButton(context, trackId, CompanionSection.STORY, enabled = ready && !state.pending, title = "Rewrite the story")
                }
            }
        }
    }
}

@Composable
private fun BasisLabel(basis: String?) {
    val label = when (basis) {
        "known" -> "Known background"
        "lyrics" -> "Interpretation from the lyrics"
        else -> return
    }
    Text(label, Modifier.background(LocalContentColor.current.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium)
}
