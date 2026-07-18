package io.github.zyrouge.symphony.services.llm

import io.github.zyrouge.symphony.Symphony
import org.json.JSONArray

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
        val result = client.complete(
            system = """
                You write search prompts for CLAP, a model that matches text to music audio.
                Rules:
                - Prompts must be in English.
                - Each prompt describes sound only: genre, mood, tempo, instruments, vocals.
                - Keep each prompt under 12 words.
                - Make the prompts meaningfully different from each other.
                - Reply with ONLY a JSON array of strings. No explanations, no markdown.
            """.trimIndent(),
            user = "Mix description: $description\nGenerate $count prompts.",
        )
        return when (result) {
            is LlmClient.Result.Error -> null
            is LlmClient.Result.Success -> parseStringArray(result.content)
                ?.take(count)
                ?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * برای یه لیست آهنگ، یه اسم کوتاه پلیلیستی میسازه (برای Daily Mix ها).
     */
    suspend fun nameMix(titles: List<String>, artists: List<String>): String? {
        val result = client.complete(
            system = "You name music playlists. Reply with ONLY the playlist name: " +
                    "2 to 4 words, in English, no quotes, no emoji, no explanations.",
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
