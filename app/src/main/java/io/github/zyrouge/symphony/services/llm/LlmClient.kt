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
 * کلاینت خام برای هر سرور OpenAI-compatible.
 * هیچی از موزیک و میکس نمیدونه — فقط پیام میفرسته و جواب میگیره.
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
    ): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.Error("LLM is not configured")
        }
        try {
            val body = JSONObject()
                .put("model", model)
                .put("temperature", temperature.toDouble())
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )

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

    suspend fun testConnection(): Result = complete(
        system = "You are a helpful assistant.",
        user = "Reply with exactly: OK",
        temperature = 0f,
    )
}
