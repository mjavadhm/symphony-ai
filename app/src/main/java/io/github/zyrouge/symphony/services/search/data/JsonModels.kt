package io.github.zyrouge.symphony.services.search.data

import kotlinx.serialization.Serializable

@Serializable
data class TrackJson(
    val filename: String,
    val title: String,
    val duration: Int,
    val chunks: List<List<Float>>
)
