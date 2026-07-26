package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.LibraryPaths
import app.monote.mobile.data.catalog.CatalogMirror
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.DocumentEntity
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class LibraryServiceTest {
    private val temporaryRoots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun movingIntoExistingPathDoesNotOverwrite() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("source.md").apply { writeText("source") }
        val destination = environment.paths.root.resolve("destination.md").apply { writeText("destination") }

        val result = environment.service.move(source, destination)

        assertTrue(result is LibraryResult.Conflict)
        assertTrue(source.exists())
        assertEquals("source", source.readText())
        assertEquals("destination", destination.readText())
    }

    @Test
    fun renameMovesTheCatalogRecordWithoutChangingItsStableIdOrTags() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("old.md").apply { writeText("# Title\nBody") }
        environment.catalog.upsert(
            DocumentEntity("stable-id", "old.md", "Title", source.lastModified(), source.length(), "hash", favorite = true),
            source.readText(),
            setOf("study"),
        )

        val result = environment.service.rename(source, "new.md")

        assertEquals(environment.paths.root.resolve("new.md"), (result as LibraryResult.Success).file)
        assertFalse(source.exists())
        assertEquals("stable-id", environment.catalog.getByPath("new.md")?.id)
        assertTrue(environment.catalog.getByPath("new.md")?.favorite == true)
        assertEquals(setOf("study"), environment.catalog.tags("stable-id"))
    }

    @Test
    fun renameMarkdownToNonMarkdownFailsBeforeFilesystemOrCatalogMutation() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("old.md").apply { writeText("body") }
        environment.catalog.upsert(
            DocumentEntity("stable-id", "old.md", "old", source.lastModified(), source.length(), "hash"),
            source.readText(),
            emptySet(),
        )
        val destination = environment.paths.root.resolve("old.txt")

        val result = environment.service.rename(source, "old.txt")

        assertTrue(result is LibraryResult.Failure)
        assertTrue(source.exists())
        assertFalse(destination.exists())
        assertEquals("stable-id", environment.catalog.getByPath("old.md")?.id)
        assertEquals(null, environment.catalog.getByPath("old.txt"))
        assertEquals(0, environment.rescanRequests)
    }

    @Test
    fun moveMarkdownToNonMarkdownFailsBeforeFilesystemOrCatalogMutation() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("source.md").apply { writeText("body") }
        val folder = environment.paths.root.resolve("folder").apply { mkdir() }
        val destination = folder.resolve("source.txt")
        environment.catalog.upsert(
            DocumentEntity("stable-id", "source.md", "source", source.lastModified(), source.length(), "hash"),
            source.readText(),
            emptySet(),
        )

        val result = environment.service.move(source, destination)

        assertTrue(result is LibraryResult.Failure)
        assertTrue(source.exists())
        assertFalse(destination.exists())
        assertEquals("stable-id", environment.catalog.getByPath("source.md")?.id)
        assertEquals(null, environment.catalog.getByPath("folder/source.txt"))
        assertEquals(0, environment.rescanRequests)
    }

    @Test
    fun createOperationsRefuseExistingTargets() = runBlocking {
        val environment = environment()
        val existingFolder = environment.paths.root.resolve("folder").apply { mkdir() }
        val existingNote = environment.paths.root.resolve("note.md").apply { writeText("keep") }

        val folderResult = environment.service.createFolder(environment.paths.root, "folder")
        val noteResult = environment.service.createMarkdown(environment.paths.root, "note")

        assertTrue("Unexpected folder result: $folderResult", folderResult is LibraryResult.Conflict)
        assertTrue("Unexpected note result: $noteResult", noteResult is LibraryResult.Conflict)
        assertTrue(existingFolder.isDirectory)
        assertEquals("keep", existingNote.readText())
    }

    @Test
    fun createMarkdownAddsExtensionAndCatalogsTheEmptyDocument() = runBlocking {
        val environment = environment()

        val result = environment.service.createMarkdown(environment.paths.root, "revision")

        val file = result.requireSuccess().file
        assertEquals("revision.md", file.name)
        assertEquals("", file.readText())
        assertEquals("revision", environment.catalog.getByPath("revision.md")?.title)
    }

    @Test
    fun rejectsSourcesAndDestinationsOutsideTheLibrary() = runBlocking {
        val environment = environment()
        val outside = temporaryDirectory().resolve("outside.md").toFile().apply { writeText("outside") }
        val inside = environment.paths.root.resolve("inside.md").apply { writeText("inside") }

        val sourceResult = environment.service.move(outside, environment.paths.root.resolve("copy.md"))
        val destinationResult = environment.service.move(inside, outside)

        assertTrue(sourceResult is LibraryResult.Failure)
        assertTrue(destinationResult is LibraryResult.Failure)
        assertTrue(outside.exists())
        assertTrue(inside.exists())
    }

    @Test
    fun rejectsAnExistingSymbolicLinkEvenWhenItPointsInsideTheLibrary() = runBlocking {
        val environment = environment()
        val real = environment.paths.root.resolve("real.md").toPath()
        Files.write(real, "content".toByteArray(StandardCharsets.UTF_8))
        val link = environment.paths.root.resolve("link.md").toPath()
        try {
            Files.createSymbolicLink(link, real)
        } catch (error: IOException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        }

        val result = environment.service.rename(link.toFile(), "renamed.md")

        assertTrue(result is LibraryResult.Failure)
        assertTrue(Files.exists(real))
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun catalogFailureKeepsCreatedContentAndSchedulesRescan() = runBlocking {
        val environment = environment()
        environment.dao.failMutations = true

        val result = environment.service.createMarkdown(environment.paths.root, "kept")

        val success = result.requireSuccess()
        assertTrue(success.file.exists())
        assertFalse(success.catalogSynchronized)
        assertEquals(1, environment.rescanRequests)
    }

    @Test
    fun cancellationDuringCatalogUpdateKeepsCreatedContentAndSchedulesRescan() = runBlocking {
        val environment = environment()
        environment.dao.mutationFailure = CancellationException("cancel catalog")

        try {
            environment.service.createMarkdown(environment.paths.root, "kept-after-cancel")
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertTrue(environment.paths.root.resolve("kept-after-cancel.md").exists())
        assertEquals(1, environment.rescanRequests)
    }

    @Test
    fun directoryMoveReadsAndRebuildsCatalogUnderOneLockWithoutOverwritingConcurrentMetadata() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("folder").apply { mkdirs() }
        val note = source.resolve("note.md").apply { writeText("body") }
        environment.catalog.upsert(
            DocumentEntity("stable-id", "folder/note.md", "note", note.lastModified(), note.length(), "hash"),
            note.readText(),
            setOf("old"),
        )
        val lockEntered = CompletableDeferred<Unit>()
        val mutate = CompletableDeferred<Unit>()
        val mutationFinished = CompletableDeferred<Unit>()
        val snapshotCaptured = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        environment.dao.afterAllSnapshot = {
            snapshotCaptured.complete(Unit)
            releaseSnapshot.await()
        }
        val metadataMutation = launch {
            environment.catalog.withScanLock {
                lockEntered.complete(Unit)
                mutate.await()
                environment.catalog.upsertDuringScan(
                    requireNotNull(environment.catalog.getByPath("folder/note.md")).copy(favorite = true),
                    note.readText(),
                    setOf("new"),
                )
                mutationFinished.complete(Unit)
            }
        }
        lockEntered.await()

        val move = async {
            environment.service.move(source, environment.paths.root.resolve("moved"))
        }
        val readOutsideLock = withTimeoutOrNull(200) {
            snapshotCaptured.await()
            true
        } ?: false
        mutate.complete(Unit)
        mutationFinished.await()
        releaseSnapshot.complete(Unit)

        assertTrue(move.await() is LibraryResult.Success)
        metadataMutation.join()
        assertFalse("Catalog was read before acquiring the mutation lock", readOutsideLock)
        val moved = requireNotNull(environment.catalog.getByPath("moved/note.md"))
        assertEquals("stable-id", moved.id)
        assertTrue(moved.favorite)
        assertEquals(setOf("new"), environment.catalog.tags(moved.id))
        assertEquals(null, environment.catalog.getByPath("folder/note.md"))
    }

    private fun environment(): TestEnvironment {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val dao = TestCatalogDao()
        val catalog = CatalogRepository(dao, CatalogMirror(paths.system.toPath()))
        var rescanRequests = 0
        val service = LibraryService(paths, catalog) { rescanRequests++ }
        return TestEnvironment(paths, dao, catalog, service) { rescanRequests }
    }

    private fun temporaryDirectory(): Path =
        Files.createTempDirectory("monote-library-service-").also(temporaryRoots::add)

    private fun LibraryResult.requireSuccess(): LibraryResult.Success =
        this as? LibraryResult.Success ?: throw AssertionError("Expected success, got $this")

    private class TestEnvironment(
        val paths: app.monote.mobile.core.storage.LibraryDirectories,
        val dao: TestCatalogDao,
        val catalog: CatalogRepository,
        val service: LibraryService,
        private val rescanCount: () -> Int,
    ) {
        val rescanRequests: Int get() = rescanCount()
    }
}
