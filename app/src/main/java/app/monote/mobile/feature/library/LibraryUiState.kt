package app.monote.mobile.feature.library

import java.io.File

data class LibraryItem(
    val id: String,
    val file: File,
    val relativePath: String,
    val displayName: String,
    val isFolder: Boolean,
    val favorite: Boolean = false,
    val tags: Set<String> = emptySet(),
    val modifiedAt: Long = file.lastModified(),
)

data class LibraryUiState(
    val currentFolder: File,
    val recent: List<LibraryItem> = emptyList(),
    val items: List<LibraryItem> = emptyList(),
    val query: String = "",
    val selectedIds: Set<String> = emptySet(),
    val activeTags: Set<String> = emptySet(),
    val moveRecoveryRecords: List<MoveRecoveryRecord> = emptyList(),
    val scanning: Boolean = false,
    val canNavigateUp: Boolean = false,
    val scanWarnings: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
