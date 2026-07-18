package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.database.entities.ChatMessage
import io.github.zyrouge.symphony.services.database.entities.ChatSession
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.services.database.entities.promptList
import io.github.zyrouge.symphony.services.llm.ChatTurn
import io.github.zyrouge.symphony.services.llm.DiscoverChatAction
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.ui.components.AddToPlaylistDialog
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class DiscoverChatRoute(
    val sessionId: Long = -1L,
    val editMixId: Long = -1L,
)

private sealed class ChatItem {
    data class UserMsg(val text: String) : ChatItem()
    data class BotMsg(val text: String) : ChatItem()
    data class BotResults(
        val text: String,
        val prompts: List<String>,
        val songIds: List<String>,
    ) : ChatItem()
}

@Composable
fun DiscoverChatView(context: ViewContext, route: DiscoverChatRoute) {
    val coroutineScope = rememberCoroutineScope()
    val chatStore = context.symphony.database.chats

    val items = remember { mutableStateListOf<ChatItem>() }
    val llmHistory = remember { mutableListOf<ChatTurn>() }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    var editMix by remember { mutableStateOf<CustomMix?>(null) }
    var input by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var addToPlaylistFor by remember { mutableStateOf<List<String>?>(null) }
    var saveAsMixFor by remember { mutableStateOf<ChatItem.BotResults?>(null) }
    val listState = rememberLazyListState()

    fun titlesOf(songIds: List<String>) = songIds.take(10).mapNotNull {
        context.symphony.groove.song.get(it)?.title
    }

    fun historySummary(reply: String, prompts: List<String>, songIds: List<String>) =
        reply +
                "\n[prompts: ${prompts.joinToString(" | ")}]" +
                "\n[top results: ${titlesOf(songIds).joinToString("; ")}]"

    suspend fun ensureSession(firstText: String): Long {
        sessionId?.let { return it }
        val id = chatStore.insertSession(ChatSession(title = firstText.take(60)))
        sessionId = id
        return id
    }

    suspend fun persist(
        kind: String,
        text: String,
        prompts: List<String> = emptyList(),
        songIds: List<String> = emptyList(),
    ) {
        val sid = sessionId ?: return
        chatStore.insertMessage(
            ChatMessage(
                sessionId = sid,
                kind = kind,
                text = text,
                prompts = prompts.joinToString("\n"),
                songIds = songIds.joinToString("\n"),
            )
        )
        chatStore.touchSession(sid, System.currentTimeMillis())
    }

    // Ø¨Ø§Ø²ÛŒØ§Ø¨ÛŒ Ú†Øª Ù‚Ø¨Ù„ÛŒ Ùˆ/ÛŒØ§ Ú©Ø§Ù†ØªÚ©Ø³Øª ÙˆÛŒØ±Ø§ÛŒØ´ Ù…ÛŒÚ©Ø³
    LaunchedEffect(Unit) {
        if (route.sessionId >= 0) {
            sessionId = route.sessionId
            chatStore.getMessages(route.sessionId).forEach { msg ->
                when (msg.kind) {
                    "user" -> {
                        items.add(ChatItem.UserMsg(msg.text))
                        llmHistory.add(ChatTurn("user", msg.text))
                    }

                    "results" -> {
                        val prompts = msg.prompts.split("\n").filter { it.isNotBlank() }
                        val songIds = msg.songIds.split("\n").filter { it.isNotBlank() }
                        items.add(ChatItem.BotResults(msg.text, prompts, songIds))
                        llmHistory.add(
                            ChatTurn("assistant", historySummary(msg.text, prompts, songIds))
                        )
                    }

                    else -> {
                        items.add(ChatItem.BotMsg(msg.text))
                        llmHistory.add(ChatTurn("assistant", msg.text))
                    }
                }
            }
        }
        if (route.editMixId >= 0) {
            context.symphony.database.customMixes.getAll().first()
                .find { it.id == route.editMixId }
                ?.let { mix ->
                    editMix = mix
                    llmHistory.add(
                        ChatTurn(
                            "user",
                            "I want to refine an existing mix named \"${mix.name}\". " +
                                    "Description: ${mix.description}. " +
                                    "Current prompts: ${mix.promptList().joinToString(" | ")}. " +
                                    "Wait for my feedback, then improve the prompts.",
                        )
                    )
                    items.add(
                        ChatItem.BotMsg(
                            "Editing \"${mix.icon} ${mix.name}\" â€” tell me what to change " +
                                    "and I'll rework its prompts."
                        )
                    )
                }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isThinking) return
        input = ""
        items.add(ChatItem.UserMsg(text))
        llmHistory.add(ChatTurn("user", text))
        coroutineScope.launch {
            ensureSession(text)
            persist("user", text)
            isThinking = true
            streamingText = null
            val action = context.symphony.llmTasks.discoverChat(llmHistory) { delta ->
                streamingText = (streamingText ?: "") + delta
            }
            streamingText = null
            when (action) {
                is DiscoverChatAction.Failed -> items.add(
                    ChatItem.BotMsg("âš ï¸ ${action.message}")
                )

                is DiscoverChatAction.Ask -> {
                    items.add(ChatItem.BotMsg(action.reply))
                    llmHistory.add(ChatTurn("assistant", action.reply))
                    persist("bot", action.reply)
                }

                is DiscoverChatAction.Search -> {
                    val songIds = runDiscoverSearch(context, action.prompts)
                    items.add(ChatItem.BotResults(action.reply, action.prompts, songIds))
                    llmHistory.add(
                        ChatTurn(
                            "assistant",
                            historySummary(action.reply, action.prompts, songIds),
                        )
                    )
                    persist("results", action.reply, action.prompts, songIds)
                }
            }
            isThinking = false
        }
    }

    LaunchedEffect(items.size, isThinking, streamingText?.length) {
        delay(80)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    GlassSettingsScaffold(
        context,
        title = editMix?.let { "Editing ${it.name}" } ?: "AI Chat",
        topBarActions = {
            GlassSurface(modifier = Modifier.size(44.dp), shape = CircleShape) {
                IconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = { showHistory = true },
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, "History")
                }
            }
            GlassSurface(modifier = Modifier.size(44.dp), shape = CircleShape) {
                IconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = {
                        items.clear()
                        llmHistory.clear()
                        sessionId = null
                        editMix = null
                    },
                ) {
                    Icon(Icons.Filled.Add, "New chat")
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (items.isEmpty()) {
                    item {
                        BotBubble(
                            "Tell me what you're in the mood for â€” a genre, a feeling, " +
                                    "a moment. I'll dig through your library and we can " +
                                    "refine it together."
                        )
                    }
                }
                items.forEach { chatItem ->
                    item {
                        when (chatItem) {
                            is ChatItem.UserMsg -> UserBubble(chatItem.text)
                            is ChatItem.BotMsg -> BotBubble(chatItem.text)
                            is ChatItem.BotResults -> ResultsBlock(
                                context,
                                chatItem,
                                editMix,
                                onSaveToPlaylist = { addToPlaylistFor = chatItem.songIds },
                                onSaveAsMix = { saveAsMixFor = chatItem },
                            )
                        }
                    }
                }
                if (isThinking) {
                    item {
                        val streamed = streamingText
                        if (streamed.isNullOrEmpty()) {
                            GlassSurface(shape = RoundedCornerShape(18.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp,
                                    ),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Thinkingâ€¦",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        } else {
                            BotBubble("$streamedâ–Œ")
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassSurface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Describe what you wantâ€¦") },
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GlassSurface(modifier = Modifier.size(48.dp), shape = CircleShape) {
                    IconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = { send() },
                        enabled = !isThinking,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }

    if (showHistory) {
        ChatHistorySheet(
            context,
            onDismiss = { showHistory = false },
            onOpen = { session ->
                showHistory = false
                context.navController.navigate(DiscoverChatRoute(sessionId = session.id))
            },
        )
    }
    addToPlaylistFor?.let { ids ->
        AddToPlaylistDialog(
            context,
            songIds = ids,
            onDismissRequest = { addToPlaylistFor = null },
        )
    }
    saveAsMixFor?.let { res ->
        SaveAsMixDialog(
            context,
            results = res,
            description = (items.firstOrNull { it is ChatItem.UserMsg }
                    as? ChatItem.UserMsg)?.text ?: "",
            onDismiss = { saveAsMixFor = null },
        )
    }
}

@Composable
private fun ChatHistorySheet(
    context: ViewContext,
    onDismiss: () -> Unit,
    onOpen: (ChatSession) -> Unit,
) {
    val chatStore = context.symphony.database.chats
    val coroutineScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }

    LaunchedEffect(Unit) {
        sessions = chatStore.getSessions()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Chat history",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    "No saved chats yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
            sessions.forEach { session ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(session) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            session.title.ifBlank { "Untitled chat" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        Text(
                            dateFormat.format(Date(session.updatedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            chatStore.deleteMessages(session.id)
                            chatStore.deleteSession(session.id)
                            sessions = chatStore.getSessions()
                        }
                    }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Box(
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                ),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BotBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ResultsBlock(
    context: ViewContext,
    item: ChatItem.BotResults,
    editMix: CustomMix?,
    onSaveToPlaylist: () -> Unit,
    onSaveAsMix: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var updated by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BotBubble(item.text)
        if (item.songIds.isEmpty()) {
            BotBubble("No matches found in your library â€” try describing it differently.")
            return
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                val visible = when {
                    expanded -> item.songIds
                    else -> item.songIds.take(3)
                }
                visible.forEach { songId ->
                    context.symphony.groove.song.get(songId)?.let { song ->
                        SongCard(context, song) {
                            context.symphony.radio.playbackSource = "discover_chat"
                            context.symphony.radio.shorty.playQueue(
                                item.songIds,
                                options = Radio.PlayOptions(
                                    index = item.songIds.indexOf(songId),
                                ),
                            )
                        }
                    }
                }
                if (item.songIds.size > 3) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            when {
                                expanded -> "Show less"
                                else -> "+${item.songIds.size - 3} more"
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = {
                        context.symphony.radio.playbackSource = "discover_chat"
                        context.symphony.radio.shorty.playQueue(item.songIds)
                    }) { Text("Play all") }
                    TextButton(onClick = onSaveToPlaylist) { Text("Save") }
                    when {
                        editMix != null -> TextButton(
                            enabled = !updated,
                            onClick = {
                                coroutineScope.launch {
                                    context.symphony.database.customMixes.update(
                                        editMix.copy(
                                            prompt = item.prompts.firstOrNull()
                                                ?: editMix.prompt,
                                            prompts = item.prompts.joinToString("\n"),
                                        )
                                    )
                                    updated = true
                                }
                            },
                        ) { Text(if (updated) "Updated âœ“" else "Update mix") }

                        else -> TextButton(onClick = onSaveAsMix) { Text("Save as Mix") }
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
    var icon by remember { mutableStateOf("ðŸ’¬") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as Mix") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = icon,
                        onValueChange = { icon = it },
                        label = { Text("Icon") },
                        modifier = Modifier.width(80.dp),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Prompts:\n${results.prompts.joinToString("\n")}",
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
                                name = name.trim(),
                                icon = icon.ifBlank { "ðŸ’¬" },
                                prompt = results.prompts.firstOrNull() ?: "",
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

private suspend fun runDiscoverSearch(
    context: ViewContext,
    prompts: List<String>,
    limit: Int = 20,
): List<String> {
    val per = (limit * 2 / prompts.size.coerceAtLeast(1)).coerceAtLeast(5)
    val ids = LinkedHashSet<String>()
    for (prompt in prompts) {
        val results = context.symphony.semanticSearch.searchDetailed(prompt, per)
        for (r in results) {
            context.symphony.recommendation
                .resolvePathToSongId(r.track.filePath)
                ?.let { ids.add(it) }
        }
    }
    return ids.take(limit).toList()
}
