package app.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Huntfolio record already imported (Requirement 59.3, 59.5). The [key] is the Huntfolio record
 * key; its presence makes a re-import skip the record, so importing the same file twice creates
 * nothing new (Property 78).
 */
@Entity(tableName = "import_record")
data class ImportRecordEntity(
    @PrimaryKey val key: String,
    @androidx.room.ColumnInfo(name = "imported_at") val importedAt: Long,
)
