package app.monote.mobile.core.storage

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AtomicTextStoreCollisionTest {
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
    fun replaceRejectsBackupThatEqualsTargetBeforeFileOperations() {
        val target = temporaryDirectory().resolve("note.md")
        target.writeText("original", StandardCharsets.UTF_8)

        assertIllegalArgument { AtomicTextStore().replace(target.toFile(), "replacement", target.toFile()) }

        assertEquals("original", target.readText(StandardCharsets.UTF_8))
        assertFalse(target.resolveSibling("note.md.monote-tmp").exists())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-atomic-collision-")
        .also(temporaryRoots::add)
}
