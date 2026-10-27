package io.github.zyrouge.symphony.services.diagnostics

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/** Never reads bodies, credentials, query strings or arbitrary server error text. */
class DiagnosticInterceptor(private val source: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val id = UUID.randomUUID().toString()
        val request = original.newBuilder().header("X-Request-ID", id).build()
        val start = System.nanoTime()
        val base = mapOf("request_id" to id, "method" to request.method, "route" to DiagnosticPrivacy.route(request.url.encodedPath))
        Diagnostics.event("INFO", source, "request_started", fields = base)
        try {
            val response = chain.proceed(request)
            val fields = base + mapOf("status" to response.code.toString(), "duration_ms" to ((System.nanoTime() - start) / 1_000_000).toString()) +
                (DiagnosticPrivacy.requestId(response.header("X-Request-ID"))?.let { mapOf("server_request_id" to it) } ?: emptyMap())
            Diagnostics.event(if (response.isSuccessful) "INFO" else "ERROR", source, "http_response", fields = fields)
            return response
        } catch (e: Exception) {
            Diagnostics.event("ERROR", source, "network_failure", e, base + mapOf("duration_ms" to ((System.nanoTime() - start) / 1_000_000).toString()))
            throw e
        }
    }
}
