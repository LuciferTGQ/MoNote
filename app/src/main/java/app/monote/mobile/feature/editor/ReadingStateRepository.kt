package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.LibraryDirectories
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DocumentHeading(
    val id: String,
    val title: String,
    val level: Int,
    val sourceLine: Int,
)

@Serializable
data class HeadingBookmark(
    val id: String,
    val title: String,
    val level: Int,
    val sourceLine: Int,
)

data class ResolvedHeadingBookmark(
    val bookmark: HeadingBookmark,
    val available: Boolean,
)

@Serializable
data class ReadingPosition(
    val editorLine: Int = 1,
    val editorColumn: Int = 0,
    val editorProgress: Float = 0f,
    val previewHeadingId: String? = null,
    val previewProgress: Float = 0f,
)

@Serializable
data class ReadingDocumentState(
    val documentId: String,
    val position: ReadingPosition = ReadingPosition(),
    val bookmarks: List<HeadingBookmark> = emptyList(),
    val updatedAt: Long = 0,
)

class ReadingStateRepository(
    paths: LibraryDirectories,
    private val textStore: AtomicTextStore = AtomicTextStore(),
    private val maxDocuments: Int = MAX_DOCUMENTS,
) {
    private val primary = paths.system.resolve("reading-state.json")
    private val backup = paths.system.resolve("reading-state.json.bak")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private var states = read().documents.associateBy(ReadingDocumentState::documentId)

    init {
        require(maxDocuments > 0) { "Reading state capacity must be positive" }
    }

    suspend fun get(documentId: String): ReadingDocumentState? = mutex.withLock {
        requireDocumentId(documentId)
        states[documentId]
    }

    suspend fun replace(state: ReadingDocumentState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            validate(state)
            val next = states.toMutableMap().apply { put(state.documentId, state) }
            persist(prune(next))
        }
    }

    suspend fun migrate(legacyId: String, stableId: String): ReadingDocumentState? = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireDocumentId(legacyId)
            requireDocumentId(stableId)
            if (legacyId == stableId) return@withContext states[stableId]
            val legacy = states[legacyId] ?: return@withContext states[stableId]
            val stable = states[stableId]
            val selected = when {
                stable == null -> legacy.copy(documentId = stableId)
                stable.updatedAt >= legacy.updatedAt -> stable
                else -> legacy.copy(documentId = stableId)
            }
            val next = states.toMutableMap().apply {
                remove(legacyId)
                put(stableId, selected)
            }
            persist(prune(next))
            selected
        }
    }

    suspend fun remove(documentIds: Set<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            documentIds.forEach(::requireDocumentId)
            val next = states - documentIds
            if (next != states) persist(next)
        }
    }

    private fun prune(next: Map<String, ReadingDocumentState>): Map<String, ReadingDocumentState> {
        if (next.size <= maxDocuments) return next
        val removeCount = next.size - maxDocuments
        val evicted = next.values.sortedWith(
            compareBy<ReadingDocumentState> { it.bookmarks.isNotEmpty() }
                .thenBy { it.updatedAt }
                .thenBy { it.documentId },
        ).take(removeCount).mapTo(hashSetOf(), ReadingDocumentState::documentId)
        return next.filterKeys { it !in evicted }
    }

    private fun persist(next: Map<String, ReadingDocumentState>) {
        val encoded = json.encodeToString(
            ReadingStateStore(documents = next.values.sortedBy(ReadingDocumentState::documentId)),
        )
        textStore.replace(backup, encoded)
        textStore.replace(primary, encoded)
        states = next.toMap()
    }

    private fun read(): ReadingStateStore {
        return listOfNotNull(readValid(primary), readValid(backup))
            .maxWithOrNull(compareBy<ValidReadingState> { it.modifiedAt }.thenBy { it.primary })
            ?.store
            ?: ReadingStateStore()
    }

    private fun readValid(file: File): ValidReadingState? = try {
        if (!file.isFile) return null
        val decoded = json.decodeFromString<ReadingStateStore>(file.readText(Charsets.UTF_8))
        if (decoded.version != ReadingStateStore.VERSION || decoded.documents.size > maxDocuments) return null
        decoded.documents.forEach(::validate)
        if (decoded.documents.map(ReadingDocumentState::documentId).toSet().size != decoded.documents.size) return null
        ValidReadingState(decoded, file.lastModified(), file == primary)
    } catch (_: Exception) {
        null
    }

    private fun validate(state: ReadingDocumentState) {
        requireDocumentId(state.documentId)
        require(state.updatedAt >= 0) { "Reading state timestamp must not be negative" }
        require(state.bookmarks.size <= MAX_BOOKMARKS) { "Too many heading bookmarks" }
        require(state.bookmarks.map(HeadingBookmark::id).toSet().size == state.bookmarks.size) {
            "Heading bookmark IDs must be unique"
        }
        validatePosition(state.position)
        state.bookmarks.forEach(::validateBookmark)
    }

    private fun validatePosition(position: ReadingPosition) {
        require(position.editorLine in 1..MAX_SOURCE_POSITION) { "Editor line is outside the supported range" }
        require(position.editorColumn in 0..MAX_SOURCE_POSITION) { "Editor column is outside the supported range" }
        require(position.editorProgress.isFinite() && position.editorProgress in 0f..1f) {
            "Editor progress is outside the supported range"
        }
        require(position.previewProgress.isFinite() && position.previewProgress in 0f..1f) {
            "Preview progress is outside the supported range"
        }
        position.previewHeadingId?.let(::requireHeadingId)
    }

    private fun validateBookmark(bookmark: HeadingBookmark) {
        requireHeadingId(bookmark.id)
        require(bookmark.title.isNotBlank() && bookmark.title.length <= MAX_HEADING_TITLE) {
            "Heading bookmark title is invalid"
        }
        require(bookmark.title.none(Char::isISOControl)) { "Heading bookmark title contains control characters" }
        require(bookmark.level in 1..6) { "Heading bookmark level is invalid" }
        require(bookmark.sourceLine in 1..MAX_SOURCE_POSITION) { "Heading bookmark line is invalid" }
    }

    private fun requireDocumentId(documentId: String) {
        require(DOCUMENT_ID.matches(documentId)) { "Invalid reading-state document ID" }
    }

    private fun requireHeadingId(headingId: String) {
        require(headingId.isNotBlank() && headingId.length <= MAX_HEADING_ID) { "Heading ID is invalid" }
        require(headingId.none(Char::isISOControl)) { "Heading ID contains control characters" }
    }

    private companion object {
        const val MAX_DOCUMENTS = 1_000
        const val MAX_BOOKMARKS = 200
        const val MAX_HEADING_ID = 256
        const val MAX_HEADING_TITLE = 512
        const val MAX_SOURCE_POSITION = 10_000_000
        val DOCUMENT_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

fun reconcileHeadingBookmarks(
    bookmarks: List<HeadingBookmark>,
    headings: List<DocumentHeading>,
): List<ResolvedHeadingBookmark> {
    val availableById = headings.associateBy(DocumentHeading::id)
    val unused = headings.toMutableList()
    return bookmarks.map { bookmark ->
        val exact = availableById[bookmark.id]
        val candidate = exact ?: unused
            .asSequence()
            .filter {
                it.level == bookmark.level &&
                    normalizedHeadingTitle(it.title) == normalizedHeadingTitle(bookmark.title)
            }
            .minByOrNull { abs(it.sourceLine - bookmark.sourceLine) }
        if (candidate == null) {
            ResolvedHeadingBookmark(bookmark, available = false)
        } else {
            unused.removeAll { it.id == candidate.id }
            ResolvedHeadingBookmark(
                HeadingBookmark(candidate.id, candidate.title, candidate.level, candidate.sourceLine),
                available = true,
            )
        }
    }
}

private fun normalizedHeadingTitle(title: String): String = title
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

@Serializable
private data class ReadingStateStore(
    val version: Int = VERSION,
    val documents: List<ReadingDocumentState> = emptyList(),
) {
    companion object { const val VERSION = 1 }
}

private data class ValidReadingState(
    val store: ReadingStateStore,
    val modifiedAt: Long,
    val primary: Boolean,
)
