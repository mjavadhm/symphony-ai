package io.github.zyrouge.symphony.services.search.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType
import io.objectbox.relation.ToOne

@Entity
data class TrackChunkEntity(
    @Id var id: Long = 0,
    var offsetSeconds: Int = 0,
    @HnswIndex(dimensions = 512, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null
) {
    lateinit var track: ToOne<TrackEntity>
}
