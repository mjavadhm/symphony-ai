package io.github.zyrouge.symphony.services.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_mixes")
data class CustomMix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val prompt: String,
    val icon: String = "🎵",
    val isBuiltIn: Boolean = false,
    val trackCount: Int = 25,
    val sortOrder: Int = 0,
)
