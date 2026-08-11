package app.monote.mobile.feature.editor

import app.monote.mobile.feature.editor.bridge.EditorMode

enum class EditorTab { Edit, Preview }

data class EditorUiState(
    val session: DocumentSession? = null,
    val selectedTab: EditorTab = EditorTab.Edit,
    val orientationPreference: OrientationPreference = OrientationPreference.FollowSystem,
    val splitRatio: Float = 0.5f,
    val pendingExit: Boolean = false,
    val canExitImmediately: Boolean = false,
    val recoveryCandidate: RecoveryDraft? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val renderWarning: String? = null,
    val externalLink: String? = null,
) {
    fun requestExit(): EditorUiState {
        val safeToExit = session?.let { !it.isDirty && it.externalConflict == null } ?: true
        return copy(
            pendingExit = !safeToExit,
            canExitImmediately = safeToExit,
        )
    }

    fun editorMode(isLandscape: Boolean): EditorMode = when {
        isLandscape -> EditorMode.SPLIT
        selectedTab == EditorTab.Preview -> EditorMode.PREVIEW
        else -> EditorMode.EDIT
    }
}
