package io.github.zyrouge.symphony.services.companion

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CompanionReference(val title: String, val url: String, val excerpt: String)
class CompanionReferences {
    private val http = OkHttpClient.Builder().addInterceptor(io.github.zyrouge.symphony.services.diagnostics.DiagnosticInterceptor("references")).callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()
    companion object {
        fun validUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty()
        } == true
    }
    suspend fun search(title: String, artists: String): List<CompanionReference> {
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrnamespace", "0")
            .addQueryParameter("gsrlimit", "3").addQueryParameter("gsrsearch", "\"$title\" $artists song")
            .addQueryParameter("prop", "extracts|info").addQueryParameter("explaintext", "1")
            .addQueryParameter("exchars", "10000").addQueryParameter("exlimit", "max")
            .addQueryParameter("inprop", "url").build()
        val body: String = suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url)
                .header("User-Agent", "SymphonySongCompanion/1.0 (https://github.com/mjavadhm/symphony-ai)").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("Reference search HTTP ${it.code}; add a source manually instead")
                            val body = it.body ?: throw IOException("Empty reference response")
                            body.source().request(500_001)
                            require(body.source().buffer.size <= 500_000) { "Reference response is too large" }
                            body.string()
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
        val pages = Json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject
            ?: return emptyList()
        return pages.values.mapNotNull { entry ->
            val page = entry.jsonObject
            val sourceUrl = page["fullurl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val excerpt = page["extract"]?.jsonPrimitive?.contentOrNull?.take(10_000).orEmpty()
            if (excerpt.isBlank() || sourceUrl.toHttpUrlOrNull()?.host != "en.wikipedia.org" || !validUrl(sourceUrl)) return@mapNotNull null
            CompanionReference(page["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), sourceUrl, excerpt)
        }.take(3)
    }
}
