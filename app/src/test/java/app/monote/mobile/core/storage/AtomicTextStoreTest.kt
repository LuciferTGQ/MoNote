package app.monote.mobile.core.storage

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicTextStoreTest {
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
    fun replaceWritesUtf8AndLeavesNoTemporaryFile() {
        val target = temporaryDirectory().resolve("nested/笔记.md")

        AtomicTextStore().replace(target.toFile(), "你好，MoNote")

        assertEquals("你好，MoNote", target.readText(StandardCharsets.UTF_8))
        assertFalse(target.resolveSibling("笔记.md.monote-tmp").exists())
    }

    @Test
    fun replaceKeepsOriginalAndCleansTemporaryFileWhenBeforeCommitFails() {
        val target = temporaryDirectory().resolve("note.md")
        target.writeText("original", StandardCharsets.UTF_8)

        val failure = IllegalStateException("stop before commit")
        try {
            AtomicTextStore().replace(
                target.toFile(),
                "replacement",
                beforeCommit = { throw failure },
            )
        } catch (actual: IllegalStateException) {
            assertEquals(failure, actual)
        }

        assertEquals("original", target.readText(StandardCharsets.UTF_8))
        assertFalse(target.resolveSibling("note.md.monote-tmp").exists())
    }

    @Test
    fun replaceCreatesBackupBeforeReplacingAndFingerprintsContent() {
        val directory = temporaryDirectory()
        val target = directory.resolve("note.md")
        val backup = directory.resolve("backups/note.md")
        target.writeText("first", StandardCharsets.UTF_8)

        AtomicTextStore().replace(target.toFile(), "second", backup.toFile())
        val modifiedAt = FileTime.fromMillis(1_234_567_890_000L)
        Files.setLastModifiedTime(target, modifiedAt)

        assertEquals("first", backup.readText(StandardCharsets.UTF_8))
        assertEquals("second", target.readText(StandardCharsets.UTF_8))
        val fingerprint = DocumentFingerprint.from(target.toFile())
        assertEquals(Files.size(target), fingerprint.size)
        assertEquals(modifiedAt.toMillis(), fingerprint.modifiedAt)
        assertEquals("16367aacb67a4a017c8da8ab95682ccb390863780f7114dda0a0e0c55644c7c4", fingerprint.sha256)
    }

    @Test
    fun beforeReplaceDetectsAChangeAfterTheEarlyCommitCheck() {
        val directory = temporaryDirectory()
        val target = directory.resolve("note.md")
        val backup = directory.resolve("backups/note.md")
        target.writeText("original", StandardCharsets.UTF_8)
        val baseline = DocumentFingerprint.from(target.toFile())
        val conflict = IllegalStateException("external change")

        try {
            AtomicTextStore().replace(
                target = target.toFile(),
                text = "mine",
                backup = backup.toFile(),
                beforeCommit = {
                    assertEquals(baseline, DocumentFingerprint.from(target.toFile()))
                    target.writeText("external", StandardCharsets.UTF_8)
                },
                beforeReplace = {
                    if (DocumentFingerprint.from(target.toFile()) != baseline) throw conflict
                },
            )
        } catch (actual: IllegalStateException) {
            assertEquals(conflict, actual)
        }

        assertEquals("external", target.readText(StandardCharsets.UTF_8))
        assertEquals("external", backup.readText(StandardCharsets.UTF_8))
        assertFalse(target.resolveSibling("note.md.monote-tmp").exists())
    }

    @Test
    fun replaceCanContinuouslyOverwriteWithoutResidualTemporaryFiles() {
        val target = temporaryDirectory().resolve("note.md")

        AtomicTextStore().replace(target.toFile(), "one")
        AtomicTextStore().replace(target.toFile(), "two")

        assertEquals("two", target.readText(StandardCharsets.UTF_8))
        assertFalse(target.resolveSibling("note.md.monote-tmp").exists())
        assertEquals("670d9743542cae3ea7ebe36af56bd53648b0a1126162e78d81a32934a711302e", DocumentFingerprint.sha256("你好"))
    }

    @Test
    fun replaceRejectsBackupThatAliasesTheTargetTemporaryPathBeforeWriting() {
        val directory = temporaryDirectory()
        val target = directory.resolve("note.md")
        val targetTemporary = directory.resolve("note.md.monote-tmp")
        target.writeText("original", StandardCharsets.UTF_8)

        assertIllegalArgument { AtomicTextStore().replace(target.toFile(), "replacement", targetTemporary.toFile()) }

        assertEquals("original", target.readText(StandardCharsets.UTF_8))
        assertFalse(targetTemporary.exists())
        assertFalse(directory.resolve("note.md.monote-tmp.monote-tmp").exists())
    }

    @Test
    fun replaceRejectsBackupWhoseTemporaryPathAliasesTheTargetBeforeWriting() {
        val directory = temporaryDirectory()
        val target = directory.resolve("note.md.monote-tmp")
        val backup = directory.resolve("note.md")
        target.writeText("original", StandardCharsets.UTF_8)

        assertIllegalArgument { AtomicTextStore().replace(target.toFile(), "replacement", backup.toFile()) }

        assertEquals("original", target.readText(StandardCharsets.UTF_8))
        assertFalse(backup.exists())
        assertFalse(directory.resolve("note.md.monote-tmp.monote-tmp").exists())
    }

    @Test
    fun replaceRejectsReentryForTheSameTargetAndCleansTheOuterTemporaryFile() {
        val target = temporaryDirectory().resolve("note.md")
        target.writeText("original", StandardCharsets.UTF_8)
        val store = AtomicTextStore()

        assertIllegalState {
            store.replace(target.toFile(), "outer") {
                store.replace(target.toFile(), "inner")
            }
        }

        assertEquals("original", target.readText(StandardCharsets.UTF_8))
        assertFalse(target.resolveSibling("note.md.monote-tmp").exists())
    }

    @Test
    fun replaceRejectsReentryForADifferentTargetAndCleansTheOuterTemporaryFile() {
        val directory = temporaryDirectory()
        val outer = directory.resolve("outer.md")
        val inner = directory.resolve("inner.md")
        outer.writeText("outer-original", StandardCharsets.UTF_8)
        inner.writeText("inner-original", StandardCharsets.UTF_8)
        val store = AtomicTextStore()

        assertIllegalState {
            store.replace(outer.toFile(), "outer-replacement") {
                store.replace(inner.toFile(), "inner-replacement")
            }
        }

        assertEquals("outer-original", outer.readText(StandardCharsets.UTF_8))
        assertEquals("inner-original", inner.readText(StandardCharsets.UTF_8))
        assertFalse(outer.resolveSibling("outer.md.monote-tmp").exists())
        assertFalse(inner.resolveSibling("inner.md.monote-tmp").exists())
    }

    @Test
    fun replaceSerializesConcurrentWritesToTheSameTarget() {
        val target = temporaryDirectory().resolve("note.md")
        target.writeText("original", StandardCharsets.UTF_8)
        val store = AtomicTextStore()
        val firstEntered = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                store.replace(target.toFile(), "first") {
                    firstEntered.countDown()
                    check(allowFirstCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStarted.countDown()
                store.replace(target.toFile(), "second")
                secondFinished.countDown()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))

            allowFirstCommit.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals("second", target.readText(StandardCharsets.UTF_8))
            assertFalse(target.resolveSibling("note.md.monote-tmp").exists())
        } finally {
            allowFirstCommit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun replaceSerializesATargetAndBackupAgainstAnotherCallTargetingTheBackup() {
        val directory = temporaryDirectory()
        val target = directory.resolve("a.md")
        val backup = directory.resolve("b.md")
        target.writeText("old-a", StandardCharsets.UTF_8)
        backup.writeText("old-b", StandardCharsets.UTF_8)
        val store = AtomicTextStore()
        val firstEntered = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                store.replace(target.toFile(), "new-a", backup.toFile()) {
                    firstEntered.countDown()
                    check(allowFirstCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStarted.countDown()
                store.replace(backup.toFile(), "new-b")
                secondFinished.countDown()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))

            allowFirstCommit.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals("new-a", target.readText(StandardCharsets.UTF_8))
            assertEquals("new-b", backup.readText(StandardCharsets.UTF_8))
        } finally {
            allowFirstCommit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun replaceSerializesATargetTemporaryPathAgainstAnotherCallTargetingThatTemporaryPath() {
        val directory = temporaryDirectory()
        val target = directory.resolve("a.md")
        val temporaryTarget = directory.resolve("a.md.monote-tmp")
        target.writeText("old-a", StandardCharsets.UTF_8)
        val store = AtomicTextStore()
        val firstEntered = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                store.replace(target.toFile(), "new-a") {
                    firstEntered.countDown()
                    check(allowFirstCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStarted.countDown()
                store.replace(temporaryTarget.toFile(), "new-temp")
                secondFinished.countDown()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))

            allowFirstCommit.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals("new-a", target.readText(StandardCharsets.UTF_8))
            assertEquals("new-temp", temporaryTarget.readText(StandardCharsets.UTF_8))
        } finally {
            allowFirstCommit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun replaceEncodesAnUnpairedHighSurrogateLikeStandardUtf8() {
        val target = temporaryDirectory().resolve("surrogate.md")
        val text = "x\uD800"

        AtomicTextStore().replace(target.toFile(), text)

        assertArrayEquals(text.toByteArray(StandardCharsets.UTF_8), Files.readAllBytes(target))
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun assertIllegalState(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-atomic-store-")
        .also(temporaryRoots::add)
}
