package io.github.zyrouge.symphony.ui.view.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.companion.*
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SongCompanionRoute(val songId: String = "", val trackId: String = "", val lineId: String = "")
@Serializable
object CompanionSettingsRoute

@Composable
fun SongCompanionView(context: ViewContext, route: SongCompanionRoute) {
    val service = context.symphony.songCompanion
    val scope = rememberCoroutineScope()
    var id by rememberSaveable(route) { mutableStateOf(route.trackId) }
    var error by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<CompanionTrack>>(emptyList()) }
    suspend fun resolve(chosen: String? = null, fresh: Boolean = false) {
        try {
            val song = context.symphony.groove.song.get(route.songId) ?: error("Song is no longer in the library")
            val lyrics = withContext(Dispatchers.IO) { context.symphony.groove.song.getLyrics(song).orEmpty() }
            id = service.resolve(song, lyrics, chosen, fresh).id
            candidates = emptyList(); error = ""
        } catch (e: AmbiguousCompanion) { candidates = e.candidates }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
    }
    LaunchedEffect(route) { if (id.isEmpty()) resolve() }
    GlassSettingsScaffold(context, "Song Companion", topBarActions = {
        TextButton(onClick = { context.navController.navigate(CompanionSettingsRoute) }) { Text("Settings") }
    }) { padding ->
        if (id.isNotEmpty()) {
            CompanionDossier(context, id, route.lineId, Modifier.padding(padding))
        } else Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(if (error.isNotEmpty()) error else if (candidates.isEmpty()) "Loading…" else "Several dossiers match. Choose one; nothing is merged automatically.")
            candidates.forEach { track -> TextButton(onClick = { scope.launch { resolve(track.id) } }) {
                Text("${track.title} · ${track.id.take(8)}")
            } }
            if (candidates.isNotEmpty()) Button(onClick = { scope.launch { resolve(fresh = true) } }) { Text("Create separate dossier") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionDossier(context: ViewContext, id: String, initialLine: String, modifier: Modifier) {
    val service = context.symphony.songCompanion
    val track by remember(id) { service.dao.observeTrack(id) }.collectAsState(initial = null)
    val revisions by remember(id) { service.dao.revisions(id) }.collectAsState(initial = emptyList())
    val messages by remember(id) { service.dao.messages(id) }.collectAsState(initial = emptyList())
    val sources by remember(id) { service.dao.sources(id) }.collectAsState(initial = emptyList())
    val prefs by service.preferences.collectAsState()
    val task by service.task.collectAsState()
    var tab by rememberSaveable(id) { mutableIntStateOf(if (initialLine.isEmpty()) 0 else 3) }
    var sectionName by rememberSaveable(id) { mutableStateOf(if (initialLine.isEmpty()) "STORY" else "NOTE") }
    var lineId by rememberSaveable(id) { mutableStateOf(initialLine) }
    var instruction by rememberSaveable(id) { mutableStateOf("") }
    var question by rememberSaveable(id) { mutableStateOf("") }
    var sourceDialog by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CompanionRevision?>(null) }
    var manualNew by remember { mutableStateOf(false) }
    var chatCount by rememberSaveable(id) { mutableIntStateOf(50) }
    var history by rememberSaveable(id) { mutableStateOf(false) }
    val profile = track ?: run { Text("Loading dossier…", modifier); return }
    val snapshot = remember(profile.snapshot) { CompanionCore.decodeSnapshot(profile.snapshot) }
    val section = CompanionSection.valueOf(sectionName)
    val target = if (section == CompanionSection.NOTE) lineId else ""
    val scoped = revisions.filter { it.section == section.name && it.target == target && it.language == prefs.language }
    val saved = scoped.firstOrNull { it.state == "saved" }
    val drafts = scoped.filter { it.state == "draft" }
    val canAct = !task.busy && (section != CompanionSection.NOTE || snapshot.lines.any { it.id == target })
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(profile.title, style = MaterialTheme.typography.titleLarge)
            Text("${profile.artists} · ${prefs.language}", style = MaterialTheme.typography.bodySmall)
            Text("Saved on this device. Opening this page never calls AI.", style = MaterialTheme.typography.bodySmall)
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Story", "Meaning", "Translation", "Line notes", "Chat").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = {
                    tab = i
                    if (i < 4) sectionName = CompanionSection.entries[i].name
                }, text = { Text(title) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (task.trackId == id) {
                Text(task.message)
                if (task.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = service::stop) { Text("Stop") } }
            }
            if (task.busy && task.trackId != id) Text("Another dossier is being processed. Wait for it to finish before starting another request.")
            if (!prefs.consent) Text("Enable AI consent in Companion settings before generating. Reading and manual editing work offline.")
            if (section == CompanionSection.NOTE) {
                Text("Select a lyric line (or long-press a line in the player).")
                snapshot.lines.forEach { line ->
                    Text((if (line.id == lineId) "● " else "○ ") + line.text,
                        Modifier.fillMaxWidth().clickable { lineId = line.id }.padding(6.dp))
                }
                val currentIds = snapshot.lines.map { it.id }.toSet()
                revisions.filter { it.section == "NOTE" && it.language == prefs.language && it.target !in currentIds }.distinctBy { it.target }.forEach { old ->
                    val oldText = CompanionCore.decodeSnapshot(old.snapshot).lines.firstOrNull { it.id == old.target }?.text.orEmpty()
                    TextButton(onClick = { lineId = old.target }) { Text("Older lyric line: $oldText") }
                }
                if (snapshot.lines.isEmpty()) Text("Add lyrics first to create line notes or translations.")
            }
            if (tab == 4) {
                Text("Chat scope: ${section.name.lowercase()} · ${prefs.language}", style = MaterialTheme.typography.titleMedium)
                Text("Select another section tab before opening Chat to change its scope. Changes are proposals until you tap Apply.")
                val conversation = messages.filter { it.section == section.name && it.target == target && it.language == prefs.language }
                if (conversation.size > chatCount) TextButton(onClick = { chatCount += 50 }) { Text("Show older messages") }
                conversation.takeLast(chatCount).forEach { message ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(message.role, style = MaterialTheme.typography.labelMedium)
                        Text(message.text)
                        message.revisionId?.let { revId -> revisions.firstOrNull { it.id == revId }?.let { revision ->
                            RevisionCard(service, revision, snapshot, sources, task.busy)
                        } }
                    } }
                }
                OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("Ask, clarify, or request a correction") })
                Button(enabled = canAct && question.isNotBlank(), onClick = {
                    service.chat(id, section, target, question); question = ""
                }) { Text("Send") }
            } else {
                if (section == CompanionSection.STORY) {
                    Text("Source-based AI summary, not verified artist testimony. Citations show supplied excerpts, not independent fact-checking.")
                    TextButton(onClick = { sourceDialog = true }) { Text("References (${sources.size}/8) · search / add") }
                }
                if (section == CompanionSection.MEANING || section == CompanionSection.NOTE) Text("AI interpretation — not established artist intent.")
                if (saved == null) Text("Nothing saved in this section/language yet.")
                else {
                    Text("Saved version", style = MaterialTheme.typography.titleMedium)
                    RevisionCard(service, saved, snapshot, sources, task.busy)
                }
                OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(), label = { Text("Optional direction for a new draft") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = canAct, onClick = { service.generate(id, section, target, instruction) }) { Text(if (saved == null) "Generate draft" else "New draft") }
                    TextButton(enabled = canAct, onClick = { editor = saved; manualNew = saved == null }) { Text("Edit manually") }
                }
                (if (history) drafts else drafts.take(3)).forEach { revision ->
                    Text("Unapplied draft #${revision.id}", style = MaterialTheme.typography.titleSmall)
                    RevisionCard(service, revision, snapshot, sources, task.busy)
                }
                TextButton(onClick = { history = !history }) { Text(if (history) "Hide versions" else "Version history") }
                if (history) scoped.filter { it.state == "archived" }.forEach { RevisionCard(service, it, snapshot, sources, task.busy) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (sourceDialog) ReferenceDialog(service, profile, sources) { sourceDialog = false }
    if (editor != null || manualNew) PayloadEditor(service, id, section, target, snapshot, sources, editor) { editor = null; manualNew = false }
}

