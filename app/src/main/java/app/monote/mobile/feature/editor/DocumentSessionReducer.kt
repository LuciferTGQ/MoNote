package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint

sealed interface DocumentEvent {
    data class Changed(
        val revision: Long,
        val text: String,
        val canUndo: Boolean,
        val canRedo: Boolean,
    ) : DocumentEvent

    data object SaveStarted : DocumentEvent
    data class Saved(val fingerprint: DocumentFingerprint) : DocumentEvent
    data class SaveFailed(val message: String) : DocumentEvent
    data object RecoverySaved : DocumentEvent
    data class RecoveryFailed(val message: String) : DocumentEvent
    data class ExternalConflict(val external: ExternalDocumentVersion) : DocumentEvent

    data class LoadedExternal(
        val revision: Long,
        val text: String,
        val fingerprint: DocumentFingerprint,
    ) : DocumentEvent

    data class ResolvedWithMine(val fingerprint: DocumentFingerprint) : DocumentEvent
    data class AutoSaveChanged(val enabled: Boolean) : DocumentEvent
    data object MarkReadOnly : DocumentEvent
}

class DocumentSessionReducer {
    fun reduce(state: DocumentSession, event: DocumentEvent): DocumentSession =
        when (event) {
            is DocumentEvent.Changed -> changed(state, event)
            DocumentEvent.SaveStarted -> if (state.isDirty && state.autoSaveAllowed) {
                state.copy(saveStatus = SaveStatus.Saving)
            } else {
                state
            }
            is DocumentEvent.Saved -> state.copy(
                baseline = event.fingerprint,
                saveStatus = when {
                    state.externalConflict != null -> SaveStatus.Conflict
                    state.contentSha256 == event.fingerprint.sha256 -> SaveStatus.Saved
                    else -> SaveStatus.Unsaved
                },
            )
            is DocumentEvent.SaveFailed -> state.copy(
                saveStatus = if (state.externalConflict == null) {
                    SaveStatus.SaveFailed
                } else {
                    SaveStatus.Conflict
                },
            )
            DocumentEvent.RecoverySaved -> state.copy(recoveryError = null)
            is DocumentEvent.RecoveryFailed -> state.copy(recoveryError = event.message)
            is DocumentEvent.ExternalConflict -> state.copy(
                saveStatus = SaveStatus.Conflict,
                externalConflict = event.external,
            )
            is DocumentEvent.LoadedExternal -> state.copy(
                text = event.text,
                revision = event.revision,
                baseline = event.fingerprint,
                saveStatus = SaveStatus.Saved,
                canUndo = false,
                canRedo = false,
                externalConflict = null,
            )
            is DocumentEvent.ResolvedWithMine -> state.copy(
                baseline = event.fingerprint,
                saveStatus = if (state.contentSha256 == event.fingerprint.sha256) {
                    SaveStatus.Saved
                } else {
                    SaveStatus.Unsaved
                },
                externalConflict = null,
            )
            is DocumentEvent.AutoSaveChanged -> state.copy(
                autoSaveEnabled = event.enabled,
                saveStatus = if (!event.enabled && state.saveStatus == SaveStatus.Saving) {
                    SaveStatus.Unsaved
                } else {
                    state.saveStatus
                },
            )
            DocumentEvent.MarkReadOnly -> state.copy(saveStatus = SaveStatus.ReadOnly)
        }

    private fun changed(
        state: DocumentSession,
        event: DocumentEvent.Changed,
    ): DocumentSession {
        if (event.revision <= state.revision) return state
        return state.copy(
            text = event.text,
            revision = event.revision,
            saveStatus = when {
                state.saveStatus == SaveStatus.ReadOnly -> SaveStatus.ReadOnly
                state.externalConflict != null -> SaveStatus.Conflict
                else -> SaveStatus.Unsaved
            },
            canUndo = event.canUndo,
            canRedo = event.canRedo,
        )
    }
}
