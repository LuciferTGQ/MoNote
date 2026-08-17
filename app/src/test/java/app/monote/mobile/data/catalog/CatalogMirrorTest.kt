package app.monote.mobile.data.catalog

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMirrorTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun writeAndReadRoundTripsStableMetadataAndChinesePaths() = runBlocking {
        val mirror = CatalogMirror(temporaryDirectory())
        val snapshot = CatalogSnapshot(
            documents = listOf(CatalogDocument("stable-id", "笔记/呼吸.md", true, setOf("健康", "inbox"))),
        )

        mirror.write(snapshot)

        assertEquals(snapshot, mirror.read())
    }

    @Test
    fun readFallsBackToBackupAfterOneSuccessfulWriteWhenPrimaryIsDamaged() = runBlocking {
        val root = temporaryDirectory()
        val mirror = CatalogMirror(root)
        val snapshot = CatalogSnapshot(documents = listOf(CatalogDocument("id", "note.md", false, setOf("tag"))))
        mirror.write(snapshot)
        root.resolve("catalog.json").writeText("not json", StandardCharsets.UTF_8)

        assertEquals(snapshot, mirror.read())
        assertTrue(Files.exists(root.resolve("catalog.json.bak")))
    }

    @Test
    fun readReturnsEmptyWhenBothCopiesAreInvalid() = runBlocking {
        val root = temporaryDirectory()
        val mirror = CatalogMirror(root)
        mirror.write(CatalogSnapshot(documents = listOf(CatalogDocument("id", "note.md", false, emptySet()))))
        root.resolve("catalog.json").writeText("{", StandardCharsets.UTF_8)
        root.resolve("catalog.json.bak").writeText("{", StandardCharsets.UTF_8)

        assertEquals(CatalogSnapshot(), mirror.read())
    }

    @Test
    fun unsupportedPrimaryVersionFallsBackToBackup() = runBlocking {
        val root = temporaryDirectory()
        val mirror = CatalogMirror(root)
        val snapshot = CatalogSnapshot(documents = listOf(CatalogDocument("id", "note.md", true, emptySet())))
        mirror.write(snapshot)
        root.resolve("catalog.json").writeText("{\"version\":999,\"documents\":[]}", StandardCharsets.UTF_8)

        assertEquals(snapshot, mirror.read())
    }

    @Test
    fun readChoosesNewestValidCopyInsteadOfAlwaysPreferringPrimary() = runBlocking {
        val root = temporaryDirectory()
        val mirror = CatalogMirror(root)
        val stale = CatalogSnapshot(documents = listOf(CatalogDocument("stale", "old.md", false, emptySet())))
        val current = CatalogSnapshot(documents = listOf(CatalogDocument("current", "new.md", true, setOf("latest"))))
        mirror.write(stale)
        val backup = root.resolve("catalog.json.bak")
        backup.writeText(
            """{"version":1,"documents":[{"id":"current","relativePath":"new.md","favorite":true,"tags":["latest"]}]}""",
            StandardCharsets.UTF_8,
        )
        Files.setLastModifiedTime(root.resolve("catalog.json"), FileTime.fromMillis(1_000L))
        Files.setLastModifiedTime(backup, FileTime.fromMillis(2_000L))

        assertEquals(current, mirror.read())
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-catalog-mirror-").also(roots::add)
}