@Composable
internal fun RevisionCard(service: SongCompanion, revision: CompanionRevision, current: CompanionSnapshot, sources: List<CompanionSource>, busy: Boolean) {
    val section = CompanionSection.valueOf(revision.section)
    val snapshot = remember(revision.snapshot) { CompanionCore.decodeSnapshot(revision.snapshot) }
    val stale = snapshot.hash != current.hash
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("#${revision.id} · ${revision.state} · ${revision.origin} ${revision.model}", style = MaterialTheme.typography.labelSmall)
        if (stale) Text("Lyrics changed: this version is kept for reference, but is not shown over the current lyrics.", color = MaterialTheme.colorScheme.error)
        when (section) {
            CompanionSection.TRANSLATION -> {
                val translations = CompanionCore.translations(revision.payload)
                snapshot.lines.forEach { Text(it.text); Text(translations[it.id].orEmpty(), color = MaterialTheme.colorScheme.primary) }
            }
            CompanionSection.STORY -> CompanionCore.parseObject(revision.payload).getValue("paragraphs").jsonArray.forEach { item ->
                val p = item.jsonObject
                Text(p.getValue("text").jsonPrimitive.content)
                p.getValue("sourceIds").jsonArray.forEach { ref -> sources.firstOrNull { it.id == ref.jsonPrimitive.content }?.let { source ->
                    TextButton(onClick = { uriHandler.openUri(source.url) }) { Text("Source: ${source.title}") }
                } }
            }
            else -> Text(CompanionCore.display(section, revision.payload))
        }
        if (revision.state == "draft") Button(enabled = !busy && !stale, onClick = { service.apply(revision.id, revision.trackId) }) { Text("Apply to saved dossier") }
        if (revision.state == "archived") TextButton(enabled = !busy && !stale, onClick = { service.restore(revision.id, revision.trackId) }) { Text("Restore as a new version") }
    } }
}

