package app.monote.mobile.feature.importing

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderImportCoordinatorTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun nestedMarkdownAndAssetsAreCommittedWithoutFlattening() = runBlocking {
        val destination = temporaryDirectory()
        val source = FakeTreeSource(
            "课程",
            mapOf(
                null to listOf(directory("chapter"), file("cover", "cover.png", "image")),
                "chapter" to listOf(file("note", "note.md", "# note")),
            ),
        )

        val imported = FolderImportCoordinator().import(source, destination.toFile())

        assertEquals("# note", imported.directory.resolve("chapter/note.md").readText())
        assertEquals("image", imported.directory.resolve("cover.png").readText())
        assertEquals(imported.directory.resolve("chapter/note.md"), imported.firstMarkdown)
    }

    @Test
    fun existingFolderIsNeverOverwritten() = runBlocking {
        val destination = temporaryDirectory()
        val existing = destination.resolve("course").also(Files::createDirectory)
        Files.write(existing.resolve("existing.md"), "keep".toByteArray())
        val source = FakeTreeSource("course", mapOf(null to listOf(file("note", "note.md", "content"))))

        val imported = FolderImportCoordinator().import(source, destination.toFile())

        assertEquals("course (2)", imported.directory.name)
        assertEquals("keep", String(Files.readAllBytes(existing.resolve("existing.md"))))
        assertTrue(imported.directory.resolve("note.md").isFile)
    }

    @Test
    fun enumerationStopsAtTheConfiguredLimitAndCleansStaging() = runBlocking {
        val destination = temporaryDirectory()
        val children = (1..20).map { file("$it", "$it.md", "$it") }
        val source = FakeTreeSource("many", mapOf(null to children))

        val failure = runCatching {
            FolderImportCoordinator(FolderImportLimits(maxEntries = 3)).import(source, destination.toFile())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(3, source.visited)
        assertTrue(destination.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationStopsEnumerationAndCleansStaging() = runBlocking {
        val destination = temporaryDirectory()
        val source = FakeTreeSource(
            "cancelled",
            mapOf(null to listOf(file("one", "one.md", "one"), file("two", "two.md", "two"))),
            cancelAfter = 1,
        )

        val failure = runCatching { FolderImportCoordinator().import(source, destination.toFile()) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, source.visited)
        assertTrue(destination.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stagingLikeChildNameDoesNotCorruptFirstMarkdownPath() = runBlocking {
        val destination = temporaryDirectory()
        val source = FakeTreeSource(
            "course",
            mapOf(
                null to listOf(directory("nested", ".monote-folder-import-child")),
                "nested" to listOf(file("note", "note.md", "content")),
            ),
        )

        val imported = FolderImportCoordinator().import(source, destination.toFile())

        assertTrue(imported.firstMarkdown.isFile)
        assertEquals(imported.directory.resolve(".monote-folder-import-child/note.md"), imported.firstMarkdown)
    }

    @Test
    fun systemDirectoryNameIsRejectedWithoutLeavingPartialFiles() = runBlocking {
        val destination = temporaryDirectory()
        val source = FakeTreeSource("course", mapOf(null to listOf(directory("system", "_MoNoteSystem"))))

        assertTrue(runCatching { FolderImportCoordinator().import(source, destination.toFile()) }.isFailure)
        assertTrue(destination.toFile().listFiles().orEmpty().isEmpty())
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-folder-import-").also(roots::add)

    private fun directory(id: String, name: String = id) = TreeDocument(id, name, true)
    private fun file(id: String, name: String, content: String) =
        TreeDocument(id, name, false, size = content.toByteArray().size.toLong())

    private class FakeTreeSource(
        override val displayName: String,
        private val children: Map<String?, List<TreeDocument>>,
        private val cancelAfter: Int? = null,
    ) : TreeDocumentSource {
        var visited = 0

        override suspend fun visitChildren(
            parentId: String?,
            limit: Int,
            visitor: suspend (TreeDocument) -> Unit,
        ): Boolean {
            val available = children[parentId].orEmpty()
            available.take(limit).forEach { document ->
                currentCoroutineContext().ensureActive()
                if (cancelAfter != null && visited >= cancelAfter) throw CancellationException("cancelled")
                visitor(document)
                visited += 1
            }
            return available.size > limit
        }

        override suspend fun open(document: TreeDocument): InputStream =
            ByteArrayInputStream(document.id.let { id ->
                when (id) {
                    "note" -> if (document.displayName == "note.md") "# note" else "content"
                    "cover" -> "image"
                    else -> id
                }
            }.toByteArray())
    }
}
