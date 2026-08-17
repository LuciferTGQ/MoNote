package app.monote.mobile.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPathsTest {
    private val temporaryRoots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun ensureCreatedCreatesThePublicLibraryDirectoryContract() {
        val root = temporaryDirectory().resolve("资料库")

        val paths = LibraryPaths(root).ensureCreated()

        assertEquals(root.toAbsolutePath().normalize().toFile(), paths.root)
        assertEquals(paths.root.resolve("收件箱"), paths.inbox)
        assertEquals(paths.root.resolve("_MoNoteSystem"), paths.system)
        assertEquals(paths.system.resolve("recovery"), paths.recovery)
        assertEquals(paths.system.resolve("backups"), paths.backups)
        assertEquals(paths.system.resolve("trash"), paths.trash)
        listOf(paths.root, paths.inbox, paths.system, paths.recovery, paths.backups, paths.trash)
            .forEach { assertTrue("Expected directory $it", it.isDirectory) }
    }

    @Test
    fun ensureCreatedSupportsTheFileBasedLibraryContractUsedByRepositories() {
        val root = temporaryDirectory().resolve("file-library").toFile()

        val paths = LibraryPaths(root).ensureCreated()

        assertTrue(paths.inbox.isDirectory)
        assertTrue(paths.backups.isDirectory)
    }

    @Test(expected = IllegalStateException::class)
    fun ensureCreatedFailsWhenAContractDirectoryIsAFile() {
        val root = temporaryDirectory().resolve("library")
        Files.createDirectories(root)
        root.resolve("收件箱").writeText("not a directory")

        LibraryPaths(root).ensureCreated()
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-library-paths-")
        .also(temporaryRoots::add)
}
