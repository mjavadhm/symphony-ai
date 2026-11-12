package io.github.zyrouge.symphony.services.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One in-flight HTTP request; spacing is measured after completion, not merely between starts. */
class LyricsRequestGate(
    private val spacingMs: Long = 1500,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextAt = 0L
    @Volatile var cooldownUntil: Long = 0L
        private set
    fun restoreCooldown(until: Long) { cooldownUntil = maxOf(cooldownUntil, until) }
    fun defer(retryAfterMs: Long) {
        val wait = retryAfterMs.coerceAtLeast(1000).coerceAtMost(Long.MAX_VALUE - now() - 10_001)
        val jitter = Random.nextLong(0, (wait / 5).coerceIn(1, 10_000) + 1)
        restoreCooldown(now() + wait + jitter)
    }
    suspend fun <T> request(onWait: (Long) -> Unit = {}, block: suspend () -> T): T = mutex.withLock {
        while (true) {
            val remaining = maxOf(nextAt, cooldownUntil) - now()
            if (remaining <= 0) break
            if (cooldownUntil > now()) onWait(remaining)
            sleep(minOf(remaining, 1000))
        }
        onWait(0)
        try { block() } finally { nextAt = now() + spacingMs.coerceAtLeast(0) }
    }
}
