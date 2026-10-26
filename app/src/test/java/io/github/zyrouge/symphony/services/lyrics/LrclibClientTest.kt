package io.github.zyrouge.symphony.services.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LrclibClientTest {
    private val track = LyricsTrack("Test Song", setOf("Test Artist"), "Album", 180.0)
    private val plain = """{"id":1,"trackName":"Test Song","artistName":"Test Artist","albumName":"Album","duration":180,"plainLyrics":"plain"}"""
    private val synced = """{"id":2,"trackName":"Test Song","artistName":"Test Artist","duration":180,"syncedLyrics":"[00:01.20] timed"}"""
    private fun client(responses: List<Pair<Int, String>>, requests: MutableList<Request>): LrclibClient {
        var index = 0
        return LrclibClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val (code, text) = responses[index++]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test").header("Retry-After", "2")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }
    @Test fun `base URL is normalized without duplicate api prefix`() {
        assertEquals("https://example.com/proxy", LrclibClient.normalizeBaseUrl("https://example.com/proxy/api/"))
    }
    @Test fun `cleartext and credential-bearing URLs are rejected`() {
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com?q=secret", "invalid").forEach {
            assertThrows(IllegalArgumentException::class.java) { LrclibClient.normalizeBaseUrl(it) }
        }
    }
    @Test fun `search is attempted for synced lyrics even when exact lookup found plain`() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client(listOf(200 to plain, 200 to "[$synced]"), requests).find("https://example.com/proxy/api", track)
        assertTrue(result!!.synced)
        assertEquals(listOf("/proxy/api/get", "/proxy/api/search"), requests.map { it.url.encodedPath })
        assertEquals("Test Song", requests[0].url.queryParameter("track_name"))
        assertTrue(requests[0].header("User-Agent")!!.contains("Symphony"))
    }
    @Test fun `exact synced result avoids extra requests`() = runBlocking {
        val requests = mutableListOf<Request>()
        assertTrue(client(listOf(200 to synced), requests).find("https://example.com", track)!!.synced)
        assertEquals(1, requests.size)
    }
    @Test fun `exact plain result is kept when search has no better result`() = runBlocking {
        val requests = mutableListOf<Request>()
        assertFalse(client(listOf(200 to plain, 200 to "[]"), requests).find("https://example.com", track)!!.synced)
    }
    @Test fun `not found is not stored as a successful lyric`() = runBlocking {
        assertNull(client(listOf(404 to "{}", 200 to "[]"), mutableListOf()).find("https://example.com", track))
    }
    @Test fun `rate limiting stops rather than hammering the provider`() {
        val requests = mutableListOf<Request>()
        val exception = assertThrows(LyricsApiException::class.java) {
            runBlocking { client(listOf(429 to "{}"), requests).find("https://example.com", track) }
        }
        assertEquals(429, exception.code)
        assertEquals(2000, exception.retryAfterMs)
        assertEquals(1, requests.size)
    }
}
