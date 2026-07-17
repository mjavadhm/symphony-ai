package io.github.zyrouge.symphony.services.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_flow")
data class TrackFlow(
    @PrimaryKey val songId: String,
    val headEnergy: Float,
    val headCentroid: Float,
    val headRolloff: Float,
    val headOnset: Float,
    val tailEnergy: Float,
    val tailCentroid: Float,
    val tailRolloff: Float,
    val tailOnset: Float,
    val analyzedAt: Long,
)
