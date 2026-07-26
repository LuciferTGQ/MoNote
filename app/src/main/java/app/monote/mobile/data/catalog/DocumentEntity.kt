package app.monote.mobile.data.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents", indices = [Index(value = ["relativePath"], unique = true)])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val relativePath: String,
    val title: String,
    val modifiedAt: Long,
    val size: Long,
    val sha256: String,
    val favorite: Boolean = false,
    val lastOpenedAt: Long? = null,
)
