package io.github.zyrouge.symphony.ui.view.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.diagnostics.*
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable object DiagnosticsSettingsRoute

@Composable
fun DiagnosticsSettingsView(context: ViewContext) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var records by remember { mutableStateOf(emptyList<JsonObject>()) }
    var usage by remember { mutableStateOf(0L) }
    var enabled by remember { mutableStateOf(Diagnostics.enabled) }
    var filter by remember { mutableStateOf("Errors") }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { Diagnostics.recent() to Diagnostics.usage() }
        records = snapshot.first; usage = snapshot.second
    }
    LaunchedEffect(Unit) {
        Diagnostics.revision.collect {
            try { refresh() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not read diagnostic reports" }
            delay(500) // Throttle refreshes without starving the UI under continuous traffic.
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.activity.contentResolver.openOutputStream(uri)?.use { Diagnostics.export(it) }
                        ?: error("Cannot open destination")
                }
                message = "Report exported. Review it before sharing."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { message = "Could not export report. Check destination access and free space." }
            finally { busy = false }
        }
    }
    GlassSettingsScaffold(context, "Diagnostics & reports") { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Local diagnostics", style = MaterialTheme.typography.titleMedium)
                        Row { Text("Record errors and request history", Modifier.weight(1f)); Switch(enabled, { enabled = it; Diagnostics.setEnabled(it) }) }
                        Text("${"%.2f".format(usage / 1048576.0)} / 20 MiB · no time expiry", style = MaterialTheme.typography.bodySmall)
                        Text("Old normal logs are removed first; crash reports have priority. Nothing is uploaded automatically. Reports exclude keys, bodies, URL queries, chats and lyrics.", style = MaterialTheme.typography.bodySmall)
                        Text("Only the 200 newest events are shown here. ZIP export contains all retained reports.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = { export.launch("symphony-diagnostics.zip") }) { Text("Export ZIP") }
                            TextButton(enabled = !busy, onClick = { confirmClear = true }) { Text("Clear reports") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank()) Text(message)
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Errors", "Crashes", "Requests", "All").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                    }
                }
            }
            val visible = records.filter { entry -> when (filter) {
                "Errors" -> entry["level"]?.jsonPrimitive?.content in setOf("ERROR", "WARN", "CRASH")
                "Crashes" -> entry["level"]?.jsonPrimitive?.content == "CRASH"
                "Requests" -> entry.containsKey("request_id")
                else -> true
            } }
            if (visible.isEmpty()) item { Text("No retained events in this category.") }
            items(visible, key = { it.getValue("id").jsonPrimitive.content }) { entry ->
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${entry["level"]?.jsonPrimitive?.content} · ${entry["source"]?.jsonPrimitive?.content}", style = MaterialTheme.typography.titleSmall)
                        val time = entry["time"]?.jsonPrimitive?.longOrNull ?: 0L
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time)), style = MaterialTheme.typography.bodySmall)
                        Text(entry["code"]?.jsonPrimitive?.content.orEmpty())
                        entry["status"]?.jsonPrimitive?.content?.let { Text("HTTP $it") }
                        entry["request_id"]?.jsonPrimitive?.content?.let { Text("Request: $it", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { selected = entry }) { Text("View details") }
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        val text = remember(entry) { Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), entry) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Report details") }, text = {
            SelectionContainer { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }
        }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear stored reports?") }, text = { Text("This removes local crashes and request logs, not your music or app settings.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            confirmClear = false; scope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { Diagnostics.clear() }; refresh(); message = "Reports cleared" }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Could not clear reports" }
                finally { busy = false }
            }
        }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}
