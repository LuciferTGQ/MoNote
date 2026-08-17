package app.monote.mobile.ui.navigation

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class DocumentExporterTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun copiesTheMarkdownDocument() = runBlocking {
        val source = temporaryDirectory().resolve("lesson.md").toFile().apply {
            writeBytes("# Lesson".toByteArray())
        }
        val destination = ByteArrayOutputStream()

        exportMarkdown(source) { destination }

        assertArrayEquals(source.readBytes(), destination.toByteArray())
    }

    @Test
    fun missingSourceIsReported() = runBlocking {
        expectIOException {
            exportMarkdown(temporaryDirectory().resolve("missing.md").toFile()) { ByteArrayOutputStream() }
        }
    }

    @Test
    fun unavailableDestinationIsReported() = runBlocking {
        val source = temporaryDirectory().resolve("lesson.md").toFile().apply { writeText("lesson") }

        expectIOException { exportMarkdown(source) { null } }
    }

    @Test
    fun destinationFailureIsReported() = runBlocking {
        val source = temporaryDirectory().resolve("lesson.md").toFile().apply { writeText("lesson") }

        expectIOException { exportMarkdown(source) { throw IOException("provider failed") } }
    }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected: the navigation layer translates it into a visible UI error.
        }
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-export-").also(roots::add)
}
