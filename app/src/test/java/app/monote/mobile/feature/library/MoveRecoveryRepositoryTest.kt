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

class MoveRecoveryRepositoryTest {
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun recordedRollbackFailureSurvivesRepositoryReconstruction() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val original = paths.root.resolve("note.md")
        val current = paths.root.resolve("archive/note.md")
        requireNotNull(current.parentFile).mkdirs()
        current.writeText("content")
        val repository = MoveRecoveryRepository(paths)

        val recorded = repository.record(
            listOf(MoveRecoveryRecord(original, current, "rollback denied")),
        ).requireRecorded().single()

        assertTrue(recorded.id.isNotBlank())
        assertTrue(paths.system.resolve("move-recovery.json").isFile)
        val restored = MoveRecoveryRepository(paths).records.value.single()
        assertEquals(recorded.id, restored.id)
        assertEquals(original.absoluteFile, restored.original.absoluteFile)
        assertEquals(current.absoluteFile, restored.current.absoluteFile)
        assertEquals("rollback denied", restored.message)
    }

    @Test
    fun successfulRecoveryMovesContentBackBeforeRemovingTheRecord() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val original = paths.root.resolve("note.md")
        val current = paths.root.resolve("archive/note.md")
        requireNotNull(current.parentFile).mkdirs()
        current.writeText("recover me")
        val repository = MoveRecoveryRepository(paths)
        val record = repository.record(
            listOf(MoveRecoveryRecord(original, current, "rollback denied")),
        ).requireRecorded().single()

        val result = repository.recover(record.id)

        assertTrue(result is LibraryResult.Success)
        assertEquals("recover me", original.readText())
        assertFalse(current.exists())
        assertTrue(repository.records.value.isEmpty())
        assertTrue(MoveRecoveryRepository(paths).records.value.isEmpty())
    }

    @Test
    fun targetConflictNeverOverwritesAndKeepsTheRecoveryRecord() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val original = paths.root.resolve("note.md")
        val current = paths.root.resolve("archive/note.md")
        requireNotNull(current.parentFile).mkdirs()
        current.writeText("recover me")
        val repository = MoveRecoveryRepository(paths)
        val record = repository.record(
            listOf(MoveRecoveryRecord(original, current, "rollback denied")),
        ).requireRecorded().single()
        original.writeText("do not overwrite")

        val result = repository.recover(record.id)

        assertTrue(result is LibraryResult.Conflict)
        assertEquals("do not overwrite", original.readText())
        assertEquals("recover me", current.readText())
        assertEquals(listOf(record.id), repository.records.value.map { it.id })
    }

    @Test
    fun restartKeepsCurrentMissingRecordAndIdempotentRecoveryClearsIt() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val original = paths.root.resolve("note.md")
        val current = paths.root.resolve("archive/note.md")
        requireNotNull(current.parentFile).mkdirs()
        current.writeText("already restored")
        val recorded = MoveRecoveryRepository(paths).record(
            listOf(MoveRecoveryRecord(original, current, "rollback denied")),
        ).requireRecorded().single()
        Files.move(current.toPath(), original.toPath())

        val restarted = MoveRecoveryRepository(paths)

        assertEquals(listOf(recorded.id), restarted.records.value.map { it.id })
        assertTrue(restarted.recover(recorded.id) is LibraryResult.Success)
        assertEquals("already restored", original.readText())
        assertTrue(restarted.records.value.isEmpty())
        assertTrue(MoveRecoveryRepository(paths).records.value.isEmpty())
    }

    @Test
    fun doubleMissingRecoveryStaysVisibleWithAnExplicitManualRecoveryFailure() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val original = paths.root.resolve("note.md")
        val current = paths.root.resolve("archive/note.md")
        requireNotNull(current.parentFile).mkdirs()
        current.writeText("lost elsewhere")
        val recorded = MoveRecoveryRepository(paths).record(
            listOf(MoveRecoveryRecord(original, current, "rollback denied")),
        ).requireRecorded().single()
        assertTrue(current.delete())
        val restarted = MoveRecoveryRepository(paths)

        val result = restarted.recover(recorded.id)

        val failure = result as LibraryResult.Failure
        assertTrue(failure.message.contains("无法自动恢复"))
        assertTrue(failure.message.contains(original.path))
        assertTrue(failure.message.contains(current.path))
        assertEquals(listOf(recorded.id), restarted.records.value.map { it.id })
        assertEquals(listOf(recorded.id), MoveRecoveryRepository(paths).records.value.map { it.id })
    }

    @Test
    fun restartKeepsSafeMissingPathsButDropsARecoveryPathOutsideTheLibrary() {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        paths.system.resolve("move-recovery.json").writeText(
            """
            {
              "version": 1,
              "records": [
                {
                  "id": "safe-id",
                  "originalRelativePath": "safe.md",
                  "currentRelativePath": "archive/safe.md",
                  "message": "safe missing paths",
                  "createdAt": "2026-08-01T00:00:00Z"
                },
                {
                  "id": "unsafe-id",
                  "originalRelativePath": "../outside.md",
                  "currentRelativePath": "archive/unsafe.md",
                  "message": "unsafe path",
                  "createdAt": "2026-08-01T00:00:00Z"
                }
              ]
            }
            """.trimIndent(),
        )

        val records = MoveRecoveryRepository(paths).records.value

        assertEquals(listOf("safe-id"), records.map { it.id })
        assertEquals(paths.root.resolve("safe.md").absoluteFile, records.single().original.absoluteFile)
        assertEquals(paths.root.resolve("archive/safe.md").absoluteFile, records.single().current.absoluteFile)
    }

    @Test
    fun atomicJournalWriteFailureReturnsFailedWithoutPublishingRecords() = runBlocking {
        val paths = LibraryPaths(temporaryDirectory().resolve("library")).ensureCreated()
        val original = paths.root.resolve("note.md")
        val current = paths.root.resolve("archive/note.md")
        requireNotNull(current.parentFile).mkdirs()
        current.writeText("needs recovery")
        paths.system.resolve("move-recovery.json.monote-tmp").apply {
            mkdirs()
            resolve("blocker").writeText("blocked")
        }
        val repository = MoveRecoveryRepository(paths)

        val result = repository.record(
            listOf(MoveRecoveryRecord(original, current, "rollback denied")),
        )

        assertTrue(result is RecoveryWriteResult.Failed)
        assertTrue(repository.records.value.isEmpty())
        assertFalse(paths.system.resolve("move-recovery.json").exists())
    }

    private fun temporaryDirectory(): Path =
        Files.createTempDirectory("monote-move-recovery-").also(roots::add)

    private fun RecoveryWriteResult.requireRecorded(): List<MoveRecoveryRecord> =
        (this as? RecoveryWriteResult.Recorded)?.records
            ?: throw AssertionError("Expected recovery records, got $this")
}
