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
    // نیت اصلی میکس به زبان آدمیزاد — بعداً ورودی LLM هم همینه
    @ColumnInfo(defaultValue = "") val description: String = "",
    // چند پرامپت، هر کدوم توی یه خط
    @ColumnInfo(defaultValue = "") val prompts: String = "",
)

/**
 * لیست پرامپتهای میکس.
 * اگه میکس قدیمی باشه و prompts خالی باشه، برمیگرده به همون prompt تکی —
 * یعنی میکسهای قبلی بدون هیچ کاری همچنان کار میکنن.
 */
fun CustomMix.promptList(): List<String> = when {
    prompts.isBlank() -> listOf(prompt).filter { it.isNotBlank() }
    else -> prompts.split("\n").map { it.trim() }.filter { it.isNotBlank() }
}
