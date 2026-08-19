package io.github.zyrouge.symphony.services.llm

import io.github.zyrouge.symphony.Symphony
import org.json.JSONArray
import org.json.JSONObject

data class ChatTurn(val role: String, val content: String)

sealed class DiscoverChatAction {
    data class Ask(val reply: String) : DiscoverChatAction()
    data class Search(val reply: String, val prompts: List<String>) : DiscoverChatAction()
    data class Failed(val message: String) : DiscoverChatAction()
}

/**
 * Meaningful tasks built on top of LlmClient.
 * Every new LLM feature should just be one more function in here.
 */
class LlmTasks(private val symphony: Symphony) {
    private val client get() = symphony.llm

    /**
     * Turns a mix description into a handful of CLAP-friendly prompts.
     * Returns null on failure (network, API key, malformed model output).
     */
    suspend fun generateMixPrompts(description: String, count: Int = 4): List<String>? {
        suspend fun attempt(): List<String>? {
            val result = client.complete(
                system = client.mixPromptsSystem,
                user = "Mix description: $description\n" +
                        "Generate exactly $count prompts. " +
                        "Return a JSON array with exactly $count strings.",
            )
            return when (result) {
                is LlmClient.Result.Error -> null
                is LlmClient.Result.Success -> parseStringArray(result.content)
                    ?.take(count)
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        // If the model returned less than half of what we asked for, try once more.
        val first = attempt() ?: return null
        if (first.size * 2 >= count) return first
        return attempt()?.takeIf { it.size > first.size } ?: first
    }

    /**
     * Comes up with a short, playlist-style name for a list of songs (used for Daily Mixes).
     */
    suspend fun nameMix(titles: List<String>, artists: List<String>): String? {
        val result = client.complete(
            system = client.nameMixSystem,
            user = "Songs: ${titles.joinToString("; ")}\n" +
                    "Artists: ${artists.joinToString("; ")}",
            temperature = 0.8f,
        )
        return when (result) {
            is LlmClient.Result.Error -> null
            is LlmClient.Result.Success -> result.content
                .trim()
                .trim('"', '\'', '.')
                .takeIf { it.isNotBlank() && it.length <= 40 }
        }
    }

    suspend fun discoverChat(
        history: List<ChatTurn>,
        onReplyDelta: (String) -> Unit = {},
    ): DiscoverChatAction {
        val extractor = ReplyStreamExtractor(onReplyDelta)
        val result = client.completeMessagesStreaming(
            system = client.chatBehavior + "\n\n" + client.chatStructure,
            messages = history.map { it.role to it.content },
            temperature = 0.7f,
            onDelta = { extractor.feed(it) },
        )
        return when (result) {
            is LlmClient.Result.Error -> DiscoverChatAction.Failed(result.message)
            is LlmClient.Result.Success -> parseChatAction(result.content)
        }
    }

    /**
     * Lenient parser: if the JSON is malformed, treat the whole response as a plain
     * message so the chat never breaks.
     */
    private fun parseChatAction(text: String): DiscoverChatAction {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return DiscoverChatAction.Ask(text.trim().take(500))
        }
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            val reply = obj.optString("reply").ifBlank { "…" }
            when (obj.optString("action")) {
                "search" -> {
                    val arr = obj.optJSONArray("prompts")
                    val prompts = (0 until (arr?.length() ?: 0))
                        .mapNotNull { i -> arr?.optString(i)?.takeIf { it.isNotBlank() } }
                    if (prompts.isEmpty()) DiscoverChatAction.Ask(reply)
                    else DiscoverChatAction.Search(reply, prompts)
                }
                else -> DiscoverChatAction.Ask(reply)
            }
        } catch (e: Exception) {
            DiscoverChatAction.Ask(text.trim().take(500))
        }
    }

    /**
     * Lenient parser: pulls out the first [...] found in the text, because weaker
     * models sometimes wrap the JSON in extra prose.
     */
    private fun parseStringArray(text: String): List<String>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val arr = JSONArray(text.substring(start, end + 1))
            (0 until arr.length())
                .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Extracts only the "reply" field out of the JSON stream as it arrives, so the user
 * sees the text typing out while the JSON and the prompts stay hidden.
 * If the response is not JSON at all (for example because the structural prompt was
 * edited), it falls back to raw mode and streams the whole text. It never crashes.
 */
private class ReplyStreamExtractor(private val onDelta: (String) -> Unit) {
    private val buffer = StringBuilder()
    private var replyStart = -1
    private var emitted = 0
    private var plainMode = false
    private var finished = false

    fun feed(delta: String) {
        if (finished) return
        buffer.append(delta)

        if (!plainMode && replyStart < 0) {
            val i = buffer.indexOf("\"reply\"")
            if (i >= 0) {
                var j = i + 7
                while (j < buffer.length && (buffer[j] == ':' || buffer[j].isWhitespace())) j++
                if (j < buffer.length) {
                    if (buffer[j] == '"') replyStart = j + 1
                    else plainMode = true // unexpected structure
                }
            } else if (buffer.length > 24 && !buffer.contains("{")) {
                plainMode = true // the model returned no JSON; show the raw text
            }
        }

        if (plainMode) {
            if (buffer.length > emitted) {
                onDelta(buffer.substring(emitted))
                emitted = buffer.length
            }
            return
        }
        if (replyStart < 0) return

        // Walk forward from the start of reply, unescaping as we go, until the closing quote.
        val out = StringBuilder()
        var k = replyStart
        var decoded = 0
        loop@ while (k < buffer.length) {
            when (val c = buffer[k]) {
                '\\' -> {
                    if (k + 1 >= buffer.length) break@loop // incomplete escape; wait for more
                    val n = buffer[k + 1]
                    var step = 2
                    val ch = when (n) {
                        'n' -> '\n'
                        't' -> '\t'
                        'u' -> {
                            if (k + 5 >= buffer.length) break@loop
                            step = 6
                            buffer.substring(k + 2, k + 6)
                                .toIntOrNull(16)?.toChar() ?: '?'
                        }
                        else -> n
                    }
                    decoded++
                    if (decoded > emitted) out.append(ch)
                    k += step
                }

                '"' -> {
                    finished = true
                    break@loop
                }

                else -> {
                    decoded++
                    if (decoded > emitted) out.append(c)
                    k++
                }
            }
        }
        if (out.isNotEmpty()) {
            onDelta(out.toString())
            emitted = decoded
        }
    }
}
