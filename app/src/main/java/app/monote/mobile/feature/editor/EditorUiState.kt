package app.monote.mobile.feature.editor

import app.monote.mobile.feature.editor.bridge.EditorMode

enum class EditorTab { Edit, Preview }
enum class OutlineTab { Outline, Bookmarks }

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
    val outline: List<DocumentHeading> = emptyList(),
    val bookmarks: List<ResolvedHeadingBookmark> = emptyList(),
    val activeHeadingId: String? = null,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val searchCurrent: Int = 0,
    val searchTotal: Int = 0,
    val outlineVisible: Boolean = false,
    val outlineTab: OutlineTab = OutlineTab.Outline,
    val readingMode: Boolean = false,
    val readingControlsVisible: Boolean = true,
) {
    fun requestExit(): EditorUiState {
        val safeToExit = session?.let { !it.isDirty && it.externalConflict == null } ?: true
        return copy(
            pendingExit = !safeToExit,
            canExitImmediately = safeToExit,
        )
    }

    fun editorMode(isLandscape: Boolean): EditorMode = when {
        readingMode -> EditorMode.READ
        isLandscape -> EditorMode.SPLIT
        selectedTab == EditorTab.Preview -> EditorMode.PREVIEW
        else -> EditorMode.EDIT
    }
}
