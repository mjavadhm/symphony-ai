package io.github.zyrouge.symphony.services.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object Diagnostics {
    private var store: DiagnosticStore? = null
    private var prefs: android.content.SharedPreferences? = null
    private var metadata = buildJsonObject { }
    private val session = UUID.randomUUID().toString()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { job -> Thread(job, "diagnostic-writer").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    val enabled get() = prefs?.getBoolean("enabled", true) ?: true

    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        store = DiagnosticStore(java.io.File(app.noBackupFilesDir, "diagnostics"))
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        metadata = buildJsonObject {
            put("appVersion", DiagnosticPrivacy.token(version)); put("androidApi", Build.VERSION.SDK_INT)
            put("deviceModel", DiagnosticPrivacy.token(Build.MODEL)); put("manufacturer", DiagnosticPrivacy.token(Build.MANUFACTURER))
            put("storageLimitBytes", DiagnosticStore.MAX_BYTES); put("ageExpiry", false)
            put("privacy", "No request/response bodies, query strings, credentials, lyric/chat text, exception messages or device IDs")
        }
        event("INFO", "application", "session_started")
    }
    fun setEnabled(value: Boolean) { prefs?.edit()?.putBoolean("enabled", value)?.apply() }
    private fun entry(level: String, source: String, code: String, stack: String?, fields: Map<String, String>): JsonObject = buildJsonObject {
        put("id", UUID.randomUUID().toString()); put("time", System.currentTimeMillis()); put("session", session)
        metadata["appVersion"]?.let { put("appVersion", it) }; metadata["androidApi"]?.let { put("androidApi", it) }
        put("level", DiagnosticPrivacy.token(level)); put("source", DiagnosticPrivacy.token(source)); put("code", DiagnosticPrivacy.token(code))
        if (stack != null) put("stack", stack)
        val allowed = setOf("request_id", "server_request_id", "method", "route", "status", "duration_ms", "exception_type")
        fields.filterKeys { it in allowed }.forEach { (k, v) -> put(k, if (k == "route") DiagnosticPrivacy.route(v) else DiagnosticPrivacy.token(v)) }
    }
    fun event(level: String, source: String, code: String, error: Throwable? = null, fields: Map<String, String> = emptyMap()) {
        if (!enabled || store == null) return
        val record = entry(level, source, code, error?.let(DiagnosticPrivacy::throwable), fields)
        executor.execute { runCatching { store?.append(record); mutableRevision.value += 1 } }
    }
    /** Fatal events bypass the queue and fsync before the dying process delegates to Android. */
    fun crash(error: Throwable): String {
        val stack = DiagnosticPrivacy.throwable(error)
        if (enabled) runCatching { store?.append(entry("CRASH", "application", "uncaught_exception", stack, emptyMap()), crash = true) }
        return stack
    }
    fun recent() = store?.recent().orEmpty()
    fun usage() = store?.usage() ?: 0L
    private fun <T> barrier(action: () -> T): T {
        val task = java.util.concurrent.FutureTask(java.util.concurrent.Callable { action() })
        executor.prestartCoreThread()
        check(executor.queue.offer(task, 10, TimeUnit.SECONDS)) { "Diagnostic writer is busy" }
        return task.get(30, TimeUnit.SECONDS)
    }
    fun clear() { barrier { store?.clear(); mutableRevision.value += 1 } }
    fun export(output: OutputStream) {
        // Drain preceding records before exporting. Export itself never recursively creates logs.
        barrier {}
        store?.export(output, metadata) ?: error("Diagnostics are not initialized")
    }
}
