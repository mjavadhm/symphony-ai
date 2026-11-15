package io.github.zyrouge.symphony.services.spotizer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** All list mutations use the latest snapshot, including UI actions during a download. */
internal class SpotizerDownloadState {
    private val mutableItems = MutableStateFlow<List<SpotizerDownloadManager.Item>>(emptyList())
    val items = mutableItems.asStateFlow()

    fun enqueue(item: SpotizerDownloadManager.Item): Boolean {
        while (true) {
            val previous = mutableItems.value
            if (previous.any { it.track.id == item.track.id && it.isActive }) return false
            if (mutableItems.compareAndSet(previous, previous + item)) return true
        }
    }

    fun update(itemId: Long, transform: (SpotizerDownloadManager.Item) -> SpotizerDownloadManager.Item) {
        mutableItems.update { rows -> rows.map {
            if (it.id == itemId && it.phase != SpotizerDownloadManager.Phase.Cancelled) transform(it) else it
        } }
    }

    fun retry(itemId: Long): Boolean {
        while (true) {
            val previous = mutableItems.value
            val item = previous.firstOrNull { it.id == itemId } ?: return false
            if (item.isActive) return false
            val next = previous.map { if (it.id == itemId) it.copy(
                phase = SpotizerDownloadManager.Phase.Queued, downloadedBytes = 0L, totalBytes = 0L, error = null,
            ) else it }
            if (mutableItems.compareAndSet(previous, next)) return true
        }
    }

    fun clearFinished() { mutableItems.update { rows -> rows.filter { it.isActive } } }
}