@Composable
private fun PayloadEditor(service: SongCompanion, id: String, section: CompanionSection, target: String,
                          snapshot: CompanionSnapshot, sources: List<CompanionSource>, revision: CompanionRevision?, dismiss: () -> Unit) {
    var text by remember { mutableStateOf(revision?.let { CompanionCore.display(section, it.payload) }.orEmpty()) }
    val translations = remember { mutableStateMapOf<String, String>().apply { putAll(CompanionCore.translations(revision?.payload)) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Manual edit · ${section.name.lowercase()}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == CompanionSection.TRANSLATION) snapshot.lines.forEach { line ->
                Text(line.text)
                OutlinedTextField(translations[line.id].orEmpty(), { translations[line.id] = it }, label = { Text("Translation") })
            } else {
                if (section == CompanionSection.STORY) Text("Manual story uses all selected references below; make sure they actually support your text.")
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 4)
            }
        }
    }, confirmButton = { TextButton(enabled = if (section == CompanionSection.TRANSLATION) snapshot.lines.isNotEmpty() && snapshot.lines.all { !translations[it.id].isNullOrBlank() }
        else text.isNotBlank() && (section != CompanionSection.STORY || sources.isNotEmpty()), onClick = {
        val payload = buildJsonObject {
            when (section) {
                CompanionSection.TRANSLATION -> put("lines", buildJsonArray { snapshot.lines.forEach { line -> add(buildJsonObject {
                    put("id", line.id); put("translation", translations[line.id].orEmpty())
                }) } })
                CompanionSection.STORY -> put("paragraphs", buildJsonArray { add(buildJsonObject { put("text", text); put("sourceIds", JsonArray(sources.map { JsonPrimitive(it.id) })) }) })
                else -> put("text", text)
            }
        }.toString()
        service.edit(id, section, target, payload); dismiss()
    }) { Text("Save new version") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
internal fun ReferenceDialog(service: SongCompanion, profile: CompanionTrack, sources: List<CompanionSource>, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var excerpt by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CompanionReference>>(emptyList()) }
    var origin by remember { mutableStateOf("user") }; var status by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Review references") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Read the excerpt and check it is about this recording. Search is optional and uses English Wikipedia; no Genius scraping.")
            sources.forEach { Text("✓ ${it.title} · ${it.origin}\n${it.url}\n${it.excerpt.take(350)}") }
            TextButton(enabled = !busy, onClick = { scope.launch {
                busy = true; status = "Searching…"
                try { results = service.references.search(profile.title, profile.artists); status = if (results.isEmpty()) "No references found; add one manually." else "Select and review an excerpt below." }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = e.message.orEmpty() }
                finally { busy = false }
            } }) { Text("Search Wikipedia") }
            results.forEach { ref -> TextButton(onClick = { title = ref.title; url = ref.url; excerpt = ref.excerpt; origin = "wikipedia" }) { Text(ref.title) } }
            Text(status)
            OutlinedTextField(title, { title = it; origin = "user" }, label = { Text("Reference title") })
            OutlinedTextField(url, { url = it; origin = "user" }, label = { Text("HTTPS URL") })
            OutlinedTextField(excerpt, { excerpt = it; origin = "user" }, label = { Text("Supporting excerpt (required)") }, minLines = 4)
        }
    }, confirmButton = { TextButton(enabled = !busy && sources.size < 8 && CompanionReferences.validUrl(url) && title.isNotBlank() && excerpt.isNotBlank(), onClick = { scope.launch {
        busy = true
        try { service.addSource(profile.id, CompanionReference(title, url, excerpt), origin); dismiss() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = e.message.orEmpty(); busy = false }
    } }) { Text("Add reviewed reference") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
