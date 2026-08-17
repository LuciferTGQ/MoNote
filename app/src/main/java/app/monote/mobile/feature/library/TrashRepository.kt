package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.DocumentEntity
import app.monote.mobile.feature.importing.TextDecoder
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TrashRepository(
    private val paths: LibraryDirectories,
    private val catalog: CatalogRepository,
    private val pendingRestoreTrustStore: PendingRestoreTrustStore,
    private val directoryMetadataRepository: DirectoryMetadataRepository = DirectoryMetadataRepository(paths),
    private val textStore: AtomicTextStore = AtomicTextStore(),
    private val beforeReconcileCommit: suspend () -> Unit = {},
    private val afterPendingRestoreWritten: suspend () -> Unit = {},
    private val afterRestoreMove: suspend () -> Unit = {},
    private val onDocumentsPermanentlyDeleted: suspend (Set<String>) -> Unit = {},
    private val requestRescan: () -> Unit = {},
) {
    private val guard = LibraryPathGuard(paths)
    private val decoder = TextDecoder()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val transactionMutex = Mutex()

    suspend fun moveToTrash(
        source: File,
        now: Instant = Instant.now(),
        stableId: String? = null,
    ): TrashEntry = withContext(Dispatchers.IO) {
        transactionMutex.withLock { moveToTrashLocked(source, now, stableId) }
    }

    private suspend fun moveToTrashLocked(
        source: File,
        now: Instant,
        stableId: String?,
    ): TrashEntry {
        return catalog.withScanLock {
            val sourcePath = guard.contentPath(source, requireExists = true)
            val originalRelativePath = guard.relativeContentPath(sourcePath)
            val sourceIsDirectory = Files.isDirectory(sourcePath, NOFOLLOW_LINKS)
            val catalogState = readCatalogState(sourcePath, originalRelativePath)
            catalogState.readFailure?.let { error ->
                requestRescan()
                throw IllegalStateException("Unable to read catalog before moving content to trash", error)
            }
            val entryId = stableId ?: catalogState.exactDocumentId ?: UUID.randomUUID().toString()
            val entryRoot = guard.trashEntryRoot(entryId)
            val trashedPath = guard.trashContentPath(entryId, originalRelativePath)
            val metadataFile = entryRoot.resolve(METADATA_FILE_NAME).toFile()
            var contentMoved = false
            var directoryMetadataMoved = false

            try {
                Files.createDirectory(entryRoot)
                pendingRestoreTrustStore.remove(entryId)
                Files.createDirectories(trashedPath.parent)
                textStore.replace(
                    metadataFile,
                    json.encodeToString(
                        TrashMetadata(
                            stableId = entryId,
                            originalRelativePath = originalRelativePath,
                            deletedAt = now.toString(),
                            documents = catalogState.documents,
                        ),
                    ),
                )
                Files.move(sourcePath, trashedPath)
                contentMoved = true
                if (sourceIsDirectory) {
                    directoryMetadataRepository.moveToTrash(originalRelativePath, entryId)
                    directoryMetadataMoved = true
                }
            } catch (error: CancellationException) {
                if (contentMoved) {
                    contentMoved = !rollBackFailedTrashMove(
                        sourcePath,
                        trashedPath,
                        originalRelativePath,
                        entryId,
                        directoryMetadataMoved,
                        error,
                    )
                }
                if (!contentMoved) cleanUpFailedEntry(entryRoot, error)
                throw error
            } catch (error: Exception) {
                if (contentMoved) {
                    contentMoved = !rollBackFailedTrashMove(
                        sourcePath,
                        trashedPath,
                        originalRelativePath,
                        entryId,
                        directoryMetadataMoved,
                        error,
                    )
                }
                if (!contentMoved) cleanUpFailedEntry(entryRoot, error)
                throw error
            }

            val catalogSynchronized = synchronizeCatalogRemovalDuringLock(originalRelativePath)
            TrashEntry(
                stableId = entryId,
                originalRelativePath = originalRelativePath,
                deletedAt = now,
                trashedFile = trashedPath.toFile(),
                metadataFile = metadataFile,
                catalogSynchronized = catalogSynchronized,
                documents = catalogState.documents,
                state = TrashEntryState.TRASHED,
            )
        }
    }

    suspend fun listEntries(): List<TrashEntry> = withContext(Dispatchers.IO) {
        transactionMutex.withLock { listEntriesLocked() }
    }

    private suspend fun listEntriesLocked(): List<TrashEntry> {
        val trash = paths.trash.toPath()
        if (!Files.isDirectory(trash, NOFOLLOW_LINKS) || Files.isSymbolicLink(trash)) {
            directoryMetadataRepository.reconcileTrashed(emptySet())
            return emptyList()
        }
        val entries = mutableListOf<TrashEntry>()
        Files.list(trash).use { children ->
            children.forEach { child ->
                if (Files.isDirectory(child, NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                    parseEntry(child)?.let(entries::add)
                }
            }
        }
        reconcileDirectoryMetadataLocked()
        return entries.sortedWith(compareByDescending<TrashEntry> { it.deletedAt }.thenBy { it.stableId })
    }

    suspend fun reconcileStartup() = withContext(Dispatchers.IO) {
        transactionMutex.withLock { reconcileDirectoryMetadataLocked() }
    }

    suspend fun restore(entry: TrashEntry): LibraryResult = withContext(Dispatchers.IO) {
        transactionMutex.withLock { restoreLocked(entry) }
    }

    private suspend fun restoreLocked(entry: TrashEntry): LibraryResult {
        try {
            val stored = parseEntry(guard.trashEntryRoot(entry.stableId))
                ?: return LibraryResult.Failure(
                    "Trash entry is missing or invalid: ${entry.stableId}",
                    IllegalArgumentException("Invalid trash metadata"),
                )
            val destination = guard.contentPath(paths.root.resolve(stored.originalRelativePath))
            val parent = destination.parent ?: throw IllegalArgumentException("Restore target has no parent")
            guard.contentPath(parent.toFile(), allowRoot = true)
            Files.createDirectories(parent)
            guard.contentPath(parent.toFile(), allowRoot = true, requireExists = true)

            val trashedPath = stored.trashedFile.toPath()
            val destinationExists = Files.exists(destination, NOFOLLOW_LINKS)
            val trashExists = Files.exists(trashedPath, NOFOLLOW_LINKS)
            val pending = when {
                stored.state == TrashEntryState.TRASHED -> {
                    if (destinationExists) return LibraryResult.Conflict(destination.toFile())
                    val documents = snapshotRestoredDocuments(stored, trashedPath)
                    writePendingRestore(stored, destination, documents).also {
                        afterPendingRestoreWritten()
                    }
                }

                stored.pendingRestoreTrusted -> stored

                trashExists && !destinationExists -> {
                    // A public/legacy sidecar cannot confer catalog identity. Rebuild a
                    // private pending intent from the actual trash content instead.
                    val documents = snapshotRestoredDocuments(stored.copy(documents = emptyList()), trashedPath)
                    writePendingRestore(stored, destination, documents).also {
                        afterPendingRestoreWritten()
                    }
                }

                destinationExists && !trashExists -> {
                    // A forged pending sidecar may point at an active file. Treat the
                    // content as already present, but do not trust any supplied metadata.
                    stored.copy(documents = emptyList(), pendingRestoreTrusted = false)
                }

                else -> stored
            }
            if (destinationExists && trashExists) return LibraryResult.Conflict(destination.toFile())
            if (!destinationExists && !trashExists) {
                return LibraryResult.Failure(
                    "Pending restore content is missing: ${pending.stableId}",
                    IOException("Neither trash nor destination content exists"),
                )
            }

            var movedNow = false
            var directoryMetadataRestored = false
            try {
                if (!destinationExists) {
                    try {
                        Files.move(trashedPath, destination)
                    } catch (_: FileAlreadyExistsException) {
                        rollbackPendingRestore(pending)
                        return LibraryResult.Conflict(destination.toFile())
                    }
                    movedNow = true
                    afterRestoreMove()
                }
                if (pending.pendingRestoreTrusted && !verifyPendingDocuments(destination, pending.documents)) {
                    throw IOException("Restored content no longer matches the pending journal")
                }
                if (Files.isDirectory(destination, NOFOLLOW_LINKS)) {
                    directoryMetadataRepository.restoreFromTrash(
                        pending.stableId,
                        pending.originalRelativePath,
                    )
                    directoryMetadataRestored = true
                }
            } catch (error: Exception) {
                if (movedNow) {
                    try {
                        Files.move(destination, trashedPath)
                        if (directoryMetadataRestored) {
                            directoryMetadataRepository.moveToTrash(
                                pending.originalRelativePath,
                                pending.stableId,
                            )
                        }
                        rollbackPendingRestore(pending)
                    } catch (rollbackError: Exception) {
                        error.addSuppressed(rollbackError)
                    }
                }
                throw error
            }

            val catalogSynchronized = synchronizeRestoredContent(pending, destination)
            val warnings = mutableListOf<String>()
            if (catalogSynchronized) {
                try {
                    pendingRestoreTrustStore.remove(pending.stableId)
                    deleteTree(guard.trashEntryRoot(pending.stableId))
                } catch (error: Exception) {
                    warnings += "Trash metadata cleanup failed: ${error.message ?: error.javaClass.simpleName}"
                }
            } else {
                warnings += "Catalog synchronization failed; trash journal retained"
            }
            return LibraryResult.Success(destination.toFile(), catalogSynchronized, warnings)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return LibraryResult.Failure(error.message ?: "Unable to restore trash entry", error)
        }
    }

    suspend fun deletePermanently(stableId: String, confirmed: Boolean): TrashDeleteResult =
        withContext(Dispatchers.IO) {
            transactionMutex.withLock {
                if (!confirmed) return@withLock TrashDeleteResult.ConfirmationRequired
                try {
                    val entryRoot = guard.trashEntryRoot(stableId)
                    if (!Files.exists(entryRoot, NOFOLLOW_LINKS)) {
                        reconcileDirectoryMetadataLocked()
                        TrashDeleteResult.NotFound(stableId)
                    } else {
                        val documentIds = parseEntry(entryRoot)
                            ?.documents
                            ?.mapTo(linkedSetOf(), TrashDocumentMetadata::id)
                            .orEmpty()
                        pendingRestoreTrustStore.remove(stableId)
                        deleteTree(entryRoot)
                        onDocumentsPermanentlyDeleted(documentIds)
                        reconcileDirectoryMetadataLocked()
                        TrashDeleteResult.Success(setOf(stableId))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    TrashDeleteResult.Failure(error.message ?: "Unable to delete trash entry", error)
                }
            }
        }

    suspend fun purgeExpired(
        now: Instant = Instant.now(),
        confirmed: Boolean,
    ): TrashDeleteResult = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            if (!confirmed) return@withLock TrashDeleteResult.ConfirmationRequired
            val cutoff = now.minus(RETENTION)
            deleteEntriesLocked(
                listEntriesLocked().filter {
                    it.state == TrashEntryState.TRASHED && it.deletedAt.isBefore(cutoff)
                },
            )
        }
    }

    suspend fun emptyTrash(confirmed: Boolean): TrashDeleteResult = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            if (!confirmed) return@withLock TrashDeleteResult.ConfirmationRequired
            val trash = paths.trash.toPath()
            if (!Files.isDirectory(trash, NOFOLLOW_LINKS) || Files.isSymbolicLink(trash)) {
                return@withLock try {
                    directoryMetadataRepository.reconcileTrashed(emptySet())
                    TrashDeleteResult.Success(emptySet())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    TrashDeleteResult.Failure(error.message ?: "Unable to empty trash", error)
                }
            }
            try {
                val documentIds = listEntriesLocked()
                    .flatMapTo(linkedSetOf()) { entry -> entry.documents.map(TrashDocumentMetadata::id) }
                val deleted = linkedSetOf<String>()
                Files.list(trash).use { children ->
                    children.forEach { child ->
                        try {
                            pendingRestoreTrustStore.remove(child.fileName.toString())
                        } catch (_: IllegalArgumentException) {
                            // Malformed trash children cannot have a valid private trust marker.
                        }
                        if (Files.isSymbolicLink(child)) {
                            Files.delete(child)
                        } else {
                            deleteTree(child)
                        }
                        deleted += child.fileName.toString()
                    }
                }
                onDocumentsPermanentlyDeleted(documentIds)
                reconcileDirectoryMetadataLocked()
                TrashDeleteResult.Success(deleted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                TrashDeleteResult.Failure(error.message ?: "Unable to empty trash", error)
            }
        }
    }

    private suspend fun deleteEntriesLocked(entries: List<TrashEntry>): TrashDeleteResult {
        return try {
            val deleted = linkedSetOf<String>()
            entries.forEach { entry ->
                pendingRestoreTrustStore.remove(entry.stableId)
                deleteTree(guard.trashEntryRoot(entry.stableId))
                deleted += entry.stableId
            }
            onDocumentsPermanentlyDeleted(
                entries.flatMapTo(linkedSetOf()) { entry -> entry.documents.map(TrashDocumentMetadata::id) },
            )
            reconcileDirectoryMetadataLocked()
            TrashDeleteResult.Success(deleted)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TrashDeleteResult.Failure(error.message ?: "Unable to purge expired trash", error)
        }
    }

    private fun parseEntry(entryRoot: Path): TrashEntry? {
        return try {
            val stableId = entryRoot.fileName.toString()
            if (guard.trashEntryRoot(stableId) != entryRoot.toAbsolutePath().normalize()) return null
            val metadataFile = entryRoot.resolve(METADATA_FILE_NAME).toFile()
            if (!metadataFile.isFile || Files.isSymbolicLink(metadataFile.toPath())) return null
            val metadata = json.decodeFromString<TrashMetadata>(metadataFile.readText(Charsets.UTF_8))
            if (metadata.stableId != stableId) return null
            val destination = guard.contentPath(paths.root.resolve(metadata.originalRelativePath))
            val normalizedRelative = guard.relativeContentPath(destination)
            if (normalizedRelative != metadata.originalRelativePath.replace('\\', '/')) return null
            val trashedFile = guard.trashContentPath(stableId, normalizedRelative).toFile()
            var pendingRestoreTrusted = false
            val pendingTarget = when (metadata.state) {
                TrashEntryState.TRASHED -> {
                    if (
                        !Files.exists(trashedFile.toPath(), NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(trashedFile.toPath())
                    ) {
                        return null
                    }
                    null
                }

                TrashEntryState.PENDING_RESTORE -> {
                    val pendingRelative = metadata.pendingTargetRelativePath ?: return null
                    val pendingPath = guard.contentPath(
                        paths.root.resolve(pendingRelative),
                        requireExists = false,
                    )
                    val normalizedPendingRelative = guard.relativeContentPath(pendingPath)
                    if (normalizedPendingRelative != pendingRelative.replace('\\', '/')) return null
                    if (normalizedPendingRelative != normalizedRelative) return null
                    val destinationExists = Files.exists(pendingPath, NOFOLLOW_LINKS)
                    val trashExists = Files.exists(trashedFile.toPath(), NOFOLLOW_LINKS)
                    if (!destinationExists && !trashExists) return null
                    if (destinationExists && !verifyPendingDocuments(pendingPath, metadata.documents)) return null
                    pendingRestoreTrusted = pendingRestoreTrustStore.matches(
                        stableId,
                        json.encodeToString(metadata),
                    )
                    pendingPath.toFile()
                }
            }
            TrashEntry(
                stableId = stableId,
                originalRelativePath = normalizedRelative,
                deletedAt = Instant.parse(metadata.deletedAt),
                trashedFile = trashedFile,
                metadataFile = metadataFile,
                documents = metadata.documents,
                state = metadata.state,
                pendingTargetRelativePath = metadata.pendingTargetRelativePath,
                pendingTargetFile = pendingTarget,
                pendingRestoreTrusted = pendingRestoreTrusted,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun snapshotRestoredDocuments(
        entry: TrashEntry,
        contentRoot: Path,
    ): List<TrashDocumentMetadata> {
        val existing = entry.documents.associateBy { it.originalRelativePath }
        val originalRoot = guard.contentPath(paths.root.resolve(entry.originalRelativePath))
        val contentIsDirectory = Files.isDirectory(contentRoot, NOFOLLOW_LINKS)
        val files = markdownFiles(contentRoot).map { file ->
            val restoredPath = if (contentIsDirectory) {
                originalRoot.resolve(contentRoot.relativize(file))
            } else {
                originalRoot
            }
            file to guard.relativeContentPath(restoredPath)
        }
        return snapshotTrashDocuments(files, existing)
    }

    private fun snapshotTrashDocuments(
        files: List<Pair<Path, String>>,
        existingByPath: Map<String, TrashDocumentMetadata>,
    ): List<TrashDocumentMetadata> = files.map { (file, relativePath) ->
        val fingerprint = DocumentFingerprint.from(file.toFile())
        val existing = existingByPath[relativePath]
        TrashDocumentMetadata(
            id = existing?.id ?: UUID.randomUUID().toString(),
            originalRelativePath = relativePath,
            title = existing?.title ?: file.fileName.toString().substringBeforeLast('.'),
            favorite = existing?.favorite ?: false,
            tags = existing?.tags ?: emptySet(),
            lastOpenedAt = existing?.lastOpenedAt,
            size = fingerprint.size,
            sha256 = fingerprint.sha256,
        )
    }

    private fun writePendingRestore(
        entry: TrashEntry,
        destination: Path,
        documents: List<TrashDocumentMetadata>,
    ): TrashEntry {
        val pendingRelativePath = guard.relativeContentPath(destination)
        val metadata = TrashMetadata(
            stableId = entry.stableId,
            originalRelativePath = entry.originalRelativePath,
            deletedAt = entry.deletedAt.toString(),
            documents = documents,
            state = TrashEntryState.PENDING_RESTORE,
            pendingTargetRelativePath = pendingRelativePath,
        )
        val canonicalMetadata = json.encodeToString(metadata)
        pendingRestoreTrustStore.trust(entry.stableId, canonicalMetadata)
        try {
            textStore.replace(entry.metadataFile, canonicalMetadata)
        } catch (error: Exception) {
            try {
                pendingRestoreTrustStore.remove(entry.stableId)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        return entry.copy(
            documents = documents,
            state = TrashEntryState.PENDING_RESTORE,
            pendingTargetRelativePath = pendingRelativePath,
            pendingTargetFile = destination.toFile(),
            pendingRestoreTrusted = true,
        )
    }

    private fun rollbackPendingRestore(entry: TrashEntry) {
        val trashed = TrashMetadata(
            stableId = entry.stableId,
            originalRelativePath = entry.originalRelativePath,
            deletedAt = entry.deletedAt.toString(),
            documents = entry.documents,
            state = TrashEntryState.TRASHED,
            pendingTargetRelativePath = null,
        )
        textStore.replace(entry.metadataFile, json.encodeToString(trashed))
        pendingRestoreTrustStore.remove(entry.stableId)
    }

    private fun verifyPendingDocuments(
        destination: Path,
        documents: List<TrashDocumentMetadata>,
    ): Boolean {
        val byPath = documents.associateBy { it.originalRelativePath }
        if (byPath.size != documents.size) return false
        val actualFiles = markdownFiles(destination).associateBy { guard.relativeContentPath(it) }
        if (actualFiles.keys != byPath.keys) return false
        return actualFiles.all { (relativePath, file) ->
            val expected = byPath.getValue(relativePath)
            val expectedSize = expected.size ?: return@all false
            val expectedSha256 = expected.sha256 ?: return@all false
            val actual = DocumentFingerprint.from(file.toFile())
            actual.size == expectedSize && actual.sha256 == expectedSha256
        }
    }

    private suspend fun readCatalogState(
        sourcePath: Path,
        originalRelativePath: String,
    ): CatalogRemovalState {
        return try {
            val prefix = "$originalRelativePath/"
            val all = catalog.all()
            val affected = all.filter {
                it.relativePath == originalRelativePath || it.relativePath.startsWith(prefix)
            }
            val knownDocuments = affected.associate { document ->
                document.relativePath to TrashDocumentMetadata(
                    id = document.id,
                    originalRelativePath = document.relativePath,
                    title = document.title,
                    favorite = document.favorite,
                    tags = catalog.tags(document.id),
                    lastOpenedAt = document.lastOpenedAt,
                )
            }
            val documents = snapshotTrashDocuments(
                markdownFiles(sourcePath).map { file -> file to guard.relativeContentPath(file) },
                knownDocuments,
            )
            val missingCatalogPaths = knownDocuments.keys - documents.mapTo(hashSetOf()) { it.originalRelativePath }
            if (missingCatalogPaths.isNotEmpty()) {
                throw IOException("Catalog refers to missing Markdown content: ${missingCatalogPaths.sorted()}")
            }
            CatalogRemovalState(
                exactDocumentId = affected.singleOrNull { it.relativePath == originalRelativePath }?.id,
                documents = documents,
                readFailure = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CatalogRemovalState(exactDocumentId = null, documents = emptyList(), readFailure = error)
        }
    }

    private suspend fun synchronizeCatalogRemovalDuringLock(originalRelativePath: String): Boolean {
        return try {
            val prefix = "$originalRelativePath/"
            val present = catalog.all()
                .filterNot {
                    it.relativePath == originalRelativePath || it.relativePath.startsWith(prefix)
                }
                .mapTo(linkedSetOf()) { it.id }
            catalog.deleteMissingDuringScan(present)
            catalog.refreshMirrorDuringScan()
            true
        } catch (error: CancellationException) {
            requestRescan()
            throw error
        } catch (_: Exception) {
            requestRescan()
            false
        }
    }

    private suspend fun synchronizeRestoredContent(entry: TrashEntry, destination: Path): Boolean {
        return try {
            catalog.withScanLock {
                val activeDocuments = catalog.all()
                val activeById = activeDocuments.associateBy { it.id }
                val activeByPath = activeDocuments.associateBy { it.relativePath }
                val metadataByPath = entry.documents.associateBy { it.originalRelativePath }
                val assignedIds = mutableMapOf<String, String>()
                markdownFiles(destination).forEach { file ->
                    val bytes = Files.newInputStream(file).use(decoder::readBytes)
                    val body = decoder.decode(bytes).text
                    val modifiedAt = Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toMillis()
                    val relativePath = guard.relativeContentPath(file)
                    val journalMetadata = metadataByPath[relativePath]
                    val trustedMetadata = journalMetadata.takeIf { entry.pendingRestoreTrusted }
                    val existingAtPath = activeByPath[relativePath]
                    val activeTags = existingAtPath?.let { catalog.tags(it.id) } ?: emptySet()
                    val id = chooseRestoreId(
                        desired = trustedMetadata?.id,
                        relativePath = relativePath,
                        existingAtPath = existingAtPath,
                        activeById = activeById,
                        assignedIds = assignedIds,
                    )
                    val tags = if (trustedMetadata == null) {
                        activeTags
                    } else {
                        activeTags + trustedMetadata.tags
                    }
                    val document = DocumentEntity(
                        id = id,
                        relativePath = relativePath,
                        title = existingAtPath?.title ?: trustedMetadata?.title ?: titleFor(file, body),
                        modifiedAt = modifiedAt,
                        size = bytes.size.toLong(),
                        sha256 = DocumentFingerprint.sha256(bytes),
                        favorite = (trustedMetadata?.favorite == true) || (existingAtPath?.favorite == true),
                        lastOpenedAt = maxTimestamp(
                            trustedMetadata?.lastOpenedAt,
                            existingAtPath?.lastOpenedAt,
                        ),
                    )
                    catalog.replaceIndexAtPathDuringScan(document, body, tags)
                    assignedIds[id] = relativePath
                }
                catalog.refreshMirrorDuringScan()
            }
            true
        } catch (error: CancellationException) {
            requestRescan()
            throw error
        } catch (_: Exception) {
            requestRescan()
            false
        }
    }

    private fun chooseRestoreId(
        desired: String?,
        relativePath: String,
        existingAtPath: DocumentEntity?,
        activeById: Map<String, DocumentEntity>,
        assignedIds: Map<String, String>,
    ): String {
        if (desired != null && DOCUMENT_ID.matches(desired)) {
            val activeBinding = activeById[desired]
            val assignedPath = assignedIds[desired]
            if (
                (activeBinding == null || activeBinding.relativePath == relativePath) &&
                (assignedPath == null || assignedPath == relativePath)
            ) {
                return desired
            }
        }
        if (existingAtPath != null) return existingAtPath.id
        while (true) {
            val generated = UUID.randomUUID().toString()
            if (generated !in activeById && generated !in assignedIds) return generated
        }
    }

    private fun maxTimestamp(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private fun markdownFiles(path: Path): List<Path> {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            return if (isMarkdown(path)) listOf(path) else emptyList()
        }
        val files = mutableListOf<Path>()
        Files.walk(path).use { paths ->
            paths.filter {
                Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(it) &&
                    isMarkdown(it)
            }.forEach(files::add)
        }
        return files.sortedBy(Path::toString)
    }

    private fun isMarkdown(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS

    private fun titleFor(file: Path, body: String): String = body.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("#") }
        ?.trimStart('#', ' ')
        ?.takeIf(String::isNotBlank)
        ?: file.fileName.toString().substringBeforeLast('.')

    private suspend fun rollBackFailedTrashMove(
        source: Path,
        trashed: Path,
        originalRelativePath: String,
        stableId: String,
        directoryMetadataMoved: Boolean,
        original: Throwable,
    ): Boolean = try {
        Files.move(trashed, source)
        if (directoryMetadataMoved) {
            directoryMetadataRepository.restoreFromTrash(stableId, originalRelativePath)
        }
        true
    } catch (rollback: Exception) {
        original.addSuppressed(rollback)
        false
    }

    private fun cleanUpFailedEntry(entryRoot: Path, original: Throwable) {
        try {
            if (Files.exists(entryRoot, NOFOLLOW_LINKS)) deleteTree(entryRoot)
        } catch (cleanup: Exception) {
            original.addSuppressed(cleanup)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, exception: java.io.IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private suspend fun reconcileDirectoryMetadataLocked() {
        val trash = paths.trash.toPath()
        val actualStableIds = if (!Files.isDirectory(trash, NOFOLLOW_LINKS) || Files.isSymbolicLink(trash)) {
            emptySet()
        } else {
            val stableIds = linkedSetOf<String>()
            Files.list(trash).use { children ->
                children.forEach { child ->
                    if (Files.isDirectory(child, NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                        parseEntry(child)?.let { entry -> stableIds += entry.stableId }
                    }
                }
            }
            stableIds
        }
        beforeReconcileCommit()
        directoryMetadataRepository.reconcileTrashed(actualStableIds)
    }

    private data class CatalogRemovalState(
        val exactDocumentId: String?,
        val documents: List<TrashDocumentMetadata>,
        val readFailure: Throwable?,
    )

    private companion object {
        const val METADATA_FILE_NAME = "entry.json"
        val RETENTION: Duration = Duration.ofDays(30)
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
        val DOCUMENT_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
