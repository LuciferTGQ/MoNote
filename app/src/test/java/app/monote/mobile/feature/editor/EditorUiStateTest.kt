package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorUiStateTest {
    @Test
    fun dirtyDocumentRequiresAnExplicitExitChoice() {
        val state = EditorUiState(session = session("changed"))

        val requested = state.requestExit()

        assertTrue(requested.pendingExit)
        assertFalse(requested.canExitImmediately)
    }

    @Test
    fun savedDocumentCanExitImmediately() {
        val state = EditorUiState(session = session("saved", baselineText = "saved"))

        val requested = state.requestExit()

        assertFalse(requested.pendingExit)
        assertTrue(requested.canExitImmediately)
        assertEquals(EditorTab.Edit, requested.selectedTab)
    }

    private fun session(text: String, baselineText: String = "original") = DocumentSession(
        id = "document-1",
        file = File("note.md"),
        text = text,
        revision = 1,
        baseline = DocumentFingerprint(
            size = baselineText.toByteArray().size.toLong(),
            modifiedAt = 1,
            sha256 = DocumentFingerprint.sha256(baselineText),
        ),
        saveStatus = if (text == baselineText) SaveStatus.Saved else SaveStatus.Unsaved,
        canUndo = text != baselineText,
        canRedo = false,
        autoSaveEnabled = true,
    )
}
