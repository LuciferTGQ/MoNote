package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.LibraryDirectories
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DirectoryMetadata(
    val relativePath: String,
    val favorite: Boolean = false,
    val tags: Set<String> = emptySet(),
)

class DirectoryMetadataRepository(
    private val paths: LibraryDirectories,
) {
    private val guard = LibraryPathGuard(paths)
    private val store = AtomicTextStore()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val file = paths.system.resolve("directory-metadata.json")
    private val mutex = Mutex()
    private val initialStore = read()
    private val mutableMetadata = MutableStateFlow(initialStore.items.associateBy { it.relativePath })
    private var trashedMetadata = initialStore.trashed
    val metadata: StateFlow<Map<String, DirectoryMetadata>> = mutableMetadata.asStateFlow()

    suspend fun setFavorite(relativePaths: Set<String>, favorite: Boolean) = mutate(relativePaths) { current, relative ->
        (current ?: DirectoryMetadata(relative)).copy(favorite = favorite)
    }

    suspend fun setTags(relativePaths: Set<String>, tags: Set<String>) = mutate(relativePaths) { current, relative ->
        (current ?: DirectoryMetadata(relative)).copy(tags = tags.toSortedSet())
    }

    suspend fun movePaths(moves: Map<String, String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (moves.isEmpty()) return@withContext
            val normalizedMoves = moves.entries.associate { (source, destination) ->
                normalizedRelativePath(source) to validatedRelativePath(destination)
            }
            require(normalizedMoves.size == moves.size) { "Duplicate directory metadata source paths" }
            require(normalizedMoves.values.toSet().size == normalizedMoves.size) {
                "Duplicate directory metadata destination paths"
            }
            val current = mutableMetadata.value
            val moved = current.values.mapNotNull { metadata ->
                val source = normalizedMoves.keys.firstOrNull { metadata.relativePath.isWithin(it) }
                    ?: return@mapNotNull null
                val destination = normalizedMoves.getValue(source)
                metadata.copy(relativePath = destination + metadata.relativePath.removePrefix(source))
            }
            val next = current.filterKeys { relative ->
                normalizedMoves.keys.none(relative::isWithin) &&
                    normalizedMoves.values.none(relative::isWithin)
            }.toMutableMap()
            moved.forEach { metadata -> next[metadata.relativePath] = metadata }
            persist(next, trashedMetadata)
        }
    }

    suspend fun moveToTrash(relativePath: String, stableId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireStableId(stableId)
            val source = normalizedRelativePath(relativePath)
            val moved = mutableMetadata.value.values.filter { it.relativePath.isWithin(source) }
            val nextActive = mutableMetadata.value.filterKeys { !it.isWithin(source) }
            val nextTrashed = trashedMetadata.toMutableMap().apply {
                if (moved.isEmpty()) remove(stableId)
                else put(stableId, TrashedDirectoryMetadata(source, moved.sortedBy { it.relativePath }))
            }
            persist(nextActive, nextTrashed)
        }
    }

    suspend fun restoreFromTrash(stableId: String, destinationRelativePath: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireStableId(stableId)
            val held = trashedMetadata[stableId] ?: return@withContext
            val destination = validatedRelativePath(destinationRelativePath)
            val restored = held.items.map { metadata ->
                metadata.copy(relativePath = destination + metadata.relativePath.removePrefix(held.sourceRelativePath))
            }
            val nextActive = mutableMetadata.value.filterKeys { !it.isWithin(destination) }.toMutableMap()
            restored.forEach { metadata -> nextActive[metadata.relativePath] = metadata }
            val nextTrashed = trashedMetadata - stableId
            persist(nextActive, nextTrashed)
        }
    }

    suspend fun deleteTrashed(stableIds: Set<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val nextTrashed = trashedMetadata - stableIds
            if (nextTrashed != trashedMetadata) persist(mutableMetadata.value, nextTrashed)
        }
    }

    suspend fun reconcileTrashed(actualStableIds: Set<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            actualStableIds.forEach(::requireStableId)
            val nextTrashed = trashedMetadata.filterKeys(actualStableIds::contains)
            if (nextTrashed != trashedMetadata) persist(mutableMetadata.value, nextTrashed)
        }
    }

    private suspend fun mutate(
        relativePaths: Set<String>,
        update: (DirectoryMetadata?, String) -> DirectoryMetadata,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalized = relativePaths.mapTo(linkedSetOf(), ::validatedRelativePath)
            val next = mutableMetadata.value.toMutableMap()
            normalized.forEach { relative -> next[relative] = update(next[relative], relative) }
            persist(next, trashedMetadata)
        }
    }

    private fun persist(
        next: Map<String, DirectoryMetadata>,
        nextTrashed: Map<String, TrashedDirectoryMetadata>,
    ) {
        store.replace(
            file,
            json.encodeToString(
                DirectoryMetadataStore(
                    items = next.values.sortedBy { it.relativePath },
                    trashed = nextTrashed.toSortedMap(),
                ),
            ),
        )
        mutableMetadata.value = next.toMap()
        trashedMetadata = nextTrashed.toMap()
    }

    private fun validatedRelativePath(relativePath: String): String {
        val folder = guard.contentPath(paths.root.resolve(relativePath), requireExists = true)
        require(Files.isDirectory(folder, NOFOLLOW_LINKS) && !Files.isSymbolicLink(folder)) {
            "Directory metadata target is not a real directory: $relativePath"
        }
        return guard.relativeContentPath(folder)
    }

    private fun normalizedRelativePath(relativePath: String): String =
        guard.relativeContentPath(guard.contentPath(paths.root.resolve(relativePath)))

    private fun requireStableId(stableId: String) {
        require(STABLE_ID.matches(stableId)) { "Invalid trash stable ID: $stableId" }
    }

    private fun read(): DirectoryMetadataStore = try {
        if (!file.isFile) return DirectoryMetadataStore()
        json.decodeFromString<DirectoryMetadataStore>(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        DirectoryMetadataStore()
    }

    private companion object {
        val STABLE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

@Serializable
private data class DirectoryMetadataStore(
    val version: Int = 2,
    val items: List<DirectoryMetadata> = emptyList(),
    val trashed: Map<String, TrashedDirectoryMetadata> = emptyMap(),
)

@Serializable
private data class TrashedDirectoryMetadata(
    val sourceRelativePath: String,
    val items: List<DirectoryMetadata>,
)

private fun String.isWithin(root: String): Boolean = this == root || startsWith("$root/")
