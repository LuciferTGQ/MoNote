package app.monote.mobile.data.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates Room's searchable catalog with the small, recoverable metadata mirror. */
class CatalogRepository(
    private val dao: CatalogDao,
    private val mirror: CatalogMirror,
) {
    private val mutationMutex = Mutex()

    suspend fun all(): List<DocumentEntity> = dao.all()

    suspend fun getByPath(relativePath: String): DocumentEntity? = dao.getByPath(relativePath)

    suspend fun tags(documentId: String): Set<String> = dao.tags(documentId).mapTo(sortedSetOf()) { it.tag }

    suspend fun upsert(document: DocumentEntity, body: String, tags: Set<String>) = mutationMutex.withLock {
        upsertDuringScan(document, body, tags)
        refreshMirrorDuringScan()
    }

    suspend fun deleteMissing(presentIds: Set<String>) = mutationMutex.withLock {
        deleteMissingDuringScan(presentIds)
        refreshMirrorDuringScan()
    }

    internal suspend fun upsertDuringScan(document: DocumentEntity, body: String, tags: Set<String>) {
        dao.replaceIndex(document, body, tags)
    }

    internal suspend fun replaceIndexAtPathDuringScan(
        document: DocumentEntity,
        body: String,
        tags: Set<String>,
    ) {
        dao.replaceIndexAtPath(document, body, tags)
    }

    internal suspend fun deleteMissingDuringScan(presentIds: Set<String>) {
        if (presentIds.isEmpty()) dao.deleteAll() else dao.deleteMissing(presentIds)
    }

    internal suspend fun <T> withScanLock(block: suspend () -> T): T = mutationMutex.withLock { block() }

    suspend fun search(userQuery: String): List<DocumentEntity> {
        val terms = ftsQueryTokens(userQuery).distinct()
        if (terms.isEmpty()) return emptyList()
        var matches = dao.search(ftsLiteral(terms.first()))
        terms.drop(1).forEach { term ->
            val ids = dao.search(ftsLiteral(term)).mapTo(hashSetOf()) { it.id }
            matches = matches.filter { it.id in ids }
        }
        return matches
    }

    suspend fun restoreMirror(): CatalogSnapshot = mirror.read()

    suspend fun refreshMirror() = mutationMutex.withLock {
        refreshMirrorDuringScan()
    }

    internal suspend fun refreshMirrorDuringScan() {
        val snapshot = CatalogSnapshot(
            documents = dao.all().map { document ->
                CatalogDocument(document.id, document.relativePath, document.favorite, tags(document.id))
            },
        )
        mirror.write(snapshot)
    }

    private fun ftsLiteral(token: String): String = "\"${token.replace("\"", "\"\"")}\""
}
