package io.github.zyrouge.symphony.services.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mix_contexts")
data class MixContext(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "☀️",
    val startHour: Int,
    val endHour: Int,
    val enabled: Boolean = true,
)
