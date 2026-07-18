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
 * تسکهای معنادار روی LlmClient.
 * هر فیچر جدید LLM = فقط یه تابع جدید اینجا.
 */
class LlmTasks(private val symphony: Symphony) {
    private val client get() = symphony.llm

    /**
     * از روی description میکس، چند پرامپت CLAP-پسند میسازه.
     * null یعنی خطا (نت، key، جواب خراب مدل).
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
        // اگه مدل کمتر از نصف تعداد خواستهشده داد، یه بار دیگه امتحان کن
        val first = attempt() ?: return null
        if (first.size * 2 >= count) return first
        return attempt()?.takeIf { it.size > first.size } ?: first
    }

    /**
     * برای یه لیست آهنگ، یه اسم کوتاه پلیلیستی میسازه (برای Daily Mix ها).
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
     * پارسر سهلگیر: اگه JSON خراب بود، کل متن رو یه پیام معمولی فرض میکنیم
     * تا چت هیچوقت نشکنه.
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
     * پارسر سهلگیر: اولین [...] توی متن رو درمیاره،
     * چون مدلهای ضعیفتر گاهی دور JSON حرف اضافه میزنن.
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
 * از توی استریم JSON، فقط محتوای فیلد "reply" رو زنده بیرون میکشه —
 * پس کاربر متن رو تایپ‌شونده میبینه ولی JSON و پرامپت‌ها دیده نمیشن.
 * اگه جواب اصلا JSON نبود (مثلا کاربر پرامپت ساختاری رو عوض کرده)،
 * میره روی حالت خام و کل متن رو استریم میکنه. هیچوقت کرش نمیکنه.
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
                    else plainMode = true // ساختار غیرمنتظره
                }
            } else if (buffer.length > 24 && !buffer.contains("{")) {
                plainMode = true // مدل JSON نداده؛ متن خام رو نشون بده
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

        // از شروعِ reply جلو برو، escape ها رو باز کن، تا نقل‌قول بسته
        val out = StringBuilder()
        var k = replyStart
        var decoded = 0
        loop@ while (k < buffer.length) {
            when (val c = buffer[k]) {
                '\\' -> {
                    if (k + 1 >= buffer.length) break@loop // escape ناقص؛ صبر کن
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
