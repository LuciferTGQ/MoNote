package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryStoreTest {
    @Test
    fun roundTripPersistsVerifiedDraftAndMetadataBesideIt() = runTest {
        val root = Files.createTempDirectory("monote-recovery").toFile()
        try {
            val original = root.resolve("note.md").apply { writeText("old") }
            val store = RecoveryStore(
                directory = root.resolve("recovery"),
                nowMillis = { 2_000L },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            val session = session(original, "draft")

            store.write(session)
            val restored = store.read(session.id)

            assertNotNull(restored)
            assertEquals("draft", restored?.text)
            assertEquals(session.id, restored?.metadata?.stableId)
            assertEquals(original.absoluteFile.normalize().path, restored?.metadata?.originalPath)
            assertEquals(session.baseline.sha256, restored?.metadata?.baselineSha256)
            assertEquals(DocumentFingerprint.sha256("draft"), restored?.metadata?.draftSha256)
            assertEquals(2_000L, restored?.metadata?.updatedAt)
            assertEquals(1, root.resolve("recovery").listFiles { file -> file.extension == "md" }?.size)
            assertTrue(root.resolve("recovery/${session.id}.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hashMismatchIsRejectedAndDeleteRemovesEveryDraftVersion() = runTest {
        val root = Files.createTempDirectory("monote-recovery-corrupt").toFile()
        try {
            val original = root.resolve("note.md").apply { writeText("old") }
            val recovery = root.resolve("recovery")
            val store = RecoveryStore(
                directory = recovery,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            val session = session(original, "draft")
            store.write(session)
            recovery.listFiles { file -> file.extension == "md" }!!.single().writeText("tampered")

            assertNull(store.read(session.id))

            store.delete(session.id)
            assertFalse(recovery.listFiles().orEmpty().any { it.name.startsWith(session.id) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun candidateMustBeNewerThanOriginalAndHaveDifferentContent() = runTest {
        val root = Files.createTempDirectory("monote-recovery-candidate").toFile()
        try {
            val original = root.resolve("note.md").apply {
                writeText("old")
                assertTrue(setLastModified(1_000L))
            }
            val store = RecoveryStore(
                directory = root.resolve("recovery"),
                nowMillis = { 2_000L },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            val session = session(original, "draft")
            store.write(session)

            assertNotNull(store.candidate(session.id, original))

            assertTrue(original.setLastModified(3_000L))
            assertNull(store.candidate(session.id, original))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun candidateMustBelongToTheSameOriginalPath() = runTest {
        val root = Files.createTempDirectory("monote-recovery-path").toFile()
        try {
            val original = root.resolve("note.md").apply { writeText("old") }
            val different = root.resolve("different.md").apply {
                writeText("different")
                assertTrue(setLastModified(1_000L))
            }
            val store = RecoveryStore(
                directory = root.resolve("recovery"),
                nowMillis = { 2_000L },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            val session = session(original, "draft")
            store.write(session)

            assertNull(store.candidate(session.id, different))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidStableIdCannotEscapeRecoveryDirectory() = runTest {
        val root = Files.createTempDirectory("monote-recovery-id").toFile()
        try {
            val store = RecoveryStore(
                directory = root.resolve("recovery"),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            var rejected = false
            try {
                store.read("../outside")
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symbolicLinkAncestorCannotRedirectRecoveryWrites() = runTest {
        val root = Files.createTempDirectory("monote-recovery-link").toFile()
        val outside = Files.createTempDirectory("monote-recovery-outside").toFile()
        try {
            val link = root.resolve("linked-root").toPath()
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (error: Exception) {
                assumeNoException("Symbolic links are unavailable for this test user", error)
            }
            val original = root.resolve("note.md").apply { writeText("old") }
            val store = RecoveryStore(
                directory = link.resolve("recovery").toFile(),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            var rejected = false

            try {
                store.write(session(original, "draft"))
            } catch (_: SecurityException) {
                rejected = true
            }

            assertTrue(rejected)
            assertFalse(outside.resolve("recovery").exists())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun session(file: File, text: String): DocumentSession {
        val baseline = DocumentFingerprint.from(file)
        return DocumentSession(
            id = "document-1",
            file = file,
            text = text,
            revision = 1,
            baseline = baseline,
            saveStatus = SaveStatus.Unsaved,
            canUndo = true,
            canRedo = false,
            autoSaveEnabled = true,
        )
    }
}
