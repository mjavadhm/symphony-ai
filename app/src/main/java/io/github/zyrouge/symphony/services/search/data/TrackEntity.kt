package io.github.zyrouge.symphony.services.search.data

import io.objectbox.annotation.Backlink
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType
import io.objectbox.relation.ToMany

@Entity
data class TrackEntity(
    @Id var id: Long = 0,
    var filePath: String? = null,
    var title: String? = null,
    var artist: String? = null,
    var durationSeconds: Int = 0,
    @HnswIndex(dimensions = 512, distanceType = VectorDistanceType.COSINE)
    var meanEmbedding: FloatArray? = null
) {
    @Backlink(to = "track")
    lateinit var chunks: ToMany<TrackChunkEntity>
}
