package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File

data class DocumentSession(
    val id: String,
    val file: File,
    val text: String,
    val revision: Long,
    val baseline: DocumentFingerprint,
    val saveStatus: SaveStatus,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val autoSaveEnabled: Boolean,
    val externalConflict: ExternalDocumentVersion? = null,
    val recoveryError: String? = null,
) {
    init {
        requireSafeDocumentId(id)
        require(revision >= 0) { "Document revision cannot be negative" }
    }

    val contentSha256: String = DocumentFingerprint.sha256(text)
    val isDirty: Boolean get() = contentSha256 != baseline.sha256
    val autoSaveAllowed: Boolean
        get() = autoSaveEnabled &&
            externalConflict == null &&
            saveStatus != SaveStatus.ReadOnly
}

enum class SaveStatus {
    Saved,
    Unsaved,
    Saving,
    ReadOnly,
    SaveFailed,
    Conflict,
}

internal fun requireSafeDocumentId(id: String): String {
    require(SAFE_DOCUMENT_ID.matches(id)) { "Invalid stable document ID: $id" }
    return id
}

private val SAFE_DOCUMENT_ID = Regex("[A-Za-z0-9._-]{1,128}")
