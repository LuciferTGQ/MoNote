package app.monote.mobile.data.catalog

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, DocumentFtsEntity::class, DocumentTagEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MoNoteDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
