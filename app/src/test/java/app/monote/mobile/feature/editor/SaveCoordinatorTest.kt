package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryPaths
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveCoordinatorTest {
    @Test
    fun recoveryWritesAt500msAndOriginalAt1000ms() = runTest {
        val recovery = RecordingRecoverySink()
        val original = RecordingDocumentSink()
        val events = mutableListOf<DocumentEvent>()
        val coordinator = SaveCoordinator(this, recovery, original, events::add)
        val session = session(text = "new")

        coordinator.onChanged(session)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, recovery.writeCount)
        assertEquals(0, original.writeCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, recovery.writeCount)
        assertEquals(0, original.writeCount)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, original.writeCount)
        assertTrue(events.any { it is DocumentEvent.SaveStarted })
        assertTrue(events.any { it is DocumentEvent.Saved })
    }

    @Test
    fun disabledAutoSaveStillMaintainsRecoveryDraft() = runTest {
        val recovery = RecordingRecoverySink()
        val original = RecordingDocumentSink()
        val coordinator = SaveCoordinator(this, recovery, original) {}

        coordinator.onChanged(session(text = "new", autoSave = false))
        advanceUntilIdle()

        assertEquals(1, recovery.writeCount)
        assertEquals(0, original.writeCount)
    }

    @Test
    fun backgroundFlushWritesRecoveryThenBestEffortOriginalImmediately() = runTest {
        val order = mutableListOf<String>()
        val recovery = object : RecoveryDraftSink {
            override suspend fun write(session: DocumentSession) {
                order += "recovery"
            }

            override suspend fun delete(stableId: String) = Unit
        }
        val original = object : OriginalDocumentSink {
            override suspend fun save(session: DocumentSession): DocumentSaveResult {
                order += "original"
                return DocumentSaveResult.Saved(fingerprint("new", 20))
            }
        }
        val coordinator = SaveCoordinator(this, recovery, original) {}

        coordinator.flushForBackground(session(text = "new"))

        assertEquals(listOf("recovery", "original"), order)
    }

    @Test
    fun laterEditCancelsStaleTimers() = runTest {
        val recovery = RecordingRecoverySink()
        val original = RecordingDocumentSink()
        val coordinator = SaveCoordinator(this, recovery, original) {}

        coordinator.onChanged(session(text = "first"))
        advanceTimeBy(400)
        coordinator.onChanged(session(text = "second", revision = 2))
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf("second"), recovery.writtenTexts)
        assertEquals(0, original.writeCount)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("second"), original.writtenTexts)
    }

    @Test
    fun editDuringInFlightSaveUsesTheBaselineAdvancedByThatSave() = runTest {
        val original = BlockingDocumentSink()
        val events = mutableListOf<DocumentEvent>()
        val coordinator = SaveCoordinator(
            this,
            RecordingRecoverySink(),
            original,
            events::add,
        )

        coordinator.onChanged(session(text = "first"))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(original.firstStarted.isCompleted)

        coordinator.onChanged(session(text = "second", revision = 2))
        original.releaseFirst.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, original.baselines.size)
        assertEquals(original.firstSavedFingerprint, original.baselines[1])
        assertEquals(2, events.count { it is DocumentEvent.Saved })
        assertFalse(events.any { it is DocumentEvent.ExternalConflict })
    }

    @Test
    fun recoveryFailureDoesNotPreventOriginalSave() = runTest {
        val recovery = RecordingRecoverySink().apply { failure = IllegalStateException("disk full") }
        val original = RecordingDocumentSink()
        val events = mutableListOf<DocumentEvent>()
        val coordinator = SaveCoordinator(this, recovery, original, events::add)

        coordinator.onChanged(session(text = "new"))
        advanceUntilIdle()

        assertEquals(1, original.writeCount)
        assertTrue(events.any { it is DocumentEvent.RecoveryFailed })
    }

    @Test
    fun cleanCloseDeletesDraftButDirtyCloseKeepsIt() = runTest {
        val recovery = RecordingRecoverySink()
        val coordinator = SaveCoordinator(this, recovery, RecordingDocumentSink()) {}

        coordinator.onCleanClose(session(text = "new"))
        assertEquals(0, recovery.deletedIds.size)

        coordinator.onCleanClose(session(text = "old"))
        assertEquals(listOf("document-1"), recovery.deletedIds)
    }

    @Test
    fun externalModificationIsReportedAndNeverSilentlyOverwritten() = runTest {
        val root = Files.createTempDirectory("monote-conflict").toFile()
        try {
            val paths = LibraryPaths(root).ensureCreated()
            val file = paths.inbox.resolve("note.md").apply { writeText("old") }
            val baseline = DocumentFingerprint.from(file)
            val document = session(file = file, text = "mine", baseline = baseline)
            file.writeText("external")

            val result = FileDocumentSink(paths).save(document)

            assertTrue(result is DocumentSaveResult.Conflict)
            assertEquals("external", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun externallyDeletedDocumentIsNotAutomaticallyRecreated() = runTest {
        val root = Files.createTempDirectory("monote-deleted-conflict").toFile()
        try {
            val paths = LibraryPaths(root).ensureCreated()
            val file = paths.inbox.resolve("note.md").apply { writeText("old") }
            val baseline = DocumentFingerprint.from(file)
            val document = session(file = file, text = "mine", baseline = baseline)
            assertTrue(file.delete())

            val result = FileDocumentSink(paths).save(document)

            assertEquals(DocumentSaveResult.Conflict(ExternalDocumentVersion.Deleted), result)
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictActionsBackUpLoadCopyAndDiffWithoutHiddenOverwrite() = runTest {
        val root = Files.createTempDirectory("monote-resolution").toFile()
        try {
            val paths = LibraryPaths(root).ensureCreated()
            val file = paths.inbox.resolve("note.md").apply { writeText("old\nline") }
            val baseline = DocumentFingerprint.from(file)
            val document = session(file = file, text = "mine\nline", baseline = baseline)
            file.writeText("external\nline")
            val resolver = ConflictResolver(paths)

            val external = resolver.loadExternal(document)
            val diff = resolver.viewDiff(document, external)
            val firstCopy = resolver.saveCopy(document)
            val secondCopy = resolver.saveCopy(document)
            val kept = resolver.keepMine(document)

            assertTrue(external is ExternalDocumentVersion.Present)
            assertEquals("external\nline", (external as ExternalDocumentVersion.Present).text)
            assertEquals(
                listOf(
                    LineDiffHunk(
                        oldStart = 1,
                        newStart = 1,
                        removed = listOf("external"),
                        added = listOf("mine"),
                    ),
                ),
                diff,
            )
            assertEquals(
                listOf(LineDiffHunk(1, 1, emptyList(), listOf("mine"))),
                lineDiff("", "mine"),
            )
            assertEquals("mine\nline", firstCopy.file.readText())
            assertNotEquals(firstCopy.file, secondCopy.file)
            assertEquals("mine\nline", file.readText())
            assertEquals("external\nline", paths.backups.resolve("document-1.md").readText())
            assertEquals(DocumentFingerprint.sha256("mine\nline"), kept.fingerprint.sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun session(
        file: File = File("note.md"),
        text: String,
        revision: Long = 1,
        autoSave: Boolean = true,
        baseline: DocumentFingerprint = fingerprint("old", 10),
    ): DocumentSession = DocumentSession(
        id = "document-1",
        file = file,
        text = text,
        revision = revision,
        baseline = baseline,
        saveStatus = SaveStatus.Unsaved,
        canUndo = true,
        canRedo = false,
        autoSaveEnabled = autoSave,
    )

    private fun fingerprint(text: String, modifiedAt: Long): DocumentFingerprint =
        DocumentFingerprint(
            size = text.toByteArray().size.toLong(),
            modifiedAt = modifiedAt,
            sha256 = DocumentFingerprint.sha256(text),
        )

    private class RecordingRecoverySink : RecoveryDraftSink {
        var failure: Throwable? = null
        val writtenTexts = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        val writeCount get() = writtenTexts.size

        override suspend fun write(session: DocumentSession) {
            failure?.let { throw it }
            writtenTexts += session.text
        }

        override suspend fun delete(stableId: String) {
            deletedIds += stableId
        }
    }

    private class RecordingDocumentSink : OriginalDocumentSink {
        val writtenTexts = mutableListOf<String>()
        val writeCount get() = writtenTexts.size

        override suspend fun save(session: DocumentSession): DocumentSaveResult {
            writtenTexts += session.text
            return DocumentSaveResult.Saved(fingerprint(session.text, 20))
        }

        private fun fingerprint(text: String, modifiedAt: Long): DocumentFingerprint =
            DocumentFingerprint(
                size = text.toByteArray().size.toLong(),
                modifiedAt = modifiedAt,
                sha256 = DocumentFingerprint.sha256(text),
            )
    }

    private class BlockingDocumentSink : OriginalDocumentSink {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val baselines = mutableListOf<DocumentFingerprint>()
        val firstSavedFingerprint = fingerprint("first", 20)

        override suspend fun save(session: DocumentSession): DocumentSaveResult {
            baselines += session.baseline
            return if (baselines.size == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                DocumentSaveResult.Saved(firstSavedFingerprint)
            } else if (session.baseline == firstSavedFingerprint) {
                DocumentSaveResult.Saved(fingerprint("second", 30))
            } else {
                DocumentSaveResult.Conflict(
                    ExternalDocumentVersion.Present(firstSavedFingerprint, "first"),
                )
            }
        }

        private fun fingerprint(text: String, modifiedAt: Long): DocumentFingerprint =
            DocumentFingerprint(
                size = text.toByteArray().size.toLong(),
                modifiedAt = modifiedAt,
                sha256 = DocumentFingerprint.sha256(text),
            )
    }
}
