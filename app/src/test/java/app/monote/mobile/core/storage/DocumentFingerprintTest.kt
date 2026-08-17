package app.monote.mobile.core.storage

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFingerprintTest {
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
    fun fromFingerprintsLargeFilesWithTheKnownSha256() {
        val file = temporaryDirectory().resolve("large.md")
        val content = "MoNote\n".repeat(300_000)
        file.writeText(content, StandardCharsets.UTF_8)

        val fingerprint = DocumentFingerprint.from(file.toFile())

        assertEquals(content.toByteArray(StandardCharsets.UTF_8).size.toLong(), fingerprint.size)
        assertEquals("9d41a953992dadde9a16b7390f44e779f59731d85c5f09a5581995e02ba9e177", fingerprint.sha256)
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-fingerprint-")
        .also(temporaryRoots::add)
}
