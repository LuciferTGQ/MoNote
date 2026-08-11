package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.LibraryPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryMetadataRepositoryTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun favoriteAndTagsPersistAndAreObservableForRealDirectories() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        paths.root.resolve("课程").mkdir()
        val repository = DirectoryMetadataRepository(paths)

        repository.setFavorite(setOf("课程"), true)
        repository.setTags(setOf("课程"), setOf("生物", "复习"))

        val observed = repository.metadata.value.getValue("课程")
        assertTrue(observed.favorite)
        assertEquals(setOf("生物", "复习"), observed.tags)
        val restored = DirectoryMetadataRepository(paths).metadata.value.getValue("课程")
        assertTrue(restored.favorite)
        assertEquals(setOf("生物", "复习"), restored.tags)
    }

    @Test
    fun movePathsMigratesOnlyExactDirectorySegmentsAndDescendants() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val course = paths.root.resolve("course").apply { mkdir() }
        course.resolve("nested").mkdir()
        paths.root.resolve("coursework").mkdir()
        paths.root.resolve("archive").mkdir()
        val repository = DirectoryMetadataRepository(paths)
        repository.setFavorite(setOf("course", "course/nested", "coursework"), true)
        repository.setTags(setOf("course/nested"), setOf("kept"))
        Files.move(course.toPath(), paths.root.resolve("archive/course").toPath())

        repository.movePaths(mapOf("course" to "archive/course"))

        assertFalse("course" in repository.metadata.value)
        assertFalse("course/nested" in repository.metadata.value)
        assertTrue(repository.metadata.value.getValue("archive/course").favorite)
        assertEquals(setOf("kept"), repository.metadata.value.getValue("archive/course/nested").tags)
        assertTrue(repository.metadata.value.getValue("coursework").favorite)
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-directory-metadata-").also(roots::add)
}
