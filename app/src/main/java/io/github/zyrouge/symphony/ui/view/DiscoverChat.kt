package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.services.llm.ChatTurn
import io.github.zyrouge.symphony.services.llm.DiscoverChatAction
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.ui.components.AddToPlaylistDialog
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object DiscoverChatRoute

private sealed class ChatItem {
    data class UserMsg(val text: String) : ChatItem()
    data class BotMsg(val text: String) : ChatItem()
    data class BotResults(
        val text: String,
        val prompts: List<String>,
        val songIds: List<String>,
    ) : ChatItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverChatView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ChatItem>() }
    val llmHistory = remember { mutableListOf<ChatTurn>() }
    var input by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    var addToPlaylistFor by remember { mutableStateOf<List<String>?>(null) }
    var saveAsMixFor by remember { mutableStateOf<ChatItem.BotResults?>(null) }
    val listState = rememberLazyListState()

    // با هر پیام جدید، برو ته لیست
    LaunchedEffect(items.size, isThinking) {
        kotlinx.coroutines.delay(80)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isThinking) return
        input = ""
        items.add(ChatItem.UserMsg(text))
        llmHistory.add(ChatTurn("user", text))
        coroutineScope.launch {
            isThinking = true
            when (val action = context.symphony.llmTasks.discoverChat(llmHistory)) {
                is DiscoverChatAction.Failed -> items.add(
                    ChatItem.BotMsg("⚠️ ${action.message}")
                )

                is DiscoverChatAction.Ask -> {
                    items.add(ChatItem.BotMsg(action.reply))
                    llmHistory.add(ChatTurn("assistant", action.reply))
                }

                is DiscoverChatAction.Search -> {
                    val songIds = runDiscoverSearch(context, action.prompts)
                    items.add(
                        ChatItem.BotResults(action.reply, action.prompts, songIds)
                    )
                    // خلاصهی نتیجه برای حافظهی مدل — تا فیدبک بعدی رو بفهمه
                    val titles = songIds.take(10).mapNotNull {
                        context.symphony.groove.song.get(it)?.title
                    }
                    llmHistory.add(
                        ChatTurn(
                            "assistant",
                            action.reply +
                                    "\n[prompts: ${action.prompts.joinToString(" | ")}]" +
                                    "\n[top results: ${titles.joinToString("; ")}]",
                        )
                    )
                }
            }
            isThinking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Chat") },
                navigationIcon = {
                    IconButton(onClick = { context.navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (items.isEmpty()) {
                    item {
                        BotBubble(
                            "Describe the playlist you want — e.g. \"slow sad songs " +
                                    "for a rainy night\". I'll ask if I need more detail, " +
                                    "and you can give feedback to refine the results."
                        )
                    }
                }
                items(items) { item ->
                    when (item) {
                        is ChatItem.UserMsg -> UserBubble(item.text)
                        is ChatItem.BotMsg -> BotBubble(item.text)
                        is ChatItem.BotResults -> ResultsBlock(
                            context = context,
                            item = item,
                            onSave = { addToPlaylistFor = item.songIds },
                            onSaveAsMix = { saveAsMixFor = item },
                        )
                    }
                }
                if (isThinking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Thinking…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Describe the playlist you want…") },
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    enabled = !isThinking && input.isNotBlank(),
                    onClick = { send() },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                }
            }
        }
    }

    addToPlaylistFor?.let { ids ->
        AddToPlaylistDialog(
            context = context,
            songIds = ids,
            onDismissRequest = { addToPlaylistFor = null },
        )
    }
    saveAsMixFor?.let { results ->
        SaveAsMixDialog(
            context = context,
            results = results,
            description = items.filterIsInstance<ChatItem.UserMsg>()
                .firstOrNull()?.text ?: "",
            onDismiss = { saveAsMixFor = null },
        )
    }
}

private suspend fun runDiscoverSearch(
    context: ViewContext,
    prompts: List<String>,
    limit: Int = 20,
): List<String> {
    if (prompts.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>()
    val per = (limit * 2 / prompts.size).coerceAtLeast(5)
    for (prompt in prompts) {
        try {
            context.symphony.semanticSearch.searchDetailed(prompt, per).forEach { r ->
                r.track.filePath?.let { path ->
                    context.symphony.recommendation.resolvePathToSongId(path)
                        ?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            // این پرامپت رو رد کن، بقیه رو ادامه بده
        }
    }
    return out.take(limit)
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun BotBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ResultsBlock(
    context: ViewContext,
    item: ChatItem.BotResults,
    onSave: () -> Unit,
    onSaveAsMix: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val songs = remember(item) {
        item.songIds.mapNotNull { context.symphony.groove.song.get(it) }
    }

    Column {
        BotBubble(item.text)
        Spacer(modifier = Modifier.height(6.dp))
        when {
            songs.isEmpty() -> Text(
                "No matches found in your library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    val shown = if (expanded) songs else songs.take(3)
                    shown.forEach { song ->
                        SongCard(context = context, song = song, onClick = {
                            context.symphony.radio.playbackSource = "discover_chat"
                            context.symphony.radio.shorty.playQueue(
                                songs.map { it.id },
                                options = Radio.PlayOptions(
                                    index = songs.indexOf(song),
                                ),
                            )
                        })
                    }
                    if (songs.size > 3) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(
                                if (expanded) "Show less"
                                else "+${songs.size - 3} more"
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = {
                            context.symphony.radio.playbackSource = "discover_chat"
                            context.symphony.radio.shorty.playQueue(songs.map { it.id })
                        }) { Text("Play all") }
                        TextButton(onClick = onSave) { Text("Save") }
                        TextButton(onClick = onSaveAsMix) { Text("Save as Mix") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveAsMixDialog(
    context: ViewContext,
    results: ChatItem.BotResults,
    description: String,
    onDismiss: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("💬") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as Mix") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = icon,
                        onValueChange = { icon = it.take(2) },
                        modifier = Modifier.width(72.dp),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This mix will refresh daily using these prompts:\n" +
                            results.prompts.joinToString("\n") { "· $it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    coroutineScope.launch {
                        context.symphony.database.customMixes.insert(
                            CustomMix(
                                name = name,
                                icon = icon,
                                prompt = results.prompts.first(),
                                prompts = results.prompts.joinToString("\n"),
                                description = description.take(200),
                            )
                        )
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
