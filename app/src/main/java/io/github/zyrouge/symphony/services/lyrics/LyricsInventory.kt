package io.github.zyrouge.symphony.services.lyrics

/** Local presence classification has no network dependency. Read failures propagate, not "missing". */
object LyricsInventory {
    enum class Presence { EMBEDDED, SIDECAR, MISSING }
    fun inspect(readEmbedded: () -> String?, readSidecar: () -> String?): Presence = when {
        !readEmbedded().isNullOrBlank() -> Presence.EMBEDDED
        !readSidecar().isNullOrBlank() -> Presence.SIDECAR
        else -> Presence.MISSING
    }
}
