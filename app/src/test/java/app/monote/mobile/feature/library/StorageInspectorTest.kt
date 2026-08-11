package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.LibraryPaths
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class StorageInspectorTest {
    private val temporaryRoots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun storageBreakdownSeparatesDocumentsTrashRecoveryAndCacheWithoutCountingSystemTwice() {
        val temporary = temporaryDirectory()
        val paths = LibraryPaths(temporary.resolve("library")).ensureCreated()
        val cache = temporary.resolve("cache").toFile().apply { mkdirs() }
        paths.root.resolve("note.md").writeBytes(ByteArray(12))
        paths.system.resolve("catalog.json").writeBytes(ByteArray(100))
        paths.backups.resolve("backup.md").writeBytes(ByteArray(8))
        paths.trash.resolve("entry.bin").writeBytes(ByteArray(4))
        paths.recovery.resolve("draft.md").writeBytes(ByteArray(3))
        cache.resolve("render.bin").writeBytes(ByteArray(2))

        val result = StorageInspector(paths, cache).measure()

        assertEquals(12L, result.documentsBytes)
        assertEquals(4L, result.trashBytes)
        assertEquals(3L, result.recoveryBytes)
        assertEquals(2L, result.cacheBytes)
    }

    @Test
    fun symbolicLinksAreNotFollowedOrCounted() {
        val temporary = temporaryDirectory()
        val paths = LibraryPaths(temporary.resolve("library")).ensureCreated()
        val cache = temporary.resolve("cache").toFile().apply { mkdirs() }
        val outside = temporary.resolve("outside.bin")
        Files.write(outside, ByteArray(50))
        val link = paths.root.toPath().resolve("linked.bin")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (error: IOException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        }

        assertEquals(0L, StorageInspector(paths, cache).measure().documentsBytes)
    }

    @Test
    fun traversalItemAndRootFailuresBecomeWarningsWithoutDiscardingOtherCategorySizes() {
        val temporary = temporaryDirectory()
        val paths = LibraryPaths(temporary.resolve("library")).ensureCreated()
        val cache = temporary.resolve("cache").toFile().apply { mkdirs() }
        val reader = StorageTreeReader { directory, _, _, warning ->
            when (directory.toAbsolutePath().normalize()) {
                paths.root.toPath().toAbsolutePath().normalize() -> {
                    warning(directory.resolve("deleted-during-scan.md"), NoSuchFileException("deleted-during-scan.md"))
                    12L
                }
                paths.trash.toPath().toAbsolutePath().normalize() -> 4L
                paths.recovery.toPath().toAbsolutePath().normalize() -> 3L
                else -> throw AccessDeniedException(directory.toString())
            }
        }

        val result = StorageInspector(paths, cache, reader).measure()

        assertEquals(12L, result.documentsBytes)
        assertEquals(4L, result.trashBytes)
        assertEquals(3L, result.recoveryBytes)
        assertEquals(0L, result.cacheBytes)
        assertTrue(result.warnings.size >= 2)
        assertTrue(result.warnings.any { it.contains("deleted-during-scan.md") })
        assertTrue(result.warnings.any { it.contains("AccessDeniedException") })
    }

    private fun temporaryDirectory(): Path =
        Files.createTempDirectory("monote-storage-").also(temporaryRoots::add)
}
