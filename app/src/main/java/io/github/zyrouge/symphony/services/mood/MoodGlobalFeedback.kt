package io.github.zyrouge.symphony.services.mood

import io.github.zyrouge.symphony.services.database.entities.MixFeedback

object MoodGlobalFeedback {
    fun merge(ordinary: List<MixFeedback>, mood: List<MixFeedback>): List<MixFeedback> =
        (ordinary + mood).groupBy { it.songId }.values.map { it.maxBy { row -> row.updatedAt } }

    fun overridesHistory(feedback: List<MixFeedback>, songId: String, title: String, artist: String) =
        feedback.any { it.songId == songId || (it.title == title && it.artist == artist) }
}
