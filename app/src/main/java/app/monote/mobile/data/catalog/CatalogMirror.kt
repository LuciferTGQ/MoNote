package app.monote.mobile.data.catalog

import app.monote.mobile.core.storage.AtomicTextStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class CatalogMirror(
    directory: File,
    private val store: AtomicTextStore = AtomicTextStore(),
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    constructor(directory: java.nio.file.Path) : this(directory.toFile())

    private val primary = File(directory, "catalog.json")
    private val backup = File(directory, "catalog.json.bak")
    private val mutex = Mutex()

    suspend fun write(snapshot: CatalogSnapshot) = mutex.withLock { withContext(Dispatchers.IO) {
        require(snapshot.version == CatalogSnapshot.VERSION) { "Unsupported catalog version" }
        val stable = snapshot.copy(documents = snapshot.documents.sortedBy { it.relativePath }.map { it.copy(tags = it.tags.toSortedSet()) })
        val text = json.encodeToString(CatalogSnapshot.serializer(), stable)
        // Write the independent recovery copy first. A failed primary replacement leaves it usable.
        store.replace(backup, text)
        store.replace(primary, text)
    } }

    suspend fun read(): CatalogSnapshot = mutex.withLock { withContext(Dispatchers.IO) {
        listOfNotNull(readFile(primary), readFile(backup))
            .maxWithOrNull(compareBy<ValidCopy> { it.modifiedAt }.thenBy { it.isPrimary })
            ?.snapshot
            ?: CatalogSnapshot()
    } }

    private fun readFile(file: File): ValidCopy? = try {
        if (!file.isFile) return null
        val snapshot = json.decodeFromString(CatalogSnapshot.serializer(), file.readText(Charsets.UTF_8))
            .takeIf { it.version == CatalogSnapshot.VERSION }
            ?: return null
        ValidCopy(snapshot, file.lastModified(), file == primary)
    } catch (_: Exception) {
        null
    }

    private data class ValidCopy(
        val snapshot: CatalogSnapshot,
        val modifiedAt: Long,
        val isPrimary: Boolean,
    )
}
