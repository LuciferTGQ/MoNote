package app.monote.mobile.feature.importing

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCoordinatorTest {
    private val temporaryRoots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun importsSanitizedChineseMarkdownNameAndPreservesItsExtension() = runBlocking {
        val destination = temporaryDirectory()

        val result = coordinator().import(ByteSource("复习:计划.MD", "内容"), destination.toFile())

        assertEquals("复习_计划.MD", result.file.name)
        assertEquals("内容", result.file.readText(StandardCharsets.UTF_8))
        assertEquals("UTF-8", result.encoding)
    }

    @Test(expected = UnsupportedDocumentTypeException::class)
    fun rejectsNonMarkdownExtensions() {
        runBlocking {
            coordinator().import(ByteSource("notes.txt", "content"), temporaryDirectory().toFile())
        }
    }

    @Test(expected = UnsupportedDocumentTypeException::class)
    fun rejectsNamesWithoutAnExtension() {
        runBlocking {
            coordinator().import(ByteSource("notes", "content"), temporaryDirectory().toFile())
        }
    }

    @Test
    fun usesNextAvailableNameWithoutOverwritingOriginalContent() = runBlocking {
        val destination = temporaryDirectory()
        destination.resolve("复习.md").toFile().writeText("original", StandardCharsets.UTF_8)
        destination.resolve("复习 (2).md").toFile().writeText("second", StandardCharsets.UTF_8)

        val result = coordinator().import(ByteSource("复习.md", "new"), destination.toFile())

        assertEquals("复习 (3).md", result.file.name)
        assertEquals("original", destination.resolve("复习.md").toFile().readText(StandardCharsets.UTF_8))
        assertEquals("second", destination.resolve("复习 (2).md").toFile().readText(StandardCharsets.UTF_8))
        assertEquals("new", result.file.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun sourceFailureLeavesNoImportedOrTemporaryFiles() = runBlocking {
        val destination = temporaryDirectory()

        try {
            coordinator().import(ThrowingSource("broken.md"), destination.toFile())
            throw AssertionError("Expected source failure")
        } catch (_: IOException) {
        }

        assertTrue(Files.list(destination).use { !it.findAny().isPresent })
    }

    @Test
    fun createsMissingDestinationDirectory() = runBlocking {
        val destination = temporaryDirectory().resolve("new/library")

        val result = coordinator().import(ByteSource("note.markdown", "content"), destination.toFile())

        assertTrue(destination.toFile().isDirectory)
        assertEquals("note.markdown", result.file.name)
    }

    @Test(expected = ImportException::class)
    fun rejectsDestinationOccupiedByAFile() {
        runBlocking {
            val destination = temporaryDirectory().resolve("not-a-directory")
            Files.write(destination, "file".toByteArray(StandardCharsets.UTF_8))

            coordinator().import(ByteSource("note.md", "content"), destination.toFile())
        }
    }

    @Test
    fun concurrentSameNameImportsClaimDistinctFilesAndLeaveNoTemporaryFiles() = runBlocking {
        val destination = temporaryDirectory()
        val barrier = CyclicBarrier(2)

        val results = awaitAll(
            async(Dispatchers.Default) { coordinator().import(BarrierSource("note.md", "first", barrier), destination.toFile()) },
            async(Dispatchers.Default) { coordinator().import(BarrierSource("note.md", "second", barrier), destination.toFile()) },
        )

        assertEquals(setOf("note.md", "note (2).md"), results.map { it.file.name }.toSet())
        assertEquals(setOf("first", "second"), results.map { it.file.readText(StandardCharsets.UTF_8) }.toSet())
        Files.list(destination).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().contains("monote-import") || it.fileName.toString().endsWith(".monote-tmp") })
        }
    }

    @Test
    fun concurrentReservationsKeepExistingContentAndClaimDistinctNames() = runBlocking {
        val destination = temporaryDirectory()
        destination.resolve("note.md").toFile().writeText("original", StandardCharsets.UTF_8)
        val barrier = CyclicBarrier(2)
        val operations = BarrierReservationOperations(barrier)

        val results = awaitAll(
            async(Dispatchers.Default) { coordinator(operations).import(ByteSource("note.md", "first"), destination.toFile()) },
            async(Dispatchers.Default) { coordinator(operations).import(ByteSource("note.md", "second"), destination.toFile()) },
        )

        assertEquals("original", destination.resolve("note.md").toFile().readText(StandardCharsets.UTF_8))
        assertEquals(setOf("note (2).md", "note (3).md"), results.map { it.file.name }.toSet())
        assertEquals(setOf("first", "second"), results.map { it.file.readText(StandardCharsets.UTF_8) }.toSet())
    }

    @Test
    fun failedMoveRemovesItsReservationAndStagingFiles() = runBlocking {
        val destination = temporaryDirectory()
        val operations = ReservationOperations(failMove = true)

        try {
            coordinator(operations).import(ByteSource("note.md", "content"), destination.toFile())
            throw AssertionError("Expected move failure")
        } catch (actual: IOException) {
            assertEquals(operations.moveFailure.message, actual.message)
        }

        assertFalse(Files.exists(destination.resolve("note.md")))
        assertNoTemporaryFiles(destination)
    }

    @Test
    fun cancellationDuringReadLeavesNoFinalReservationOrTemporaryFiles() = runBlocking {
        val destination = temporaryDirectory()
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val deferred = async(Dispatchers.Default) {
            coordinator().import(BlockingSource("note.md", enteredRead, releaseRead), destination.toFile())
        }

        assertTrue(enteredRead.await(5, TimeUnit.SECONDS))
        deferred.cancel()
        releaseRead.countDown()
        try {
            deferred.await()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertFalse(Files.exists(destination.resolve("note.md")))
        assertNoTemporaryFiles(destination)
    }

    @Test
    fun returnsCommittedTargetWhenStagingCleanupFailsAfterMove() = runBlocking {
        val destination = temporaryDirectory()
        val operations = CleanupFailingOperations()

        val result = coordinator(operations).import(ByteSource("note.md", "content"), destination.toFile())

        assertEquals(destination.resolve("note.md").toFile(), result.file)
        assertEquals("content", result.file.readText(StandardCharsets.UTF_8))
        assertTrue(result.warnings.contains("Temporary cleanup failed"))
        assertFalse(Files.exists(destination.resolve("note (2).md")))
    }

    @Test
    fun preservesMoveFailureAndSuppressesCleanupFailure() = runBlocking {
        val destination = temporaryDirectory()
        val operations = CleanupFailingOperations(failMove = true)

        try {
            coordinator(operations).import(ByteSource("note.md", "content"), destination.toFile())
            throw AssertionError("Expected move failure")
        } catch (actual: IOException) {
            assertEquals(operations.moveFailure.message, actual.message)
            assertEquals(operations.moveFailure.message, actual.message)
            assertTrue(actual.suppressed.contains(operations.cleanupFailure))
        }
    }

    private fun coordinator() = ImportCoordinator()

    private fun coordinator(fileOperations: ImportFileOperations) = ImportCoordinator(fileOperations = fileOperations)

    private fun assertNoTemporaryFiles(destination: Path) {
        Files.list(destination).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().contains("monote-import") || it.fileName.toString().endsWith(".monote-tmp") })
        }
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-import-").also(temporaryRoots::add)

    private class ByteSource(
        override val displayName: String,
        private val text: String,
    ) : DocumentSource {
        override val mimeType: String? = "text/markdown"
        override suspend fun open(): InputStream = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
    }

    private class ThrowingSource(override val displayName: String) : DocumentSource {
        override val mimeType: String? = "text/markdown"
        override suspend fun open(): InputStream = throw IOException("read failed")
    }

    private class BarrierSource(
        override val displayName: String,
        private val text: String,
        private val barrier: CyclicBarrier,
    ) : DocumentSource {
        override val mimeType: String? = "text/markdown"
        override suspend fun open(): InputStream {
            barrier.await()
            return ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private class BlockingSource(
        override val displayName: String,
        private val enteredRead: CountDownLatch,
        private val releaseRead: CountDownLatch,
    ) : DocumentSource {
        override val mimeType: String? = "text/markdown"
        override suspend fun open(): InputStream = object : InputStream() {
            private var emitted = false

            override fun read(): Int {
                if (emitted) return -1
                enteredRead.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                emitted = true
                return 'x'.code
            }
        }
    }

    private class CleanupFailingOperations(private val failMove: Boolean = false) : ImportFileOperations {
        val moveFailure = IOException("move failed")
        val cleanupFailure = IOException("cleanup failed")
        private lateinit var staging: Path

        override fun createTempFile(directory: Path, prefix: String, suffix: String): Path {
            staging = Files.createTempFile(directory, prefix, suffix)
            return staging
        }

        override fun createFile(path: Path): Path = Files.createFile(path)

        override fun moveReplacing(source: Path, target: Path): Path {
            if (failMove) throw moveFailure
            return Files.move(source, target, REPLACE_EXISTING)
        }

        override fun deleteIfExists(path: Path): Boolean {
            if (path == staging) throw cleanupFailure
            return Files.deleteIfExists(path)
        }
    }

    private open class ReservationOperations(private val failMove: Boolean = false) : ImportFileOperations {
        val moveFailure = IOException("move failed")

        override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
            Files.createTempFile(directory, prefix, suffix)

        override fun createFile(path: Path): Path = Files.createFile(path)

        override fun moveReplacing(source: Path, target: Path): Path {
            if (failMove) throw moveFailure
            return Files.move(source, target, REPLACE_EXISTING)
        }

        override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)
    }

    private class BarrierReservationOperations(private val barrier: CyclicBarrier) : ReservationOperations() {
        override fun createFile(path: Path): Path {
            if (path.fileName.toString() == "note (2).md") {
                barrier.await(5, TimeUnit.SECONDS)
            }
            return super.createFile(path)
        }
    }
}
