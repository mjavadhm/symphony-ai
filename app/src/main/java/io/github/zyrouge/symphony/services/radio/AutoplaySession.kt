package io.github.zyrouge.symphony.services.radio

/**
 * Owns the seed and the single in-flight recommendation request for a playback session.
 * Completion and reset share a lock so an old result cannot be applied after a reset.
 * Kept independent of Android and coroutines so the lifecycle can be unit tested.
 */
internal class AutoplaySession {
    internal class Request(
        val sessionId: Long,
        val anchorSongId: String?,
        var advanceFromIndex: Int?,
    ) {
        var cancel: (() -> Unit)? = null
    }

    private var sessionId = 0L
    private var anchorSongId: String? = null
    private var pending: Request? = null

    /** Returns whether the previous session was a song radio. */
    @Synchronized
    fun reset(anchorSongId: String? = null): Boolean {
        val wasSongRadio = this.anchorSongId != null
        sessionId++
        this.anchorSongId = anchorSongId
        val old = pending
        pending = null
        old?.cancel?.invoke()
        return wasSongRadio
    }

    /**
     * Coalesce repeated skips/end-of-track events into the current request.
     * An advance arriving during prefetch must still advance when that prefetch completes.
     */
    @Synchronized
    fun begin(advanceFromIndex: Int? = null): Request? {
        pending?.let {
            if (advanceFromIndex != null) it.advanceFromIndex = advanceFromIndex
            return null
        }
        return Request(sessionId, anchorSongId, advanceFromIndex).also { pending = it }
    }

    @Synchronized
    fun onCancel(request: Request, cancel: () -> Unit) {
        if (pending === request && request.sessionId == sessionId) {
            request.cancel = cancel
        } else {
            // A reset can happen between creating the request and attaching its job.
            cancel()
        }
    }

    @Synchronized
    fun complete(request: Request, apply: (advanceFromIndex: Int?) -> Unit): Boolean {
        if (pending !== request || request.sessionId != sessionId) return false
        pending = null
        apply(request.advanceFromIndex)
        return true
    }

    @Synchronized
    fun abandon(request: Request) {
        if (pending === request) pending = null
    }
}
