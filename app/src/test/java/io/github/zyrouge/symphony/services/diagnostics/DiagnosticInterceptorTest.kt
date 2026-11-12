package io.github.zyrouge.symphony.services.diagnostics

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class DiagnosticInterceptorTest {
    @Test fun `each call receives a fresh UUID and response echoes it even on 500`() {
        val seen = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/tracks/secret/status") { exchange ->
            val id = exchange.requestHeaders.getFirst("X-Request-ID")
            synchronized(seen) { seen.add(id) }
            assertEquals("Bearer secret", exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.set("X-Request-ID", id)
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.use { it.write("private response body".toByteArray()) }
        }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(DiagnosticInterceptor("test")).build()
        try {
            repeat(2) {
                val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/v1/tracks/secret/status?token=secret")
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
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
