package io.github.zyrouge.symphony.services.llm

import android.content.Context
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bare-bones client for any OpenAI-compatible server.
 * It knows nothing about music or mixes: it only sends messages and returns answers.
 */
class LlmClient(private val symphony: Symphony) {
    enum class UsageMode { Off, Manual, Auto }

    sealed class Result {
        data class Success(val content: String) : Result()
        data class Error(val message: String) : Result()
    }

    private val prefs
        get() = symphony.applicationContext
            .getSharedPreferences("llm_prefs", Context.MODE_PRIVATE)

    companion object {
        val DEFAULT_MIX_PROMPTS_SYSTEM = """
            You write search prompts for CLAP, a model that matches text to music audio.
            Rules:
            - Prompts must be in English.
            - Each prompt describes sound only: genre, mood, tempo, instruments, vocals.
            - Keep each prompt under 12 words.
            - Make the prompts meaningfully different from each other.
            - You MUST return exactly the requested number of prompts.
            - Reply with ONLY a JSON array of strings. No explanations, no markdown.
        """.trimIndent()

        val DEFAULT_NAME_MIX_SYSTEM =
            "You name music playlists. Reply with ONLY the playlist name: " +
                    "2 to 4 words, in English, no quotes, no emoji, no explanations."

        val DEFAULT_CHAT_BEHAVIOR = """
            You are a friendly, opinionated music companion helping the user build playlists from their local library.
            Talk naturally in 1-3 sentences — not telegraphic.
            Say what direction you searched and why, offer opinions, and invite feedback
            (e.g. "if these feel too calm, I can raise the tempo").
            If the request is vague, make a reasonable guess, search, and confirm — don't interrogate.
            Write in the same language the user writes in.
        """.trimIndent()

        val DEFAULT_CHAT_STRUCTURE = """
            You cannot see or pick songs directly. Songs are found by CLAP, a model that matches English text prompts to music audio.
            Every turn, reply with ONLY a JSON object, no markdown:
            - Need more info? {"action":"ask","reply":"<your message>"}
            - Ready to search? {"action":"search","reply":"<your message>","prompts":["...","..."]}
            Prompt rules: 2-4 prompts, English only, describe sound (genre, mood, tempo, instruments, vocals), under 12 words each, meaningfully different.
            When the user gives feedback on results, refine the prompts.
        """.trimIndent()
    }

    var chatBehavior: String
        get() = prefs.getString("tpl_chat_behavior", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_BEHAVIOR
        set(v) = prefs.edit().putString("tpl_chat_behavior", v).apply()

    var chatStructure: String
        get() = prefs.getString("tpl_chat_structure", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_STRUCTURE
        set(v) = prefs.edit().putString("tpl_chat_structure", v).apply()

    var mixPromptsSystem: String
        get() = prefs.getString("tpl_mix_prompts", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_MIX_PROMPTS_SYSTEM
        set(v) = prefs.edit().putString("tpl_mix_prompts", v).apply()

    var nameMixSystem: String
        get() = prefs.getString("tpl_name_mix", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME_MIX_SYSTEM
        set(v) = prefs.edit().putString("tpl_name_mix", v).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var usageMode: UsageMode
        get() = when (prefs.getString("usage_mode", "Off")) {
            "Manual" -> UsageMode.Manual
            "Auto" -> UsageMode.Auto
            else -> UsageMode.Off
        }
        set(v) = prefs.edit().putString("usage_mode", v.name).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    suspend fun complete(
        system: String,
        user: String,
        temperature: Float = 0.9f,
    ): Result = completeMessages(system, listOf("user" to user), temperature)

    suspend fun completeMessages(
        system: String,
        messages: List<Pair<String, String>>, // role to content
        temperature: Float = 0.9f,
    ): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.Error("LLM is not configured")
        }
        try {
            val messagesJson = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
            for ((role, content) in messages) {
                messagesJson.put(JSONObject().put("role", role).put("content", content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("temperature", temperature.toDouble())
                .put("messages", messagesJson)

            val conn = URL("$baseUrl/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.doOutput = true
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext Result.Error("HTTP $code: ${text.take(200)}")
            }

            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            Result.Success(content)
        } catch (e: Exception) {
            Logger.error("LlmClient", "request failed", e)
            Result.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Same as completeMessages but with stream: true.
     * Every chunk of text coming back from the model is handed to onDelta right away.
     * When the stream ends, the full accumulated text is returned as Success.
     */
    suspend fun completeMessagesStreaming(
        system: String,
        messages: List<Pair<String, String>>,
        temperature: Float = 0.9f,
        onDelta: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.Error("LLM is not configured")
        }
        try {
            val messagesJson = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
            for ((role, content) in messages) {
                messagesJson.put(JSONObject().put("role", role).put("content", content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("temperature", temperature.toDouble())
                .put("stream", true)
                .put("messages", messagesJson)

            val conn = URL("$baseUrl/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.doOutput = true
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                return@withContext Result.Error("HTTP $code: ${err.take(200)}")
            }

            // SSE format: every useful line starts with "data:" and the stream ends with [DONE]
            val full = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = try {
                        JSONObject(data)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .optJSONObject("delta")
                            ?.optString("content")
                            ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                }
            }
            conn.disconnect()
            Result.Success(full.toString())
        } catch (e: Exception) {
            Logger.error("LlmClient", "stream request failed", e)
            Result.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun testConnection(): Result = complete(
        system = "You are a helpful assistant.",
        user = "Reply with exactly: OK",
        temperature = 0f,
    )
}
