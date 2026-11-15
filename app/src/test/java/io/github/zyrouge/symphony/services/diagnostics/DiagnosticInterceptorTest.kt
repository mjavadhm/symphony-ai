package io.github.zyrouge.symphony.services.diagnostics

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticInterceptorTest {
    @Test fun `each call receives a fresh UUID and response echoes it even on 500`() {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(DiagnosticInterceptor("test"))
            .addInterceptor { chain ->
                val request = chain.request()
                val id = requireNotNull(request.header("X-Request-ID"))
                seen.add(id)
                assertEquals("Bearer secret", request.header("Authorization"))
                // Exercise the interceptor chain without a JDK-specific HTTP server or a socket.
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(500).message("Test failure").header("X-Request-ID", id)
                    .body("private response body".toResponseBody()).build()
            }.build()
        try {
            repeat(2) {
                val request = Request.Builder().url("https://example.test/v1/tracks/secret/status?token=secret")
                    .header("Authorization", "Bearer secret").header("X-Request-ID", "untrusted").build()
                client.newCall(request).execute().use { response ->
                    assertEquals(500, response.code)
                    assertNotNull(DiagnosticPrivacy.requestId(response.header("X-Request-ID")))
                    assertEquals(response.request.header("X-Request-ID"), response.header("X-Request-ID"))
                    assertEquals("private response body", response.body!!.string())
                }
            }
            assertEquals(2, seen.toSet().size)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
