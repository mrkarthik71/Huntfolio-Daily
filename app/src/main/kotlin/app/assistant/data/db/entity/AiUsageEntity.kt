package app.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cloud AI usage for one calendar month (Requirement 45, design: Cap_Governor). [month] is the key
 * `YYYY-MM` in the device zone. [spentPaise] is the running total reserved-or-recorded this month;
 * [attempts] counts every attempt, successful or not (Requirement 45.6). The cap itself lives in
 * settings, not here, so changing the cap never rewrites history.
 */
@Entity(tableName = "ai_usage")
data class AiUsageEntity(
    @PrimaryKey val month: String,
    val spentPaise: Long,
    val attempts: Int,
)
