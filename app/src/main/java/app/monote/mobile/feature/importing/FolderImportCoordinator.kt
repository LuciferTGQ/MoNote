package app.monote.mobile.feature.importing

import app.monote.mobile.core.storage.SafePathPolicy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class TreeDocument(
    val id: String,
    val displayName: String,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val size: Long? = null,
)

interface TreeDocumentSource {
    val displayName: String

    /** Visits at most [limit] direct children and returns true when more children exist. */
    suspend fun visitChildren(
        parentId: String?,
        limit: Int,
        visitor: suspend (TreeDocument) -> Unit,
    ): Boolean

    suspend fun open(document: TreeDocument): InputStream
}

data class ImportedFolder(val directory: File, val firstMarkdown: File)

class FolderImportCoordinator(
    private val limits: FolderImportLimits = FolderImportLimits(),
) {
    suspend fun import(source: TreeDocumentSource, destination: File): ImportedFolder = withContext(Dispatchers.IO) {
        val destinationPath = destination.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(destinationPath)) { "Import destination is not a directory: $destinationPath" }
        val staging = Files.createDirectory(destinationPath.resolve(".monote-folder-import-${UUID.randomUUID()}"))
        val state = ImportState(staging)
        try {
            copyChildren(source, null, staging, 0, state)
            val firstRelative = state.firstMarkdown ?: throw IOException("文件夹中没有 Markdown 文件")
            val finalDirectory = commitDirectory(staging, destinationPath, safeDirectoryName(source.displayName))
            ImportedFolder(finalDirectory.toFile(), finalDirectory.resolve(firstRelative).toFile())
        } catch (failure: Exception) {
            deleteTree(staging)
            throw failure
        }
    }

    private suspend fun copyChildren(
        source: TreeDocumentSource,
        parentId: String?,
        target: Path,
        depth: Int,
        state: ImportState,
    ) {
        require(depth <= limits.maxDepth) { "文件夹层级过深" }
        val remaining = limits.maxEntries - state.entries
        require(remaining > 0) { "文件数量超过限制" }
        val truncated = source.visitChildren(parentId, remaining) { child ->
            currentCoroutineContext().ensureActive()
            state.entries += 1
            require(state.entries <= limits.maxEntries) { "文件数量超过限制" }
            val safeName = safeChildName(child.displayName)
            val childTarget = target.resolve(safeName).normalize()
            require(childTarget.parent == target) { "文件名不安全: ${child.displayName}" }
            if (child.isDirectory) {
                Files.createDirectory(childTarget)
                copyChildren(source, child.id, childTarget, depth + 1, state)
            } else {
                copyFile(source, child, childTarget, state)
            }
        }
        require(!truncated) { "文件数量超过限制" }
    }

    private suspend fun copyFile(source: TreeDocumentSource, document: TreeDocument, target: Path, state: ImportState) {
        document.size?.let { require(it <= limits.maxSingleFileBytes) { "单个文件过大: ${document.displayName}" } }
        var fileBytes = 0L
        source.open(document).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    fileBytes += count
                    state.totalBytes += count
                    require(fileBytes <= limits.maxSingleFileBytes) { "单个文件过大: ${document.displayName}" }
                    require(state.totalBytes <= limits.maxTotalBytes) { "文件夹总大小超过限制" }
                    output.write(buffer, 0, count)
                }
            }
        }
        if (target.fileName.toString().lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }) {
            if (state.firstMarkdown == null) state.firstMarkdown = state.stagingRoot.relativize(target)
        }
    }

    private fun safeDirectoryName(name: String): String = safeChildName(name).ifBlank { "导入文件夹" }

    private fun safeChildName(name: String): String {
        val safe = SafePathPolicy.sanitizeFileName(name)
        require(safe != "." && safe != ".." && !safe.equals("_MoNoteSystem", ignoreCase = true)) {
            "文件名不安全: $name"
        }
        return safe
    }

    private fun commitDirectory(staging: Path, parent: Path, requestedName: String): Path {
        var suffix = 1
        while (true) {
            val name = if (suffix == 1) requestedName else "$requestedName ($suffix)"
            val candidate = parent.resolve(name)
            try {
                // Staging is created in [parent], so this is a same-filesystem rename.
                // Omitting REPLACE_EXISTING keeps an existing import safe under races.
                return Files.move(staging, candidate)
            } catch (_: FileAlreadyExistsException) {
                suffix += 1
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private data class ImportState(
        val stagingRoot: Path,
        var entries: Int = 0,
        var totalBytes: Long = 0,
        var firstMarkdown: Path? = null,
    )
}

data class FolderImportLimits(
    val maxDepth: Int = 32,
    val maxEntries: Int = 10_000,
    val maxSingleFileBytes: Long = 100L * 1024 * 1024,
    val maxTotalBytes: Long = 500L * 1024 * 1024,
)
