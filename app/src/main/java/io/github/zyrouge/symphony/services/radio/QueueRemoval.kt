package io.github.zyrouge.symphony.services.radio

/** Removes selected queue occurrences while keeping the unshuffled order in sync. */
internal object QueueRemoval {
    data class Result(
        val original: List<String>,
        val current: List<String>,
        val currentIndex: Int,
        val currentRemoved: Boolean,
    )

    fun remove(original: List<String>, current: List<String>, currentIndex: Int, indices: List<Int>): Result {
        val selected = indices.distinct().filter { it in current.indices }.sortedDescending()
        val remainingOriginal = original.toMutableList()
        val remainingCurrent = current.toMutableList()
        for (index in selected) {
            // An ID may occur more than once: remove one occurrence, not every copy.
            remainingOriginal.remove(remainingCurrent.removeAt(index))
        }
        val nextIndex = when {
            remainingCurrent.isEmpty() -> -1
            else -> (currentIndex - selected.count { it < currentIndex }).coerceAtMost(remainingCurrent.lastIndex)
        }
        return Result(remainingOriginal, remainingCurrent, nextIndex, currentIndex in selected)
    }
}
