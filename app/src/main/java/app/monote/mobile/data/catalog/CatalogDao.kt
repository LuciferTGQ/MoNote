package app.monote.mobile.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM documents ORDER BY COALESCE(lastOpenedAt, modifiedAt) DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY COALESCE(lastOpenedAt, modifiedAt) DESC")
    suspend fun all(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE relativePath = :relativePath LIMIT 1")
    suspend fun getByPath(relativePath: String): DocumentEntity?

    @Query("SELECT * FROM document_tags WHERE documentId = :documentId ORDER BY tag")
    suspend fun tags(documentId: String): List<DocumentTagEntity>

    @Query("SELECT d.* FROM document_fts f JOIN documents d ON d.id = f.documentId WHERE document_fts MATCH :query ORDER BY COALESCE(d.lastOpenedAt, d.modifiedAt) DESC")
    suspend fun search(query: String): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: DocumentFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<DocumentTagEntity>)

    @Query("DELETE FROM document_fts WHERE documentId = :documentId")
    suspend fun deleteFts(documentId: String)

    @Query("DELETE FROM document_tags WHERE documentId = :documentId")
    suspend fun deleteTags(documentId: String)

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()

    @Query("UPDATE documents SET favorite = :favorite WHERE id IN (:documentIds)")
    suspend fun setFavorite(documentIds: Set<String>, favorite: Boolean)

    @Query("DELETE FROM document_fts")
    suspend fun deleteAllFts()

    @Transaction
    suspend fun deleteIndexedDocument(documentId: String) {
        deleteFts(documentId)
        deleteTags(documentId)
        deleteDocument(documentId)
    }

    @Transaction
    suspend fun deleteMissing(presentIds: Set<String>) {
        all().forEach { document ->
            if (document.id !in presentIds) deleteIndexedDocument(document.id)
        }
    }

    @Transaction
    suspend fun deleteAll() {
        deleteAllFts()
        deleteAllDocuments()
    }

    @Transaction
    suspend fun replaceIndex(document: DocumentEntity, body: String, tags: Set<String>) {
        insertDocument(document)
        deleteFts(document.id)
        insertFts(DocumentFtsEntity(document.id, ftsSearchText(document.title), ftsSearchText(body)))
        deleteTags(document.id)
        insertTags(tags.sorted().map { DocumentTagEntity(document.id, it) })
    }

    @Transaction
    suspend fun replaceIndexAtPath(document: DocumentEntity, body: String, tags: Set<String>) {
        getByPath(document.relativePath)
            ?.takeIf { it.id != document.id }
            ?.let { deleteIndexedDocument(it.id) }
        replaceIndex(document, body, tags)
    }

    @Transaction
    suspend fun replaceTags(documentId: String, tags: Set<String>) {
        deleteTags(documentId)
        insertTags(tags.sorted().map { DocumentTagEntity(documentId, it) })
    }
}
