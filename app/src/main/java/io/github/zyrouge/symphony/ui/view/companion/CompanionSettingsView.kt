package io.github.zyrouge.symphony.ui.view.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.CompanionBackup
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.settings.LlmSettingsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CompanionSettingsView(context: ViewContext) {
    val service = context.symphony.songCompanion
    val prefs by service.preferences.collectAsState()
    val tracks by remember { service.dao.observeTracks() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var language by remember(prefs.language) { mutableStateOf(prefs.language) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val json = service.export()
                withContext(Dispatchers.IO) {
                    context.activity.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Cannot open export file")
                }
                status = "Backup saved. It contains dossiers, sources, versions and chats, but no API keys or audio files."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = context.activity.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= CompanionBackup.MAX_BYTES) { "Backup exceeds 50 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Cannot read backup")
                    bytes.toString(Charsets.UTF_8)
                }
                status = service.import(text)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    GlassSettingsScaffold(context, "Song Companion settings") { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI & privacy", style = MaterialTheme.typography.titleLarge)
            Text("Generation sends song metadata, lyrics, selected reference excerpts, saved text and recent chat context to your configured AI provider. No audio file is uploaded. Wikipedia search sends song title/artist to Wikipedia. Offline reading and manual edits do not require AI.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = prefs.consent, onCheckedChange = { service.configure(consent = it) })
                Text("Allow sending context to the AI provider", Modifier.weight(1f))
            }
            TextButton(onClick = { context.navController.navigate(LlmSettingsRoute) }) { Text("Configure existing AI provider / base URL / model") }
            Text("Uses the existing OpenAI-compatible provider configuration and respects AI mode Off. There is no automatic generation or background billing.")
            OutlinedTextField(language, { language = it }, label = { Text("Explanation and translation language") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = language.isNotBlank() && language.length <= 80, onClick = { service.configure(language = language.trim()) }) { Text("Save language") }
            Text("Lyric display", style = MaterialTheme.typography.titleMedium)
            listOf("ORIGINAL" to "Original only", "BILINGUAL" to "Original + translation", "TRANSLATION" to "Translation (original if unavailable)").forEach { (mode, label) ->
                TextButton(onClick = { service.configure(mode = mode) }) { Text((if (prefs.mode == mode) "● " else "○ ") + label) }
            }
            HorizontalDivider()
            Text("Portable backup", style = MaterialTheme.typography.titleLarge)
            Text("Dossiers are stored in a separate on-device database, not inside audio tags. Export before reinstalling. Import skips existing dossier IDs and does not copy old device file paths. A song with matching metadata + lyrics can reconnect automatically only if the match is unique.")
            Text("Backups include private chats and lyric text. Store them somewhere you trust.")
            Row {
                TextButton(enabled = !busy, onClick = { export.launch("symphony-song-companion.json") }) { Text("Export JSON") }
                TextButton(enabled = !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import JSON") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status)
            Text("Saved dossiers (${tracks.size})", style = MaterialTheme.typography.titleLarge)
            if (tracks.isEmpty()) Text("Open Song Companion from the lyrics screen to start a dossier.")
            tracks.forEach { track ->
                TextButton(onClick = { context.navController.navigate(SongCompanionRoute(trackId = track.id)) }) {
                    Column(Modifier.fillMaxWidth()) { Text(track.title); Text("${track.artists} · ${track.id.take(8)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
