package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSessionReducerTest {
    private val reducer = DocumentSessionReducer()
    private val baseline = fingerprint("old", modifiedAt = 10)
    private val openState = DocumentSession(
        id = "document-1",
        file = File("note.md"),
        text = "old",
        revision = 0,
        baseline = baseline,
        saveStatus = SaveStatus.Saved,
        canUndo = false,
        canRedo = false,
        autoSaveEnabled = true,
    )

    @Test
    fun editMarksDirtyAndSuccessfulSaveAdvancesBaseline() {
        val edited = reducer.reduce(
            openState,
            DocumentEvent.Changed(1, "new", canUndo = true, canRedo = false),
        )

        assertTrue(edited.isDirty)
        assertEquals(SaveStatus.Unsaved, edited.saveStatus)
        assertTrue(edited.canUndo)

        val saved = reducer.reduce(edited, DocumentEvent.Saved(fingerprint("new", 20)))

        assertFalse(saved.isDirty)
        assertEquals(SaveStatus.Saved, saved.saveStatus)
        assertEquals(DocumentFingerprint.sha256("new"), saved.baseline.sha256)
    }

    @Test
    fun externalChangePausesAutoSaveWithoutDiscardingMine() {
        val dirty = reducer.reduce(
            openState,
            DocumentEvent.Changed(1, "mine", canUndo = true, canRedo = false),
        )
        val external = ExternalDocumentVersion.Present(
            fingerprint = fingerprint("external", 30),
            text = "external",
        )

        val conflicted = reducer.reduce(dirty, DocumentEvent.ExternalConflict(external))

        assertEquals("mine", conflicted.text)
        assertEquals(SaveStatus.Conflict, conflicted.saveStatus)
        assertFalse(conflicted.autoSaveAllowed)
        assertEquals(external, conflicted.externalConflict)
    }

    @Test
    fun staleSaveAdvancesFileBaselineButKeepsNewerEditDirty() {
        val first = reducer.reduce(
            openState,
            DocumentEvent.Changed(1, "first", canUndo = true, canRedo = false),
        )
        val second = reducer.reduce(
            first,
            DocumentEvent.Changed(2, "second", canUndo = true, canRedo = false),
        )

        val afterOldSave = reducer.reduce(
            second,
            DocumentEvent.Saved(fingerprint("first", 20)),
        )

        assertEquals("second", afterOldSave.text)
        assertTrue(afterOldSave.isDirty)
        assertEquals(SaveStatus.Unsaved, afterOldSave.saveStatus)
        assertEquals(DocumentFingerprint.sha256("first"), afterOldSave.baseline.sha256)
    }

    @Test
    fun loadingExternalVersionClearsConflictAndCreatesCleanState() {
        val externalFingerprint = fingerprint("external", 30)
        val conflicted = openState.copy(
            text = "mine",
            saveStatus = SaveStatus.Conflict,
            externalConflict = ExternalDocumentVersion.Present(externalFingerprint, "external"),
        )

        val loaded = reducer.reduce(
            conflicted,
            DocumentEvent.LoadedExternal(
                revision = 4,
                text = "external",
                fingerprint = externalFingerprint,
            ),
        )

        assertFalse(loaded.isDirty)
        assertEquals(SaveStatus.Saved, loaded.saveStatus)
        assertEquals(null, loaded.externalConflict)
        assertEquals(4, loaded.revision)
    }

    private fun fingerprint(text: String, modifiedAt: Long): DocumentFingerprint =
        DocumentFingerprint(
            size = text.toByteArray().size.toLong(),
            modifiedAt = modifiedAt,
            sha256 = DocumentFingerprint.sha256(text),
        )
}
