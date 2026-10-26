package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.llm.LlmClient
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.readingStyle

/** Same persisted prompt and reset behavior as the other AI Provider editors. */
@Composable
fun StoryPromptEditor(llm: LlmClient) {
    var prompt by remember { mutableStateOf(llm.songStoryPrompt) }
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Behind the song · writing prompt", style = MaterialTheme.typography.titleMedium)
            Text("Change tone, detail and focus. Saved automatically; used for future drafts, not existing stories. No Wikipedia lookup or source selection.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(prompt, { if (it.length <= 16000) { prompt = it; llm.songStoryPrompt = it } }, Modifier.fillMaxWidth(),
                label = { Text("Writing prompt") }, minLines = 5, maxLines = 12,
                textStyle = readingStyle(prompt, LocalTextStyle.current),
                supportingText = { Text("{title} · {artist} · {album} · {language} — up to 16,000 characters") })
            TextButton(onClick = { llm.songStoryPrompt = ""; prompt = llm.songStoryPrompt }) { Text("Reset to default") }
            Text("AI may not know the real history. The default asks it to admit uncertainty instead of inventing facts.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
