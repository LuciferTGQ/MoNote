package app.monote.mobile.data.catalog

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun mixedHanLatinAndPunctuationAreSeparateAndTerms() = runBlocking {
        val both = document("both")
        val hanOnly = document("han-only")
        val dao = SearchRecordingDao(
            mapOf(
                "\"呼吸\"" to listOf(both, hanOnly),
                "\"LatinSearch\"" to listOf(both),
            ),
        )
        val repository = CatalogRepository(dao, CatalogMirror(temporaryDirectory()))

        assertEquals(listOf("both"), repository.search("呼吸，LatinSearch").map { it.id })
        assertEquals(listOf("\"呼吸\"", "\"LatinSearch\""), dao.queries)
    }

    @Test
    fun ftsOperatorsAndPunctuationAreAlwaysQueriedAsLiteralTokens() = runBlocking {
        val dao = SearchRecordingDao(emptyMap())
        val repository = CatalogRepository(dao, CatalogMirror(temporaryDirectory()))

        repository.search("""OR title: foo* "quoted"""")

        assertEquals(listOf("\"OR\"", "\"title\"", "\"foo\"", "\"quoted\""), dao.queries)
    }

    @Test
    fun indexTextContainsEveryMixedLanguageQueryTokenAsASeparateTerm() {
        listOf("Android开发", "GPT教程", "呼", "呼吸", "呼吸作").forEach { value ->
            val indexedTerms = ftsSearchText(value).split(Regex("\\s+")).toSet()

            assertTrue("$value indexed as $indexedTerms", indexedTerms.containsAll(ftsQueryTokens(value)))
        }
    }

    @Test
    fun fullScanLockSerializesConcurrentScansOnTheSameRepository() = runBlocking {
        val repository = CatalogRepository(SearchRecordingDao(emptyMap()), CatalogMirror(temporaryDirectory()))
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = launch {
            repository.withScanLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            repository.withScanLock { secondEntered.complete(Unit) }
        }

        delay(100)
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }

    private fun document(id: String) = DocumentEntity(id, "$id.md", id, 1L, 1L, id)

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-repository-").also(roots::add)

    private class SearchRecordingDao(private val results: Map<String, List<DocumentEntity>>) : CatalogDao {
        val queries = mutableListOf<String>()

        override fun observeAll(): Flow<List<DocumentEntity>> = flowOf(emptyList())
        override suspend fun all(): List<DocumentEntity> = emptyList()
        override suspend fun getByPath(relativePath: String): DocumentEntity? = null
        override suspend fun tags(documentId: String): List<DocumentTagEntity> = emptyList()
        override suspend fun search(query: String): List<DocumentEntity> {
            queries += query
            return results[query].orEmpty()
        }
        override suspend fun insertDocument(document: DocumentEntity) = Unit
        override suspend fun insertFts(entry: DocumentFtsEntity) = Unit
        override suspend fun insertTags(tags: List<DocumentTagEntity>) = Unit
        override suspend fun deleteFts(documentId: String) = Unit
        override suspend fun deleteTags(documentId: String) = Unit
        override suspend fun deleteDocument(documentId: String) = Unit
        override suspend fun deleteAllDocuments() = Unit
        override suspend fun deleteAllFts() = Unit
    }
}
