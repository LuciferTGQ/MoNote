package app.monote.mobile.data.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tag"],
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class DocumentTagEntity(val documentId: String, val tag: String)
