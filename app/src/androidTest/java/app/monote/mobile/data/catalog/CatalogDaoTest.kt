package app.monote.mobile.data.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.monote.mobile.core.storage.DocumentFingerprint
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CatalogDaoTest {
    private lateinit var database: MoNoteDatabase
    private lateinit var dao: CatalogDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.catalogDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun searchMatchesChineseBodyOnlyForTheMatchingDocument() = runBlocking {
        dao.replaceIndex(document("one", "呼吸练习", 1L), "慢慢呼吸，保持专注", setOf("健康"))
        dao.replaceIndex(document("two", "购物", 2L), "牛奶和面包", emptySet())

        assertEquals(listOf("one"), dao.search("呼吸").map { it.id })
    }

    @Test
    fun replacementUpdatesFtsTagsSortingAndMissingDocuments() = runBlocking {
        dao.replaceIndex(document("one", "first", 1L), "obsolete", setOf("old"))
        dao.replaceIndex(document("two", "second", 20L), "fresh content", setOf("new", "inbox"))
        dao.replaceIndex(document("one", "first", 30L), "replacement body", setOf("now"))

        assertTrue(dao.search("obsolete").isEmpty())
        assertEquals(listOf("now"), dao.tags("one").map { it.tag })
        assertEquals(listOf("one", "two"), dao.all().map { it.id })
        dao.deleteMissing(setOf("one"))
        assertEquals(listOf("one"), dao.all().map { it.id })
    }

    @Test
    fun indexerFiltersSystemFilesReusesStableIdAndDeletesMissingDocuments() = runBlocking {
        val root = Files.createTempDirectory("monote-indexer-")
        try {
            Files.write(root.resolve("keep.MD"), "# 保留\n呼吸".toByteArray(StandardCharsets.UTF_8))
            Files.write(root.resolve("ignore.txt"), "ignore".toByteArray(StandardCharsets.UTF_8))
            Files.createDirectories(root.resolve("_MoNoteSystem"))
            Files.write(root.resolve("_MoNoteSystem/hidden.md"), "hidden".toByteArray(StandardCharsets.UTF_8))
            var reads = 0
            val repository = CatalogRepository(dao, CatalogMirror(root.resolve("_MoNoteSystem")))
            val indexer = LibraryIndexer(repository, readSnapshot = { file ->
                reads++
                IndexedDocumentReader().read(file)
            })

            indexer.scan(root.toFile())
            val first = dao.all().single()
            indexer.scan(root.toFile())
            assertEquals(1, reads)
            assertEquals(first.id, dao.all().single().id)
            Files.delete(root.resolve("keep.MD"))
            indexer.scan(root.toFile())
            assertTrue(dao.all().isEmpty())
            assertEquals(ScanStatus.COMPLETED, indexer.progress.value.status)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun repositoryRefreshesMirrorAndSearchesOneTwoAndThreeChineseCharacters() = runBlocking {
        val root = Files.createTempDirectory("monote-repository-")
        try {
            val mirror = CatalogMirror(root)
            val repository = CatalogRepository(dao, mirror)
            val document = document("stable", "呼吸作用", 1L).copy(favorite = true)
            repository.upsert(document, "呼吸作用 improves LatinSearch", setOf("健康"))
            assertEquals("stable", mirror.read().documents.single().id)
            assertEquals(setOf("健康"), mirror.read().documents.single().tags)
            assertEquals(listOf("stable"), repository.search("呼").map { it.id })
            assertEquals(listOf("stable"), repository.search("呼吸").map { it.id })
            assertEquals(listOf("stable"), repository.search("呼吸作").map { it.id })
            assertEquals(listOf("stable"), repository.search("LatinSearch").map { it.id })
            assertTrue(repository.search("OR").isEmpty())
            assertTrue(repository.search("foo OR").isEmpty())
            repository.deleteMissing(emptySet())
            assertTrue(mirror.read().documents.isEmpty())
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun incompleteDiscoveryRetainsExistingRecordsAndCancellationPropagates() = runBlocking {
        val root = Files.createTempDirectory("monote-incomplete-")
        try {
            val repository = CatalogRepository(dao, CatalogMirror(root.resolve("_MoNoteSystem")))
            repository.upsert(document("existing", "existing", 1L), "body", emptySet())
            val incomplete = LibraryIndexer(repository, discoveryOverride = { _, _ -> LibraryIndexer.DiscoveryResult(emptyList(), false) })
            incomplete.scan(root.toFile())
            assertEquals(listOf("existing"), dao.all().map { it.id })
            assertTrue(incomplete.progress.value.errors.any { it.contains("incomplete") })
            Files.write(root.resolve("cancel.md"), "cancel".toByteArray(StandardCharsets.UTF_8))
            val cancelling = LibraryIndexer(repository, readSnapshot = { throw CancellationException("stop") })
            try {
                cancelling.scan(root.toFile())
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
            }
            assertTrue(cancelling.progress.value.status != ScanStatus.COMPLETED)
            assertEquals(listOf("existing"), dao.all().map { it.id })
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun deletingDocumentsTransactionallyRemovesTheirFtsRows() = runBlocking {
        dao.replaceIndex(document("keep", "keep", 1L), "shared keep-token", emptySet())
        dao.replaceIndex(document("remove", "remove", 2L), "shared removed-token", emptySet())

        dao.deleteMissing(setOf("keep"))

        assertEquals(listOf("keep"), dao.search("shared").map { it.id })
        assertTrue(dao.search("removed").isEmpty())
        dao.deleteAll()
        assertTrue(dao.search("keep").isEmpty())
    }

    @Test
    fun deleteMissingHandlesMoreThanOneThousandPresentIdsWithoutSqlBindingLimit() = runBlocking {
        val ids = (0..1_005).mapTo(linkedSetOf()) { "id-$it" }
        ids.forEachIndexed { index, id ->
            dao.replaceIndex(document(id, id, index.toLong()), "bodyToken$index", emptySet())
        }

        dao.deleteMissing(ids)

        assertEquals(ids.size, dao.all().size)
        val retained = ids.take(1_000).toSet()
        dao.deleteMissing(retained)
        assertEquals(retained, dao.all().mapTo(hashSetOf()) { it.id })
        assertTrue(dao.search("bodyToken1005").isEmpty())
    }

    @Test
    fun repositorySearchTreatsMixedLanguagePunctuationAsAnd() = runBlocking {
        val root = Files.createTempDirectory("monote-mixed-search-")
        try {
            val repository = CatalogRepository(dao, CatalogMirror(root))
            repository.upsert(document("both", "呼吸 LatinSearch", 1L), "呼吸，LatinSearch", emptySet())
            repository.upsert(document("han", "呼吸", 2L), "只有呼吸", emptySet())

            assertEquals(listOf("both"), repository.search("呼吸，LatinSearch").map { it.id })
            assertTrue(repository.search("""OR title: foo* "quoted"""").isEmpty())
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun repositorySearchesLatinAndHanRunsWithoutASeparator() = runBlocking {
        val root = Files.createTempDirectory("monote-attached-search-")
        try {
            val repository = CatalogRepository(dao, CatalogMirror(root))
            repository.upsert(document("android", "Android开发", 1L), "Android开发入门", emptySet())
            repository.upsert(document("gpt", "GPT教程", 2L), "GPT教程与复习", emptySet())

            assertEquals(listOf("android"), repository.search("Android").map { it.id })
            assertEquals(listOf("android"), repository.search("Android开发").map { it.id })
            assertEquals(listOf("gpt"), repository.search("GPT").map { it.id })
            assertEquals(listOf("gpt"), repository.search("GPT教程").map { it.id })
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun indexerDecodesUtf8AndUtf16BomsAndHashesTheirRawBytes() = runBlocking {
        val root = Files.createTempDirectory("monote-encodings-")
        try {
            val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# UTF8 标题".toByteArray(StandardCharsets.UTF_8)
            val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "# UTF16 标题".toByteArray(Charsets.UTF_16LE)
            Files.write(root.resolve("utf8.md"), utf8)
            Files.write(root.resolve("utf16.md"), utf16)
            val repository = CatalogRepository(dao, CatalogMirror(root.resolve("_MoNoteSystem")))

            LibraryIndexer(repository).scan(root.toFile())

            assertEquals("UTF8 标题", dao.getByPath("utf8.md")?.title)
            assertEquals(DocumentFingerprint.sha256(utf8), dao.getByPath("utf8.md")?.sha256)
            assertEquals("UTF16 标题", dao.getByPath("utf16.md")?.title)
            assertEquals(DocumentFingerprint.sha256(utf16), dao.getByPath("utf16.md")?.sha256)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun oversizedChangedFileReportsErrorWithoutDeletingItsPreviousRecord() = runBlocking {
        val root = Files.createTempDirectory("monote-oversized-")
        try {
            val file = root.resolve("keep.md")
            Files.write(file, "# previous".toByteArray(StandardCharsets.UTF_8))
            val repository = CatalogRepository(dao, CatalogMirror(root.resolve("_MoNoteSystem")))
            val indexer = LibraryIndexer(repository)
            indexer.scan(root.toFile())
            val previous = dao.getByPath("keep.md")!!
            Files.newOutputStream(file).use { output ->
                val block = ByteArray(1024 * 1024)
                repeat(16) { output.write(block) }
                output.write(0)
            }

            indexer.scan(root.toFile())

            assertEquals(previous, dao.getByPath("keep.md"))
            assertTrue(indexer.progress.value.errors.any { it.contains("exceeds") })
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun emptyRoomRestoresStableMetadataFromMirrorDuringScan() = runBlocking {
        val root = Files.createTempDirectory("monote-mirror-restore-")
        try {
            Files.write(root.resolve("restored.md"), "# Restored".toByteArray(StandardCharsets.UTF_8))
            val mirror = CatalogMirror(root.resolve("_MoNoteSystem"))
            mirror.write(
                CatalogSnapshot(
                    documents = listOf(CatalogDocument("stable-id", "restored.md", true, setOf("archive", "复习"))),
                ),
            )
            val repository = CatalogRepository(dao, mirror)

            LibraryIndexer(repository).scan(root.toFile())

            val restored = dao.getByPath("restored.md")!!
            assertEquals("stable-id", restored.id)
            assertTrue(restored.favorite)
            assertEquals(setOf("archive", "复习"), repository.tags(restored.id))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun document(id: String, title: String, modifiedAt: Long) = DocumentEntity(
        id = id,
        relativePath = "$id.md",
        title = title,
        modifiedAt = modifiedAt,
        size = 1,
        sha256 = id,
    )
}
