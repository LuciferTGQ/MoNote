package app.monote.mobile.feature.library

import app.monote.mobile.data.catalog.CatalogDao
import app.monote.mobile.data.catalog.DocumentEntity
import app.monote.mobile.data.catalog.DocumentFtsEntity
import app.monote.mobile.data.catalog.DocumentTagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class TestCatalogDao : CatalogDao {
    val documents = linkedMapOf<String, DocumentEntity>()
    val documentTags = linkedMapOf<String, MutableSet<String>>()
    val ftsEntries = linkedMapOf<String, DocumentFtsEntity>()
    var failMutations = false
    var mutationFailure: Throwable? = null
    var allFailure: Throwable? = null
    var tagsFailure: Throwable? = null
    var afterAllSnapshot: (suspend () -> Unit)? = null

    override fun observeAll(): Flow<List<DocumentEntity>> = flowOf(documents.values.toList())

    override suspend fun all(): List<DocumentEntity> {
        allFailure?.let { throw it }
        val snapshot = documents.values.toList()
        afterAllSnapshot?.invoke()
        return snapshot
    }

    override suspend fun getByPath(relativePath: String): DocumentEntity? =
        documents.values.firstOrNull { it.relativePath == relativePath }

    override suspend fun tags(documentId: String): List<DocumentTagEntity> =
        tagsFailure?.let { throw it }
            ?: documentTags[documentId].orEmpty().sorted().map { DocumentTagEntity(documentId, it) }

    override suspend fun search(query: String): List<DocumentEntity> = emptyList()

    override suspend fun insertDocument(document: DocumentEntity) {
        failMutationIfRequested()
        documents[document.id] = document
    }

    override suspend fun insertFts(entry: DocumentFtsEntity) {
        failMutationIfRequested()
        ftsEntries[entry.documentId] = entry
    }

    override suspend fun insertTags(tags: List<DocumentTagEntity>) {
        failMutationIfRequested()
        tags.forEach { documentTags.getOrPut(it.documentId, ::mutableSetOf).add(it.tag) }
    }

    override suspend fun deleteFts(documentId: String) {
        failMutationIfRequested()
        ftsEntries.remove(documentId)
    }

    override suspend fun deleteTags(documentId: String) {
        failMutationIfRequested()
        documentTags.remove(documentId)
    }

    override suspend fun deleteDocument(documentId: String) {
        failMutationIfRequested()
        documents.remove(documentId)
        documentTags.remove(documentId)
    }

    override suspend fun deleteAllDocuments() {
        failMutationIfRequested()
        documents.clear()
        documentTags.clear()
    }

    override suspend fun deleteAllFts() {
        failMutationIfRequested()
        ftsEntries.clear()
    }

    private fun failMutationIfRequested() {
        mutationFailure?.let { throw it }
        check(!failMutations) { "catalog mutation failed" }
    }
}
