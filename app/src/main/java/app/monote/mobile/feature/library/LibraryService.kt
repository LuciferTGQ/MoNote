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
import kotlinx.coroutines.withContext

class LibraryService(
    private val paths: LibraryDirectories,
    private val catalog: CatalogRepository,
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
