package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryPaths
import app.monote.mobile.data.catalog.CatalogMirror
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.DocumentEntity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRepositoryTest {
    private val temporaryRoots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun trashKeepsStableIdRelativePathAndDeletionTimeInJsonSidecar() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("生物/呼吸.md")
        requireNotNull(source.parentFile).mkdirs()
        source.writeText("oxygen")
        environment.catalog.upsert(
            DocumentEntity("stable-id", "生物/呼吸.md", "呼吸", source.lastModified(), source.length(), "hash"),
            source.readText(),
            emptySet(),
        )
        val now = Instant.parse("2026-07-26T00:00:00Z")

        val entry = environment.repository.moveToTrash(source, now)

        assertEquals("stable-id", entry.stableId)
        assertEquals("生物/呼吸.md", entry.originalRelativePath)
        assertEquals(now, entry.deletedAt)
        assertTrue(entry.trashedFile.exists())
        assertFalse(source.exists())
        val metadata = Json.parseToJsonElement(entry.metadataFile.readText()).jsonObject
        assertEquals("stable-id", metadata.getValue("stableId").jsonPrimitive.content)
        assertEquals("生物/呼吸.md", metadata.getValue("originalRelativePath").jsonPrimitive.content)
        assertEquals(now.toString(), metadata.getValue("deletedAt").jsonPrimitive.content)
    }

    @Test
    fun restoreConflictNeverOverwritesTheCurrentLibraryFile() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("note.md").apply { writeText("trashed") }
        val entry = environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"), "stable-id")
        source.writeText("current")

        val result = environment.repository.restore(entry)

        assertTrue(result is LibraryResult.Conflict)
        assertEquals("current", source.readText())
        assertEquals("trashed", entry.trashedFile.readText())
    }

    @Test
    fun restoreReturnsContentToItsOriginalPathAndRemovesTheSidecar() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("folder/note.md")
        requireNotNull(source.parentFile).mkdirs()
        source.writeText("restore me")
        val entry = environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"), "stable-id")

        val result = environment.repository.restore(entry)

        assertEquals(source, (result as LibraryResult.Success).file)
        assertEquals("restore me", source.readText())
        assertFalse(entry.metadataFile.exists())
        assertFalse(entry.trashedFile.exists())
    }

    @Test
    fun permanentDeleteRequiresExplicitConfirmation() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("note.md").apply { writeText("content") }
        val entry = environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"), "stable-id")

        val result = environment.repository.deletePermanently(entry.stableId, confirmed = false)

        assertTrue(result is TrashDeleteResult.ConfirmationRequired)
        assertTrue(entry.trashedFile.exists())
    }

    @Test
    fun purgeDeletesOnlyEntriesStrictlyOlderThanThirtyDaysAfterConfirmation() = runBlocking {
        val environment = environment()
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val old = environment.paths.root.resolve("old.md").apply { writeText("old") }
        val boundary = environment.paths.root.resolve("boundary.md").apply { writeText("boundary") }
        val recent = environment.paths.root.resolve("recent.md").apply { writeText("recent") }
        environment.repository.moveToTrash(old, now.minus(Duration.ofDays(31)), "old")
        environment.repository.moveToTrash(boundary, now.minus(Duration.ofDays(30)), "boundary")
        environment.repository.moveToTrash(recent, now.minus(Duration.ofDays(2)), "recent")

        val result = environment.repository.purgeExpired(now, confirmed = true)

        assertEquals(setOf("old"), (result as TrashDeleteResult.Success).deletedStableIds)
        assertEquals(setOf("boundary", "recent"), environment.repository.listEntries().map { it.stableId }.toSet())
    }

    @Test
    fun unconfirmedExpiredPurgeLeavesEveryEntryInPlace() = runBlocking {
        val environment = environment()
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val source = environment.paths.root.resolve("old.md").apply { writeText("old") }
        environment.repository.moveToTrash(source, now.minus(Duration.ofDays(31)), "old")

        val result = environment.repository.purgeExpired(now, confirmed = false)

        assertTrue(result is TrashDeleteResult.ConfirmationRequired)
        assertEquals(listOf("old"), environment.repository.listEntries().map { it.stableId })
    }

    @Test
    fun expiredPurgeNeverDeletesAnOldPendingRestoreJournal() = runBlocking {
        val environment = environment()
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val source = environment.paths.root.resolve("old-pending.md").apply { writeText("content") }
        val entry = environment.repository.moveToTrash(
            source,
            now.minus(Duration.ofDays(31)),
            "old-pending",
        )
        environment.dao.failMutations = true
        val restore = environment.repository.restore(entry) as LibraryResult.Success

        val result = environment.repository.purgeExpired(now, confirmed = true)

        assertFalse(restore.catalogSynchronized)
        assertTrue((result as TrashDeleteResult.Success).deletedStableIds.isEmpty())
        assertTrue(source.exists())
        assertTrue(entry.metadataFile.exists())
        assertEquals(
            TrashEntryState.PENDING_RESTORE,
            environment.repository.listEntries().single().state,
        )
    }

    @Test
    fun maliciousOriginalPathInSidecarIsIgnoredAndCannotEscapeOnRestore() = runBlocking {
        val environment = environment()
        val entryRoot = environment.paths.trash.resolve("malicious").apply { mkdirs() }
        val content = entryRoot.resolve("content/outside.md")
        requireNotNull(content.parentFile).mkdirs()
        content.writeText("malicious")
        entryRoot.resolve("entry.json").writeText(
            """{"stableId":"malicious","originalRelativePath":"../outside.md","deletedAt":"2026-07-01T00:00:00Z"}""",
        )

        assertTrue(environment.repository.listEntries().isEmpty())
        assertFalse(requireNotNull(environment.paths.root.parentFile).resolve("outside.md").exists())
    }

    @Test
    fun cancellationDuringCatalogRemovalKeepsTrashedContentAndSchedulesRescan() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("cancelled.md").apply { writeText("content") }
        environment.dao.mutationFailure = CancellationException("cancel catalog")

        try {
            environment.repository.moveToTrash(
                source,
                Instant.parse("2026-07-01T00:00:00Z"),
                "cancelled",
            )
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertFalse(source.exists())
        assertTrue(environment.paths.trash.resolve("cancelled/content/cancelled.md").exists())
        assertEquals(1, environment.rescanRequests)
    }

    @Test
    fun singleDocumentMetadataRoundTripsThroughTrashSidecar() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("note.md").apply { writeText("# Note\nbody") }
        environment.catalog.upsert(
            DocumentEntity(
                "document-id",
                "note.md",
                "Note",
                source.lastModified(),
                source.length(),
                "hash",
                favorite = true,
                lastOpenedAt = 1234L,
            ),
            source.readText(),
            setOf("study", "重点"),
        )
        val expectedSize = source.length()
        val expectedSha256 = DocumentFingerprint.sha256File(source)

        val entry = environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"))
        val metadataDocument = Json.parseToJsonElement(entry.metadataFile.readText())
            .jsonObject.getValue("documents").jsonArray.single().jsonObject
        assertEquals("document-id", metadataDocument.getValue("id").jsonPrimitive.content)
        assertEquals("note.md", metadataDocument.getValue("originalRelativePath").jsonPrimitive.content)
        assertEquals(expectedSize.toString(), metadataDocument.getValue("size").jsonPrimitive.content)
        assertEquals(expectedSha256, metadataDocument.getValue("sha256").jsonPrimitive.content)

        assertTrue(environment.repository.restore(entry) is LibraryResult.Success)
        val restored = requireNotNull(environment.catalog.getByPath("note.md"))
        assertEquals("document-id", restored.id)
        assertTrue(restored.favorite)
        assertEquals(1234L, restored.lastOpenedAt)
        assertEquals(setOf("study", "重点"), environment.catalog.tags(restored.id))
    }

    @Test
    fun oversizedMarkdownRestoreKeepsMovedContentAndPendingJournalWhenIndexingCannotDecode() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("oversized.md")
        Files.newOutputStream(source.toPath()).use { output ->
            val mebibyte = ByteArray(1024 * 1024)
            repeat(16) { output.write(mebibyte) }
            output.write(1)
        }
        val expected = DocumentFingerprint.from(source)

        val entry = environment.repository.moveToTrash(
            source,
            Instant.parse("2026-07-01T00:00:00Z"),
            "oversized-entry",
        )
        val result = environment.repository.restore(entry) as LibraryResult.Success

        val document = entry.documents.single()
        assertTrue(source.exists())
        assertFalse(entry.trashedFile.exists())
        assertFalse(result.catalogSynchronized)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(entry.metadataFile.exists())
        assertEquals(1, environment.trustDirectory.listFiles().orEmpty().size)
        assertEquals("oversized", document.title)
        assertEquals(expected.size, document.size)
        assertEquals(expected.sha256, document.sha256)
        assertEquals(TrashEntryState.PENDING_RESTORE, environment.repository.listEntries().single().state)
    }

    @Test
    fun malformedUtf8RestoreKeepsMovedContentAndPendingJournalWhenIndexingCannotDecode() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("malformed.md")
        Files.write(source.toPath(), byteArrayOf(0xC3.toByte(), 0x28))
        val expected = DocumentFingerprint.from(source)

        val entry = environment.repository.moveToTrash(
            source,
            Instant.parse("2026-07-01T00:00:00Z"),
            "malformed-entry",
        )
        val result = environment.repository.restore(entry) as LibraryResult.Success

        val document = entry.documents.single()
        assertTrue(source.exists())
        assertFalse(entry.trashedFile.exists())
        assertFalse(result.catalogSynchronized)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(entry.metadataFile.exists())
        assertEquals(1, environment.trustDirectory.listFiles().orEmpty().size)
        assertEquals("malformed", document.title)
        assertEquals(expected.size, document.size)
        assertEquals(expected.sha256, document.sha256)
        assertEquals(TrashEntryState.PENDING_RESTORE, environment.repository.listEntries().single().state)
    }

    @Test
    fun directoryTrashRoundTripsMetadataForEveryMarkdownDocumentByRelativePath() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        val first = folder.resolve("first.md").apply { writeText("first") }
        val second = folder.resolve("nested/second.markdown")
        requireNotNull(second.parentFile).mkdirs()
        second.writeText("second")
        environment.catalog.upsert(
            DocumentEntity("first-id", "course/first.md", "first", first.lastModified(), first.length(), "hash", favorite = true),
            first.readText(),
            setOf("one"),
        )
        environment.catalog.upsert(
            DocumentEntity("second-id", "course/nested/second.markdown", "second", second.lastModified(), second.length(), "hash", lastOpenedAt = 99L),
            second.readText(),
            setOf("two"),
        )

        val entry = environment.repository.moveToTrash(
            folder,
            Instant.parse("2026-07-01T00:00:00Z"),
            "folder-entry",
        )
        val documentPaths = Json.parseToJsonElement(entry.metadataFile.readText())
            .jsonObject.getValue("documents").jsonArray
            .map { it.jsonObject.getValue("originalRelativePath").jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("course/first.md", "course/nested/second.markdown"), documentPaths)
        entry.trashedFile.resolve("added.md").writeText("added after trashing")

        assertTrue(environment.repository.restore(entry) is LibraryResult.Success)
        val restoredFirst = requireNotNull(environment.catalog.getByPath("course/first.md"))
        val restoredSecond = requireNotNull(environment.catalog.getByPath("course/nested/second.markdown"))
        val restoredAdded = requireNotNull(environment.catalog.getByPath("course/added.md"))
        assertEquals("first-id", restoredFirst.id)
        assertTrue(restoredFirst.favorite)
        assertEquals(setOf("one"), environment.catalog.tags(restoredFirst.id))
        assertEquals("second-id", restoredSecond.id)
        assertEquals(99L, restoredSecond.lastOpenedAt)
        assertEquals(setOf("two"), environment.catalog.tags(restoredSecond.id))
        assertTrue(restoredAdded.id !in setOf("folder-entry", "first-id", "second-id"))
    }

    @Test
    fun directoryFavoriteAndTagsRoundTripThroughTrash() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        environment.directoryMetadata.setTags(setOf("course"), setOf("review"))

        val entry = environment.repository.moveToTrash(
            folder,
            Instant.parse("2026-07-01T00:00:00Z"),
            "directory-entry",
        )

        assertFalse("course" in environment.directoryMetadata.metadata.value)
        assertTrue(environment.repository.restore(entry) is LibraryResult.Success)
        val restored = environment.directoryMetadata.metadata.value.getValue("course")
        assertTrue(restored.favorite)
        assertEquals(setOf("review"), restored.tags)
    }

    @Test
    fun permanentDeletePreventsARecreatedPathFromInheritingDirectoryMetadata() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val entry = environment.repository.moveToTrash(
            folder,
            Instant.parse("2026-07-01T00:00:00Z"),
            "deleted-directory",
        )

        assertTrue(
            environment.repository.deletePermanently(entry.stableId, confirmed = true) is TrashDeleteResult.Success,
        )
        environment.paths.root.resolve("course").mkdirs()
        val restarted = DirectoryMetadataRepository(environment.paths)
        restarted.restoreFromTrash(entry.stableId, "course")

        assertFalse("course" in restarted.metadata.value)
    }

    @Test
    fun retryingPermanentDeleteReconcilesMetadataAfterTheFirstWriteFailed() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val entry = environment.repository.moveToTrash(folder, stableId = "retry-delete-directory")
        val blocker = blockDirectoryMetadataWrite(environment.paths)

        val first = environment.repository.deletePermanently(entry.stableId, confirmed = true)
        unblockDirectoryMetadataWrite(blocker)
        val retry = environment.repository.deletePermanently(entry.stableId, confirmed = true)

        assertTrue(first is TrashDeleteResult.Failure)
        assertTrue(retry is TrashDeleteResult.NotFound)
        assertFalse(environment.paths.trash.resolve(entry.stableId).exists())
        environment.paths.root.resolve("course").mkdirs()
        val restarted = DirectoryMetadataRepository(environment.paths)
        restarted.restoreFromTrash(entry.stableId, "course")
        assertFalse("course" in restarted.metadata.value)
    }

    @Test
    fun emptyTrashClearsEveryHeldDirectoryMetadataBucket() = runBlocking {
        val environment = environment()
        val first = environment.paths.root.resolve("first").apply { mkdirs() }
        val second = environment.paths.root.resolve("second").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("first", "second"), true)
        environment.repository.moveToTrash(first, stableId = "first-directory")
        environment.repository.moveToTrash(second, stableId = "second-directory")

        assertTrue(environment.repository.emptyTrash(confirmed = true) is TrashDeleteResult.Success)
        environment.paths.root.resolve("first").mkdirs()
        environment.paths.root.resolve("second").mkdirs()
        val restarted = DirectoryMetadataRepository(environment.paths)
        restarted.restoreFromTrash("first-directory", "first")
        restarted.restoreFromTrash("second-directory", "second")

        assertTrue(restarted.metadata.value.isEmpty())
    }

    @Test
    fun retryingEmptyTrashReconcilesMetadataEvenWhenTheTrashIsAlreadyEmpty() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setTags(setOf("course"), setOf("old-tag"))
        val entry = environment.repository.moveToTrash(folder, stableId = "retry-empty-directory")
        val blocker = blockDirectoryMetadataWrite(environment.paths)

        val first = environment.repository.emptyTrash(confirmed = true)
        unblockDirectoryMetadataWrite(blocker)
        val retry = environment.repository.emptyTrash(confirmed = true)

        assertTrue(first is TrashDeleteResult.Failure)
        assertEquals(TrashDeleteResult.Success(emptySet()), retry)
        assertFalse(environment.paths.trash.resolve(entry.stableId).exists())
        environment.paths.root.resolve("course").mkdirs()
        val restarted = DirectoryMetadataRepository(environment.paths)
        restarted.restoreFromTrash(entry.stableId, "course")
        assertFalse("course" in restarted.metadata.value)
    }

    @Test
    fun expiredPurgeClearsHeldDirectoryMetadata() = runBlocking {
        val environment = environment()
        val now = Instant.parse("2026-08-01T00:00:00Z")
        val folder = environment.paths.root.resolve("old-course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("old-course"), true)
        environment.repository.moveToTrash(
            folder,
            now.minus(Duration.ofDays(31)),
            "expired-directory",
        )

        assertTrue(environment.repository.purgeExpired(now, confirmed = true) is TrashDeleteResult.Success)
        environment.paths.root.resolve("old-course").mkdirs()
        val restarted = DirectoryMetadataRepository(environment.paths)
        restarted.restoreFromTrash("expired-directory", "old-course")

        assertTrue(restarted.metadata.value.isEmpty())
    }

    @Test
    fun listAfterFailedPurgeReconcilesRestartedMetadataAndIgnoresInvalidTrashChildren() = runBlocking {
        val environment = environment()
        val now = Instant.parse("2026-08-01T00:00:00Z")
        val folder = environment.paths.root.resolve("old-course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("old-course"), true)
        val entry = environment.repository.moveToTrash(
            folder,
            now.minus(Duration.ofDays(31)),
            "retry-purge-directory",
        )
        val temporaryFolder = environment.paths.root.resolve("temporary-source").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("temporary-source"), true)
        val temporaryEntry = environment.repository.moveToTrash(
            temporaryFolder,
            now,
            "leftover.monote-tmp",
        )
        val blocker = blockDirectoryMetadataWrite(environment.paths)

        val first = environment.repository.purgeExpired(now, confirmed = true)
        unblockDirectoryMetadataWrite(blocker)
        val temporaryRoot = environment.paths.trash.resolve(temporaryEntry.stableId).toPath()
        Files.walk(temporaryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).filter { it != temporaryRoot }.forEach(Files::deleteIfExists)
        }
        environment.paths.trash.resolve("invalid child").mkdirs()
        val restartedMetadata = DirectoryMetadataRepository(environment.paths)
        val restartedTrash = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = restartedMetadata,
        )

        val entries = restartedTrash.listEntries()

        assertTrue(first is TrashDeleteResult.Failure)
        assertTrue(entries.isEmpty())
        environment.paths.root.resolve("old-course").mkdirs()
        environment.paths.root.resolve("temporary-source").mkdirs()
        restartedMetadata.restoreFromTrash(entry.stableId, "old-course")
        restartedMetadata.restoreFromTrash(temporaryEntry.stableId, "temporary-source")
        assertFalse("old-course" in restartedMetadata.metadata.value)
        assertFalse("temporary-source" in restartedMetadata.metadata.value)
    }

    @Test
    fun corruptValidNamedTrashDirectoryDoesNotProtectOrphanDirectoryMetadata() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setTags(setOf("course"), setOf("orphaned"))
        val entry = environment.repository.moveToTrash(folder, stableId = "corrupt-valid-id")
        val entryRoot = environment.paths.trash.resolve(entry.stableId).toPath()
        Files.walk(entryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).filter { it != entryRoot }.forEach(Files::deleteIfExists)
        }

        val listed = environment.repository.listEntries()

        assertTrue(listed.isEmpty())
        environment.paths.root.resolve("course").mkdirs()
        environment.directoryMetadata.restoreFromTrash(entry.stableId, "course")
        assertFalse("course" in environment.directoryMetadata.metadata.value)
    }

    @Test
    fun startupReconciliationClearsOrphanMetadataWithoutListingTrash() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val entry = environment.repository.moveToTrash(folder, stableId = "startup-orphan-id")
        val entryRoot = environment.paths.trash.resolve(entry.stableId).toPath()
        Files.walk(entryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        val restartedMetadata = DirectoryMetadataRepository(environment.paths)
        val restartedTrash = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = restartedMetadata,
        )

        restartedTrash.reconcileStartup()

        environment.paths.root.resolve("course").mkdirs()
        restartedMetadata.restoreFromTrash(entry.stableId, "course")
        assertFalse("course" in restartedMetadata.metadata.value)
    }

    @Test
    fun startupReconciliationCanRetryAfterAtomicMetadataWriteFailure() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val entry = environment.repository.moveToTrash(folder, stableId = "startup-retry-id")
        val entryRoot = environment.paths.trash.resolve(entry.stableId).toPath()
        Files.walk(entryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        val restartedMetadata = DirectoryMetadataRepository(environment.paths)
        val restartedTrash = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = restartedMetadata,
        )
        val blocker = blockDirectoryMetadataWrite(environment.paths)

        val first = runCatching { restartedTrash.reconcileStartup() }
        unblockDirectoryMetadataWrite(blocker)
        restartedTrash.reconcileStartup()

        assertTrue(first.isFailure)
        environment.paths.root.resolve("course").mkdirs()
        restartedMetadata.restoreFromTrash(entry.stableId, "course")
        assertFalse("course" in restartedMetadata.metadata.value)
    }

    @Test
    fun startupReconciliationCannotEraseMetadataFromACompetingTrashMove() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val scanned = CompletableDeferred<Unit>()
        val releaseReconciliation = CompletableDeferred<Unit>()
        val repository = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
            beforeReconcileCommit = {
                scanned.complete(Unit)
                releaseReconciliation.await()
            },
        )
        val reconciliation = async { repository.reconcileStartup() }
        scanned.await()
        val move = async { repository.moveToTrash(folder, stableId = "concurrent-move-id") }

        val moveBeforeRelease = withTimeoutOrNull(1_000) { move.await() }
        releaseReconciliation.complete(Unit)
        reconciliation.await()
        val entry = moveBeforeRelease ?: move.await()

        assertEquals(null, moveBeforeRelease)
        environment.paths.root.resolve("course").mkdirs()
        environment.directoryMetadata.restoreFromTrash(entry.stableId, "course")
        assertTrue(environment.directoryMetadata.metadata.value.getValue("course").favorite)
    }

    @Test
    fun startupReconciliationSerializesWithRestoreAndPreservesDirectoryMetadata() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val entry = environment.repository.moveToTrash(folder, stableId = "concurrent-restore-id")
        val scanned = CompletableDeferred<Unit>()
        val releaseReconciliation = CompletableDeferred<Unit>()
        val repository = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
            beforeReconcileCommit = {
                scanned.complete(Unit)
                releaseReconciliation.await()
            },
        )
        val reconciliation = async { repository.reconcileStartup() }
        scanned.await()
        val restore = async { repository.restore(entry) }

        val restoreBeforeRelease = withTimeoutOrNull(1_000) { restore.await() }
        releaseReconciliation.complete(Unit)
        reconciliation.await()
        val result = restoreBeforeRelease ?: restore.await()

        assertEquals(null, restoreBeforeRelease)
        assertTrue(result is LibraryResult.Success)
        assertTrue(folder.isDirectory)
        assertTrue(environment.directoryMetadata.metadata.value.getValue("course").favorite)
    }

    @Test
    fun startupReconciliationSerializesWithPermanentDeleteAndClearsDirectoryMetadata() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        val entry = environment.repository.moveToTrash(folder, stableId = "concurrent-delete-id")
        val scanned = CompletableDeferred<Unit>()
        val releaseReconciliation = CompletableDeferred<Unit>()
        val repository = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
            beforeReconcileCommit = {
                scanned.complete(Unit)
                releaseReconciliation.await()
            },
        )
        val reconciliation = async { repository.reconcileStartup() }
        scanned.await()
        val deletion = async { repository.deletePermanently(entry.stableId, confirmed = true) }

        val deletionBeforeRelease = withTimeoutOrNull(1_000) { deletion.await() }
        releaseReconciliation.complete(Unit)
        reconciliation.await()
        val result = deletionBeforeRelease ?: deletion.await()

        assertEquals(null, deletionBeforeRelease)
        assertTrue(result is TrashDeleteResult.Success)
        assertFalse(environment.paths.trash.resolve(entry.stableId).exists())
        folder.mkdirs()
        environment.directoryMetadata.restoreFromTrash(entry.stableId, "course")
        assertFalse("course" in environment.directoryMetadata.metadata.value)
    }

    @Test
    fun restoreCatalogFailureKeepsSidecarJournalAndSchedulesRescan() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("failed.md").apply { writeText("content") }
        environment.catalog.upsert(
            DocumentEntity("failed-id", "failed.md", "failed", source.lastModified(), source.length(), "hash", lastOpenedAt = 44L),
            source.readText(),
            setOf("retry-tag"),
        )
        val entry = environment.repository.moveToTrash(
            source,
            Instant.parse("2026-07-01T00:00:00Z"),
        )
        environment.dao.failMutations = true

        val result = environment.repository.restore(entry) as LibraryResult.Success

        assertTrue(source.exists())
        assertFalse(result.catalogSynchronized)
        assertTrue(entry.metadataFile.exists())
        assertEquals(1, environment.rescanRequests)
        val pendingJson = Json.parseToJsonElement(entry.metadataFile.readText()).jsonObject
        assertEquals("PENDING_RESTORE", pendingJson.getValue("state").jsonPrimitive.content)
        assertEquals("failed.md", pendingJson.getValue("pendingTargetRelativePath").jsonPrimitive.content)
        assertEquals(1, environment.trustDirectory.listFiles().orEmpty().size)

        environment.dao.failMutations = false
        environment.catalog.upsert(
            DocumentEntity(
                "placeholder-id",
                "failed.md",
                "placeholder",
                source.lastModified(),
                source.length(),
                "placeholder-hash",
                favorite = true,
                lastOpenedAt = 99L,
            ),
            "placeholder body token",
            setOf("placeholder-tag"),
        )
        val pending = environment.repository.listEntries().single()
        assertTrue(environment.repository.restore(pending) is LibraryResult.Success)

        val restored = requireNotNull(environment.catalog.getByPath("failed.md"))
        assertEquals("failed-id", restored.id)
        assertEquals("placeholder", restored.title)
        assertTrue(restored.favorite)
        assertEquals(99L, restored.lastOpenedAt)
        assertEquals(setOf("placeholder-tag", "retry-tag"), environment.catalog.tags(restored.id))
        assertFalse("placeholder document must be deleted", environment.dao.documents.containsKey("placeholder-id"))
        assertFalse("placeholder FTS must be deleted", environment.dao.ftsEntries.containsKey("placeholder-id"))
        assertFalse("placeholder tags must be deleted", environment.dao.documentTags.containsKey("placeholder-id"))
        assertFalse(entry.metadataFile.exists())
        assertTrue(environment.trustDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun restoreCatalogCancellationKeepsSidecarJournalAndSchedulesRescan() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("cancel-restore.md").apply { writeText("content") }
        environment.catalog.upsert(
            DocumentEntity("cancel-id", "cancel-restore.md", "cancel", source.lastModified(), source.length(), "hash", favorite = true),
            source.readText(),
            setOf("cancel-tag"),
        )
        val entry = environment.repository.moveToTrash(
            source,
            Instant.parse("2026-07-01T00:00:00Z"),
        )
        environment.dao.mutationFailure = CancellationException("cancel restore catalog")

        try {
            environment.repository.restore(entry)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertTrue(source.exists())
        assertTrue(entry.metadataFile.exists())
        assertEquals(1, environment.rescanRequests)
        assertEquals(
            "PENDING_RESTORE",
            Json.parseToJsonElement(entry.metadataFile.readText()).jsonObject.getValue("state").jsonPrimitive.content,
        )

        environment.dao.mutationFailure = null
        val pending = environment.repository.listEntries().single()
        assertTrue(environment.repository.restore(pending) is LibraryResult.Success)

        val restored = requireNotNull(environment.catalog.getByPath("cancel-restore.md"))
        assertEquals("cancel-id", restored.id)
        assertTrue(restored.favorite)
        assertEquals(setOf("cancel-tag"), environment.catalog.tags(restored.id))
        assertFalse(entry.metadataFile.exists())
    }

    @Test
    fun changedPendingRestoreIsNotExposedForAnUnsafeCatalogRetry() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("changed.md").apply { writeText("original") }
        val entry = environment.repository.moveToTrash(
            source,
            Instant.parse("2026-07-01T00:00:00Z"),
            "changed-entry",
        )
        environment.dao.failMutations = true

        val result = environment.repository.restore(entry) as LibraryResult.Success
        source.writeText("changed outside the retry journal")

        assertFalse(result.catalogSynchronized)
        assertTrue(entry.metadataFile.exists())
        assertTrue(environment.repository.listEntries().isEmpty())
    }

    @Test
    fun forgedPendingSidecarWithMatchingFingerprintCannotReplaceOrModifyAnActiveRecord() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("active-pending.md").apply { writeText("same content") }
        environment.catalog.upsert(
            DocumentEntity(
                "active-id",
                "active-pending.md",
                "Active title",
                source.lastModified(),
                source.length(),
                DocumentFingerprint.sha256File(source),
                lastOpenedAt = 7L,
            ),
            source.readText(),
            setOf("active-tag"),
        )
        val entryRoot = environment.paths.trash.resolve("forged-pending").apply { mkdirs() }
        entryRoot.resolve("entry.json").writeText(
            """
            {
              "stableId": "forged-pending",
              "originalRelativePath": "active-pending.md",
              "deletedAt": "2026-07-01T00:00:00Z",
              "state": "PENDING_RESTORE",
              "pendingTargetRelativePath": "active-pending.md",
              "documents": [
                {
                  "id": "forged-id",
                  "originalRelativePath": "active-pending.md",
                  "title": "Forged title",
                  "favorite": true,
                  "tags": ["forged-tag"],
                  "lastOpenedAt": 999,
                  "size": ${source.length()},
                  "sha256": "${DocumentFingerprint.sha256File(source)}"
                }
              ]
            }
            """.trimIndent(),
        )

        val forged = environment.repository.listEntries().single()
        assertTrue(environment.repository.restore(forged) is LibraryResult.Success)

        val active = requireNotNull(environment.catalog.getByPath("active-pending.md"))
        assertEquals("active-id", active.id)
        assertEquals("Active title", active.title)
        assertFalse(active.favorite)
        assertEquals(7L, active.lastOpenedAt)
        assertEquals(setOf("active-tag"), environment.catalog.tags(active.id))
        assertFalse(environment.dao.documents.containsKey("forged-id"))
    }

    @Test
    fun catalogAllFailureAbortsBeforeCreatingTrashEntryOrMovingSource() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("all-failure.md").apply { writeText("content") }
        environment.dao.allFailure = java.io.IOException("catalog all failed")

        try {
            environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"))
            throw AssertionError("Expected catalog read failure")
        } catch (_: IllegalStateException) {
        }

        assertTrue(source.exists())
        assertTrue(environment.repository.listEntries().isEmpty())
        assertTrue(environment.paths.trash.listFiles().orEmpty().isEmpty())
        assertEquals(1, environment.rescanRequests)
    }

    @Test
    fun catalogTagsFailureAbortsBeforeCreatingTrashEntryOrMovingSource() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("tags-failure.md").apply { writeText("content") }
        environment.catalog.upsert(
            DocumentEntity("tags-id", "tags-failure.md", "tags", source.lastModified(), source.length(), "hash"),
            source.readText(),
            setOf("tag"),
        )
        environment.dao.tagsFailure = java.io.IOException("catalog tags failed")

        try {
            environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"))
            throw AssertionError("Expected catalog read failure")
        } catch (_: IllegalStateException) {
        }

        assertTrue(source.exists())
        assertTrue(environment.repository.listEntries().isEmpty())
        assertTrue(environment.paths.trash.listFiles().orEmpty().isEmpty())
        assertEquals(1, environment.rescanRequests)
    }

    @Test
    fun catalogReadCancellationPropagatesBeforeCreatingTrashEntryOrMovingSource() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("read-cancelled.md").apply { writeText("content") }
        environment.dao.allFailure = CancellationException("cancel catalog read")

        try {
            environment.repository.moveToTrash(source, Instant.parse("2026-07-01T00:00:00Z"))
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertTrue(source.exists())
        assertTrue(environment.repository.listEntries().isEmpty())
        assertTrue(environment.paths.trash.listFiles().orEmpty().isEmpty())
        assertEquals(0, environment.rescanRequests)
    }

    @Test
    fun legacySidecarStableIdCannotReplaceAnActiveDocumentAtAnotherPath() = runBlocking {
        val environment = environment()
        val active = environment.paths.root.resolve("active.md").apply { writeText("active") }
        environment.catalog.upsert(
            DocumentEntity("shared-id", "active.md", "active", active.lastModified(), active.length(), "hash", favorite = true),
            active.readText(),
            setOf("active-tag"),
        )
        val source = environment.paths.root.resolve("restored.md").apply { writeText("restored") }
        val entry = environment.repository.moveToTrash(
            source,
            Instant.parse("2026-07-01T00:00:00Z"),
            "shared-id",
        )
        entry.metadataFile.writeText(
            """
            {
              "stableId": "shared-id",
              "originalRelativePath": "restored.md",
              "deletedAt": "2026-07-01T00:00:00Z",
              "documents": [
                {
                  "id": "shared-id",
                  "originalRelativePath": "restored.md",
                  "title": "restored",
                  "favorite": false,
                  "tags": [],
                  "lastOpenedAt": null
                }
              ]
            }
            """.trimIndent(),
        )

        assertTrue(environment.repository.restore(entry) is LibraryResult.Success)

        val stillActive = requireNotNull(environment.catalog.getByPath("active.md"))
        val restored = requireNotNull(environment.catalog.getByPath("restored.md"))
        assertEquals("shared-id", stillActive.id)
        assertTrue(stillActive.favorite)
        assertEquals(setOf("active-tag"), environment.catalog.tags(stillActive.id))
        assertTrue(restored.id != "shared-id")
    }

    @Test
    fun legacySidecarWithoutDocumentsRestoresWithANewDocumentId() = runBlocking {
        val environment = environment()
        val entryRoot = environment.paths.trash.resolve("legacy-entry").apply { mkdirs() }
        val trashed = entryRoot.resolve("content/legacy.md")
        requireNotNull(trashed.parentFile).mkdirs()
        trashed.writeText("legacy")
        entryRoot.resolve("entry.json").writeText(
            """
            {
              "stableId": "legacy-entry",
              "originalRelativePath": "legacy.md",
              "deletedAt": "2026-07-01T00:00:00Z"
            }
            """.trimIndent(),
        )
        val entry = environment.repository.listEntries().single()

        assertTrue(environment.repository.restore(entry) is LibraryResult.Success)

        val restored = requireNotNull(environment.catalog.getByPath("legacy.md"))
        assertTrue(restored.id != "legacy-entry")
    }

    @Test
    fun pendingRestoreIntentSurvivesCrashBeforeMoveAndKeepsDocumentIdentity() = runBlocking {
        val environment = environment()
        val source = environment.paths.root.resolve("before-move.md").apply { writeText("# before") }
        environment.catalog.upsert(
            DocumentEntity("before-id", "before-move.md", "before", source.lastModified(), source.length(), "hash", favorite = true),
            source.readText(),
            setOf("crash"),
        )
        val entry = environment.repository.moveToTrash(source, stableId = "before-move-entry")
        val crashing = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
            afterPendingRestoreWritten = { throw SimulatedCrash() },
        )

        try {
            crashing.restore(entry)
            throw AssertionError("Expected simulated process crash")
        } catch (_: SimulatedCrash) {
        }

        assertFalse(source.exists())
        assertTrue(entry.trashedFile.exists())
        val restarted = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
        )
        assertTrue(restarted.restore(restarted.listEntries().single()) is LibraryResult.Success)
        val restored = requireNotNull(environment.catalog.getByPath("before-move.md"))
        assertEquals("before-id", restored.id)
        assertTrue(restored.favorite)
        assertEquals(setOf("crash"), environment.catalog.tags(restored.id))
    }

    @Test
    fun pendingRestoreIntentSurvivesCrashAfterMoveAndRestoresDirectoryMetadata() = runBlocking {
        val environment = environment()
        val folder = environment.paths.root.resolve("course").apply { mkdirs() }
        folder.resolve("note.md").writeText("# course")
        environment.directoryMetadata.setFavorite(setOf("course"), true)
        environment.directoryMetadata.setTags(setOf("course"), setOf("study"))
        val entry = environment.repository.moveToTrash(folder, stableId = "after-move-entry")
        val crashing = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
            afterRestoreMove = { throw SimulatedCrash() },
        )

        try {
            crashing.restore(entry)
            throw AssertionError("Expected simulated process crash")
        } catch (_: SimulatedCrash) {
        }

        assertTrue(folder.isDirectory)
        assertFalse(entry.trashedFile.exists())
        val restarted = TrashRepository(
            environment.paths,
            environment.catalog,
            PendingRestoreTrustStore(environment.trustDirectory),
            directoryMetadataRepository = environment.directoryMetadata,
        )
        assertTrue(restarted.restore(restarted.listEntries().single()) is LibraryResult.Success)
        val metadata = environment.directoryMetadata.metadata.value.getValue("course")
        assertTrue(metadata.favorite)
        assertEquals(setOf("study"), metadata.tags)
    }

    private fun environment(): TestEnvironment {
        val root = temporaryDirectory()
        val paths = LibraryPaths(root.resolve("library")).ensureCreated()
        val trustDirectory = root.resolve("app-private/pending-restore").toFile()
        val dao = TestCatalogDao()
        val catalog = CatalogRepository(dao, CatalogMirror(paths.system.toPath()))
        val directoryMetadata = DirectoryMetadataRepository(paths)
        var rescanRequests = 0
        val repository = TrashRepository(
            paths,
            catalog,
            PendingRestoreTrustStore(trustDirectory),
            directoryMetadataRepository = directoryMetadata,
        ) { rescanRequests++ }
        return TestEnvironment(paths, trustDirectory, dao, catalog, directoryMetadata, repository) { rescanRequests }
    }

    private fun temporaryDirectory(): Path =
        Files.createTempDirectory("monote-trash-").also(temporaryRoots::add)

    private fun blockDirectoryMetadataWrite(
        paths: app.monote.mobile.core.storage.LibraryDirectories,
    ): java.io.File = paths.system.resolve("directory-metadata.json.monote-tmp").apply {
        mkdirs()
        resolve("blocker").writeText("blocked")
    }

    private fun unblockDirectoryMetadataWrite(blocker: java.io.File) {
        blocker.resolve("blocker").delete()
        blocker.delete()
    }

    private class TestEnvironment(
        val paths: app.monote.mobile.core.storage.LibraryDirectories,
        val trustDirectory: java.io.File,
        val dao: TestCatalogDao,
        val catalog: CatalogRepository,
        val directoryMetadata: DirectoryMetadataRepository,
        val repository: TrashRepository,
        private val rescanCount: () -> Int,
    ) {
        val rescanRequests: Int get() = rescanCount()
    }

    private class SimulatedCrash : Error("simulated process termination")
}
