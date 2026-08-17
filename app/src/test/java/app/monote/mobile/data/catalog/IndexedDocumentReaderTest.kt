package app.monote.mobile.data.catalog

import app.monote.mobile.core.storage.ConcurrentDocumentModificationException
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.feature.importing.TextInputTooLargeException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.writeBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexedDocumentReaderTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun utf16BomTextAndShaComeFromTheSameRawByteSnapshot() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "# 复习".toByteArray(Charsets.UTF_16LE)
        val file = temporaryDirectory().resolve("note.md").also { it.writeBytes(bytes) }

        val snapshot = IndexedDocumentReader().read(file.toFile())

        assertEquals("# 复习", snapshot.body)
        assertEquals(bytes.size.toLong(), snapshot.fingerprint.size)
        assertEquals(DocumentFingerprint.sha256(bytes), snapshot.fingerprint.sha256)
    }

    @Test
    fun retriesWhenFileChangesDuringReadAndReturnsOnlyTheStableVersion() {
        val file = temporaryDirectory().resolve("note.md")
        file.writeBytes("old".toByteArray(StandardCharsets.UTF_8))
        var changed = false
        val reader = IndexedDocumentReader(afterRead = {
            if (!changed) {
                changed = true
                file.writeBytes("# new".toByteArray(StandardCharsets.UTF_8))
            }
        })

        val snapshot = reader.read(file.toFile())

        assertEquals("# new", snapshot.body)
        assertEquals(DocumentFingerprint.sha256("# new"), snapshot.fingerprint.sha256)
    }

    @Test(expected = ConcurrentDocumentModificationException::class)
    fun rejectsAFileThatChangesDuringEveryReadAttempt() {
        val file = temporaryDirectory().resolve("note.md")
        file.writeBytes("x".toByteArray(StandardCharsets.UTF_8))
        var suffix = 0
        val reader = IndexedDocumentReader(afterRead = {
            suffix += 1
            file.writeBytes("changed-$suffix".toByteArray(StandardCharsets.UTF_8))
        })

        reader.read(file.toFile())
    }

    @Test(expected = TextInputTooLargeException::class)
    fun defaultReaderRejectsFilesAboveSixteenMiB() {
        val file = temporaryDirectory().resolve("large.md")
        Files.newOutputStream(file).use { output ->
            val block = ByteArray(1024 * 1024)
            repeat(16) { output.write(block) }
            output.write(0)
        }

        IndexedDocumentReader().read(file.toFile())
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-index-reader-").also(roots::add)
}
