package app.monote.mobile.data.catalog

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ScanStatus { IDLE, SCANNING, COMPLETED }

data class ScanProgress(
    val status: ScanStatus = ScanStatus.IDLE,
    val scanned: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val errors: List<String> = emptyList(),
)

class LibraryIndexer @JvmOverloads constructor(
    private val repository: CatalogRepository,
    private val readSnapshot: (File) -> IndexedDocumentSnapshot = IndexedDocumentReader()::read,
    private val discoveryOverride: ((Path, MutableList<String>) -> DiscoveryResult)? = null,
) {
    private val mutableProgress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = mutableProgress.asStateFlow()

    suspend fun scan(root: File) = withContext(Dispatchers.IO) {
        repository.withScanLock { scanLocked(root) }
    }

    private suspend fun scanLocked(root: File) {
        val errors = mutableListOf<String>()
        if (!root.isDirectory) {
            mutableProgress.value = ScanProgress(ScanStatus.COMPLETED, errors = listOf("Not a directory: $root"))
            return
        }
        val discovery = discoveryOverride?.invoke(root.toPath(), errors) ?: discover(root.toPath(), errors)
        val files = discovery.files
        val mirrorByPath = repository.restoreMirror().documents.associateBy { it.relativePath }
        val existingByPath = repository.all().associateBy { it.relativePath }
        val presentIds = mutableSetOf<String>()
        mutableProgress.value = ScanProgress(ScanStatus.SCANNING, total = files.size, errors = errors.toList())

        files.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            val relativePath = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            mutableProgress.value = ScanProgress(ScanStatus.SCANNING, index, files.size, relativePath, errors.toList())
            val existing = existingByPath[relativePath]
            existing?.let { presentIds += it.id }
            try {
                val size = file.length()
                val modifiedAt = file.lastModified()
                if (existing != null && existing.size == size && existing.modifiedAt == modifiedAt) {
                    // The existing FTS row and SHA are still valid; no body read or mirror write is needed.
                } else {
                    val snapshot = readSnapshot(file)
                    val body = snapshot.body
                    val fingerprint = snapshot.fingerprint
                    val restored = mirrorByPath[relativePath]
                    val id = existing?.id ?: restored?.id ?: UUID.randomUUID().toString()
                    val tags = if (existing != null) repository.tags(existing.id) else restored?.tags.orEmpty()
                    val favorite = existing?.favorite ?: restored?.favorite ?: false
                    repository.upsertDuringScan(
                        DocumentEntity(id, relativePath, titleFor(file, body), fingerprint.modifiedAt, fingerprint.size, fingerprint.sha256, favorite, existing?.lastOpenedAt),
                        body,
                        tags,
                    )
                    presentIds += id
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errors += "$relativePath: ${error.message ?: error.javaClass.simpleName}"
            }
            mutableProgress.value = ScanProgress(ScanStatus.SCANNING, index + 1, files.size, relativePath, errors.toList())
        }
        currentCoroutineContext().ensureActive()
        if (discovery.complete) repository.deleteMissingDuringScan(presentIds)
        else errors += "Discovery incomplete; retained records not enumerated during scan"
        repository.refreshMirrorDuringScan()
        mutableProgress.value = ScanProgress(ScanStatus.COMPLETED, files.size, files.size, errors = errors.toList())
    }

    data class DiscoveryResult(val files: List<File>, val complete: Boolean)

    private fun discover(root: Path, errors: MutableList<String>): DiscoveryResult {
        val files = mutableListOf<File>()
        var complete = true
        try { Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                return if (Files.isSymbolicLink(directory) || directory.fileName.toString() == "_MoNoteSystem") {
                    FileVisitResult.SKIP_SUBTREE
                } else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (!Files.isSymbolicLink(file) && attributes.isRegularFile && file.fileName.toString().substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS) {
                    files += file.toFile()
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exception: java.io.IOException): FileVisitResult {
                complete = false
                errors += "$file: ${exception.message ?: exception.javaClass.simpleName}"
                return FileVisitResult.CONTINUE
            }
        }) } catch (error: java.io.IOException) {
            complete = false
            errors += "$root: ${error.message ?: error.javaClass.simpleName}"
        }
        return DiscoveryResult(files.sortedBy { it.absolutePath }, complete)
    }

    private fun titleFor(file: File, body: String): String = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("#") }
        ?.trimStart('#', ' ')
        ?.takeIf { it.isNotBlank() }
        ?: file.name.substringBeforeLast('.')

    private companion object {
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
    }
}
