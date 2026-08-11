package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.DocumentEntity
import app.monote.mobile.feature.importing.TextDecoder
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface LibraryFileOperations {
    fun move(source: Path, destination: Path): Path
}

private object NioLibraryFileOperations : LibraryFileOperations {
    override fun move(source: Path, destination: Path): Path = Files.move(source, destination)
}

class LibraryService(
    private val paths: LibraryDirectories,
    private val catalog: CatalogRepository,
    private val directoryMetadataRepository: DirectoryMetadataRepository = DirectoryMetadataRepository(paths),
    private val moveRecoveryRepository: MoveRecoveryStore = MoveRecoveryRepository(paths),
    private val fileOperations: LibraryFileOperations = NioLibraryFileOperations,
    private val requestRescan: () -> Unit = {},
) {
    private val guard = LibraryPathGuard(paths)
    private val decoder = TextDecoder()

    suspend fun rename(file: File, newName: String): LibraryResult = withContext(Dispatchers.IO) {
        operation {
            val source = guard.contentPath(file, requireExists = true)
            val destination = source.resolveSibling(guard.requireSimpleName(newName))
            moveValidated(source, destination)
        }
    }

    suspend fun move(file: File, destination: File): LibraryResult = withContext(Dispatchers.IO) {
        operation {
            val source = guard.contentPath(file, requireExists = true)
            val target = guard.contentPath(destination)
            moveValidated(source, target)
        }
    }

    suspend fun moveBatch(files: List<File>, destinationDirectory: File): BatchMoveResult = withContext(Dispatchers.IO) {
        try {
            require(files.isNotEmpty()) { "No library items selected" }
            val destination = guard.contentPath(destinationDirectory, allowRoot = true, requireExists = true)
            requireRealDirectory(destination)
            val sources = files.map { guard.contentPath(it, requireExists = true) }
            require(sources.distinct().size == sources.size) { "Duplicate library item selected" }
            val targets = sources.map { source ->
                val target = guard.contentPath(destination.resolve(source.fileName).toFile())
                if (Files.isRegularFile(source, NOFOLLOW_LINKS) && !isMarkdown(target)) {
                    throw IllegalArgumentException("Library documents must keep a .md or .markdown extension")
                }
                if (Files.isDirectory(source, NOFOLLOW_LINKS) && target.startsWith(source)) {
                    throw IllegalArgumentException("A directory cannot be moved inside itself")
                }
                target
            }
            require(targets.distinct().size == targets.size) { "Selected items have duplicate destination names" }
            val directoryMoves = sources.zip(targets)
                .filter { (source, _) -> Files.isDirectory(source, NOFOLLOW_LINKS) }
                .associate { (source, target) ->
                    guard.relativeContentPath(source) to guard.relativeContentPath(target)
                }
            targets.firstOrNull { Files.exists(it, NOFOLLOW_LINKS) }
                ?.let { return@withContext BatchMoveResult.Conflict(it.toFile()) }

            catalog.withScanLock {
                val catalogStates = sources.associateWith { source ->
                    catalogStateFor(guard.relativeContentPath(source))
                }
                if (catalogStates.values.any { it.readFailed }) {
                    val error = IllegalStateException("Unable to read catalog before batch move")
                    return@withScanLock BatchMoveResult.Failure(error.message!!, error, rolledBack = true)
                }
                val moved = mutableListOf<Pair<Path, Path>>()
                try {
                    sources.zip(targets).forEach { (source, target) ->
                        fileOperations.move(source, target)
                        moved += source to target
                    }
                    directoryMetadataRepository.movePaths(directoryMoves)
                } catch (failure: Exception) {
                    val recovery = rollbackMoves(moved)
                    if (recovery.isEmpty()) {
                        return@withScanLock BatchMoveResult.Failure(
                            message = failure.message ?: "Batch move failed",
                            cause = failure,
                            rolledBack = true,
                        )
                    }
                    return@withScanLock withContext(NonCancellable) {
                        val recoveryWrite = try {
                            moveRecoveryRepository.record(recovery)
                        } catch (logFailure: Exception) {
                            RecoveryWriteResult.Failed(
                                logFailure.message ?: "Unable to persist move recovery records",
                                logFailure,
                            )
                        }
                        val persistedRecovery = when (recoveryWrite) {
                            is RecoveryWriteResult.Recorded -> recoveryWrite.records
                            is RecoveryWriteResult.Failed -> {
                                if (recoveryWrite.cause !== failure) failure.addSuppressed(recoveryWrite.cause)
                                emptyList()
                            }
                        }
                        try {
                            requestRescan()
                        } catch (rescanFailure: Exception) {
                            if (rescanFailure !== failure) failure.addSuppressed(rescanFailure)
                        }
                        BatchMoveResult.Failure(
                            message = failure.message ?: "Batch move failed",
                            cause = failure,
                            rolledBack = false,
                            recoveryRecords = persistedRecovery,
                            recoveryPersistenceFailure = (recoveryWrite as? RecoveryWriteResult.Failed)?.message,
                        )
                    }
                }
                var synchronized = true
                sources.zip(targets).forEach { (source, target) ->
                    synchronized = synchronizeCatalogDuringLock(
                        catalogStates.getValue(source).documents,
                        source,
                        target,
                    ) && synchronized
                }
                BatchMoveResult.Success(targets.map { it.toFile() }, synchronized)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            BatchMoveResult.Failure(
                failure.message ?: "Batch move failed",
                failure,
                rolledBack = true,
            )
        }
    }

    private fun rollbackMoves(moved: List<Pair<Path, Path>>): List<MoveRecoveryRecord> {
        val recovery = mutableListOf<MoveRecoveryRecord>()
        moved.asReversed().forEach { (original, current) ->
            try {
                fileOperations.move(current, original)
            } catch (failure: Exception) {
                recovery += MoveRecoveryRecord(
                    original.toFile(),
                    current.toFile(),
                    failure.message ?: "Rollback failed",
                )
            }
        }
        return recovery
    }

    suspend fun createFolder(parent: File, name: String): LibraryResult = withContext(Dispatchers.IO) {
        operation {
            val parentPath = guard.contentPath(parent, allowRoot = true, requireExists = true)
            requireRealDirectory(parentPath)
            val target = guard.contentPath(parentPath.resolve(guard.requireSimpleName(name)).toFile())
            try {
                Files.createDirectory(target)
                LibraryResult.Success(target.toFile())
            } catch (_: FileAlreadyExistsException) {
                LibraryResult.Conflict(target.toFile())
            }
        }
    }

    suspend fun createMarkdown(parent: File, name: String): LibraryResult = withContext(Dispatchers.IO) {
        operation {
            val parentPath = guard.contentPath(parent, allowRoot = true, requireExists = true)
            requireRealDirectory(parentPath)
            val validName = guard.requireSimpleName(name)
            val markdownName = if (validName.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS) {
                validName
            } else {
                "$validName.md"
            }
            val target = guard.contentPath(parentPath.resolve(markdownName).toFile())
            try {
                Files.newByteChannel(target, CREATE_NEW, WRITE).use { channel -> channel.forceIfFileChannel() }
            } catch (_: FileAlreadyExistsException) {
                return@operation LibraryResult.Conflict(target.toFile())
            }
            val synchronized = synchronizeCatalog(emptyMap(), target, target)
            LibraryResult.Success(target.toFile(), catalogSynchronized = synchronized)
        }
    }

    private suspend fun moveValidated(source: Path, destination: Path): LibraryResult {
        val directory = Files.isDirectory(source, NOFOLLOW_LINKS)
        if (Files.isRegularFile(source, NOFOLLOW_LINKS) && !isMarkdown(destination)) {
            throw IllegalArgumentException("Library documents must keep a .md or .markdown extension")
        }
        if (Files.exists(destination, NOFOLLOW_LINKS)) return LibraryResult.Conflict(destination.toFile())
        val parent = destination.parent ?: throw IllegalArgumentException("Destination has no parent: $destination")
        guard.contentPath(parent.toFile(), allowRoot = true, requireExists = true)
        requireRealDirectory(parent)
        if (Files.isDirectory(source, NOFOLLOW_LINKS) && destination.startsWith(source)) {
            throw IllegalArgumentException("A directory cannot be moved inside itself")
        }

        return catalog.withScanLock {
            val sourceRelative = guard.relativeContentPath(source)
            val knownDocuments = catalogStateFor(sourceRelative)
            try {
                Files.move(source, destination)
            } catch (_: FileAlreadyExistsException) {
                return@withScanLock LibraryResult.Conflict(destination.toFile())
            }
            if (directory) {
                try {
                    directoryMetadataRepository.movePaths(
                        mapOf(
                            guard.relativeContentPath(source) to guard.relativeContentPath(destination),
                        ),
                    )
                } catch (error: Exception) {
                    try {
                        Files.move(destination, source)
                    } catch (rollbackError: Exception) {
                        error.addSuppressed(rollbackError)
                    }
                    throw error
                }
            }
            val synchronized = if (knownDocuments.readFailed) {
                requestRescan()
                false
            } else {
                synchronizeCatalogDuringLock(knownDocuments.documents, source, destination)
            }
            LibraryResult.Success(destination.toFile(), catalogSynchronized = synchronized)
        }
    }

    private suspend fun catalogStateFor(sourceRelative: String): CatalogState {
        return try {
            val prefix = "$sourceRelative/"
            val documents = catalog.all()
                .filter { it.relativePath == sourceRelative || it.relativePath.startsWith(prefix) }
                .associate { document ->
                    document.relativePath to CatalogDocumentState(document, catalog.tags(document.id))
                }
            CatalogState(documents, readFailed = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            CatalogState(emptyMap(), readFailed = true)
        }
    }

    private suspend fun synchronizeCatalog(
        knownDocuments: Map<String, CatalogDocumentState>,
        source: Path,
        destination: Path,
    ): Boolean = catalog.withScanLock {
        synchronizeCatalogDuringLock(knownDocuments, source, destination)
    }

    private suspend fun synchronizeCatalogDuringLock(
        knownDocuments: Map<String, CatalogDocumentState>,
        source: Path,
        destination: Path,
    ): Boolean {
        return try {
            markdownFiles(destination).forEach { file ->
                val destinationRelative = guard.relativeContentPath(file)
                val suffix = destination.relativize(file)
                val previousPath = if (source == destination) {
                    destinationRelative
                } else {
                    guard.relativeContentPath(source.resolve(suffix))
                }
                val known = knownDocuments[previousPath]
                val bytes = Files.newInputStream(file).use(decoder::readBytes)
                val body = decoder.decode(bytes).text
                val modifiedAt = Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toMillis()
                val document = known?.document?.copy(
                    relativePath = destinationRelative,
                    modifiedAt = modifiedAt,
                    size = bytes.size.toLong(),
                    sha256 = DocumentFingerprint.sha256(bytes),
                ) ?: DocumentEntity(
                    id = UUID.randomUUID().toString(),
                    relativePath = destinationRelative,
                    title = titleFor(file, body),
                    modifiedAt = modifiedAt,
                    size = bytes.size.toLong(),
                    sha256 = DocumentFingerprint.sha256(bytes),
                )
                catalog.upsertDuringScan(document, body, known?.tags.orEmpty())
            }
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

    private fun markdownFiles(path: Path): List<Path> {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            return if (isMarkdown(path)) listOf(path) else emptyList()
        }
        val files = mutableListOf<Path>()
        Files.walk(path).use { paths ->
            paths.filter { candidate ->
                Files.isRegularFile(candidate, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(candidate) &&
                    isMarkdown(candidate)
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

    private fun requireRealDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IllegalArgumentException("Library parent is not a real directory: $path")
        }
    }

    private suspend fun operation(block: suspend () -> LibraryResult): LibraryResult {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryResult.Failure(error.message ?: "Library operation failed", error)
        }
    }

    private data class CatalogDocumentState(val document: DocumentEntity, val tags: Set<String>)
    private data class CatalogState(
        val documents: Map<String, CatalogDocumentState>,
        val readFailed: Boolean,
    )

    private companion object {
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
    }
}

private fun java.nio.channels.SeekableByteChannel.forceIfFileChannel() {
    (this as? java.nio.channels.FileChannel)?.force(true)
}
