package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.LibraryPaths
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStateRepositoryTest {
    @Test
    fun persistsPositionAndRecoversFromCorruptPrimary() = runTest {
        val fixture = fixture()
        val first = ReadingDocumentState(
            documentId = "document-1",
            position = ReadingPosition(editorLine = 12, previewHeadingId = "heading-one", previewProgress = 0.4f),
            updatedAt = 10,
        )
        val second = first.copy(position = first.position.copy(editorLine = 24), updatedAt = 20)

        fixture.repository.replace(first)
        fixture.repository.replace(second)
        fixture.paths.system.resolve("reading-state.json").writeText("not json")

        val recovered = ReadingStateRepository(fixture.paths).get("document-1")
        assertEquals(24, recovered?.position?.editorLine)
        assertEquals("heading-one", recovered?.position?.previewHeadingId)
    }

    @Test
    fun migratesLegacyIdWithoutOverwritingNewerStableState() = runTest {
        val fixture = fixture()
        fixture.repository.replace(ReadingDocumentState("path-legacy", updatedAt = 10))

        val migrated = fixture.repository.migrate("path-legacy", "stable-1")
        assertEquals("stable-1", migrated?.documentId)
        assertNull(fixture.repository.get("path-legacy"))

        fixture.repository.replace(ReadingDocumentState("path-second", updatedAt = 20))
        fixture.repository.replace(ReadingDocumentState("stable-2", updatedAt = 30))
        assertEquals(30L, fixture.repository.migrate("path-second", "stable-2")?.updatedAt)
        assertNull(fixture.repository.get("path-second"))
    }

    @Test
    fun reconcilesSameTitleBookmarkByNearestSourceLineAndKeepsMissingBookmark() = runTest {
        val saved = listOf(
            HeadingBookmark("old-a", "重复标题", 2, 10),
            HeadingBookmark("gone", "已删除", 2, 30),
        )
        val current = listOf(
            DocumentHeading("new-a", "重复标题", 2, 8),
            DocumentHeading("new-b", "重复标题", 2, 80),
        )

        val resolved = reconcileHeadingBookmarks(saved, current)

        assertEquals("new-a", resolved[0].bookmark.id)
        assertTrue(resolved[0].available)
        assertEquals("gone", resolved[1].bookmark.id)
        assertFalse(resolved[1].available)
    }

    @Test
    fun capacityPrefersEvictingOldestStateWithoutBookmarks() = runTest {
        val fixture = fixture(maxDocuments = 2)
        fixture.repository.replace(
            ReadingDocumentState(
                "bookmarked",
                bookmarks = listOf(HeadingBookmark("h", "保留", 1, 1)),
                updatedAt = 1,
            ),
        )
        fixture.repository.replace(ReadingDocumentState("old-unbookmarked", updatedAt = 2))
        fixture.repository.replace(ReadingDocumentState("new", updatedAt = 3))

        assertTrue(fixture.repository.get("bookmarked") != null)
        assertNull(fixture.repository.get("old-unbookmarked"))
        assertTrue(fixture.repository.get("new") != null)
    }

    @Test
    fun permanentDeletionRemovesEveryRequestedDocumentState() = runTest {
        val fixture = fixture()
        fixture.repository.replace(ReadingDocumentState("one", updatedAt = 1))
        fixture.repository.replace(ReadingDocumentState("two", updatedAt = 2))

        fixture.repository.remove(setOf("one", "two"))

        assertNull(fixture.repository.get("one"))
        assertNull(fixture.repository.get("two"))
    }

    private fun fixture(maxDocuments: Int = 1_000): Fixture {
        val root = Files.createTempDirectory("monote-reading-state")
        val paths = LibraryPaths(root).ensureCreated()
        return Fixture(paths, ReadingStateRepository(paths, maxDocuments = maxDocuments))
    }

    private data class Fixture(
        val paths: app.monote.mobile.core.storage.LibraryDirectories,
        val repository: ReadingStateRepository,
    )
}
