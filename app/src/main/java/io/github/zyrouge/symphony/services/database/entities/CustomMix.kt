package io.github.zyrouge.symphony.services.database.entities

import androidx.room.ColumnInfo
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
    // The mix's intent in plain language — this is also what gets fed to the LLM later
    @ColumnInfo(defaultValue = "") val description: String = "",
    // Multiple prompts, one per line
    @ColumnInfo(defaultValue = "") val prompts: String = "",
)

/**
 * The mix's list of prompts.
 * For older mixes where `prompts` is empty, this falls back to the single `prompt`,
 * so existing mixes keep working without any migration.
 */
fun CustomMix.promptList(): List<String> = when {
    prompts.isBlank() -> listOf(prompt).filter { it.isNotBlank() }
    else -> prompts.split("\n").map { it.trim() }.filter { it.isNotBlank() }
}
