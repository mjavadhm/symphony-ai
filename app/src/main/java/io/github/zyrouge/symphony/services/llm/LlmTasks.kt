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
