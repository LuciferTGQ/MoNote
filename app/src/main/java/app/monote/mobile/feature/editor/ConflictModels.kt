package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.ConcurrentDocumentModificationException
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryDirectories
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ExternalDocumentVersion {
    data class Present(
        val fingerprint: DocumentFingerprint,
        val text: String,
    ) : ExternalDocumentVersion {
        init {
            require(fingerprint.sha256 == DocumentFingerprint.sha256(text)) {
                "External text does not match its fingerprint"
            }
        }
    }

    data object Deleted : ExternalDocumentVersion
}

data class SavedDocumentCopy(
    val file: File,
    val fingerprint: DocumentFingerprint,
)

data class LineDiffHunk(
    val oldStart: Int,
    val newStart: Int,
    val removed: List<String>,
    val added: List<String>,
)

class ConflictResolver(
    private val paths: LibraryDirectories,
    private val textStore: AtomicTextStore = AtomicTextStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun keepMine(session: DocumentSession): DocumentSaveResult.Saved =
        withContext(ioDispatcher) {
            val backup = session.file.takeIf(File::isFile)?.let {
                paths.backups.resolve("${requireSafeDocumentId(session.id)}.md")
            }
            textStore.replace(session.file, session.text, backup = backup)
            val fingerprint = DocumentFingerprint.from(session.file)
            check(fingerprint.sha256 == session.contentSha256)
            DocumentSaveResult.Saved(fingerprint)
        }

    suspend fun loadExternal(session: DocumentSession): ExternalDocumentVersion =
        withContext(ioDispatcher) { readExternalDocument(session.file) }

    suspend fun saveCopy(session: DocumentSession): SavedDocumentCopy =
        withContext(ioDispatcher) {
            val parent = requireNotNull(session.file.parentFile) {
                "Document must have a parent directory"
            }
            Files.createDirectories(parent.toPath())
            for (index in 1..MAX_COPY_ATTEMPTS) {
                val candidate = parent.resolve(copyName(session.file, index))
                try {
                    Files.createFile(candidate.toPath())
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
                try {
                    textStore.replace(candidate, session.text)
                } catch (error: Exception) {
                    Files.deleteIfExists(candidate.toPath())
                    throw error
                }
                val fingerprint = DocumentFingerprint.from(candidate)
                return@withContext SavedDocumentCopy(candidate, fingerprint)
            }
            error("Unable to allocate a unique sibling copy")
        }

    fun viewDiff(
        session: DocumentSession,
        external: ExternalDocumentVersion,
    ): List<LineDiffHunk> = lineDiff(
        oldText = (external as? ExternalDocumentVersion.Present)?.text.orEmpty(),
        newText = session.text,
    )

    private fun copyName(file: File, index: Int): String {
        val extension = file.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val base = file.name.removeSuffix(extension)
        val suffix = if (index == 1) " (副本)" else " (副本 $index)"
        return "$base$suffix$extension"
    }

    private companion object {
        const val MAX_COPY_ATTEMPTS = 10_000
    }
}

internal fun readExternalDocument(file: File): ExternalDocumentVersion {
    if (!file.isFile) return ExternalDocumentVersion.Deleted
    repeat(3) {
        val before = DocumentFingerprint.from(file)
        val text = file.readText(Charsets.UTF_8)
        val after = DocumentFingerprint.from(file)
        if (
            before == after &&
            after.sha256 == DocumentFingerprint.sha256(text)
        ) {
            return ExternalDocumentVersion.Present(after, text)
        }
    }
    throw ConcurrentDocumentModificationException(
        "File changed while reading external version: $file",
    )
}

fun lineDiff(oldText: String, newText: String): List<LineDiffHunk> {
    if (oldText == newText) return emptyList()
    val oldLines = oldText.takeIf(String::isNotEmpty)?.split('\n').orEmpty()
    val newLines = newText.takeIf(String::isNotEmpty)?.split('\n').orEmpty()
    if (oldLines.size.toLong() * newLines.size.toLong() > MAX_DIFF_MATRIX_CELLS) {
        return listOf(LineDiffHunk(1, 1, oldLines, newLines))
    }

    val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (oldIndex in oldLines.indices.reversed()) {
        for (newIndex in newLines.indices.reversed()) {
            lcs[oldIndex][newIndex] = if (oldLines[oldIndex] == newLines[newIndex]) {
                lcs[oldIndex + 1][newIndex + 1] + 1
            } else {
                maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
            }
        }
    }

    val hunks = mutableListOf<LineDiffHunk>()
    var oldIndex = 0
    var newIndex = 0
    var oldStart = 1
    var newStart = 1
    var removed = mutableListOf<String>()
    var added = mutableListOf<String>()

    fun flush() {
        if (removed.isEmpty() && added.isEmpty()) return
        hunks += LineDiffHunk(oldStart, newStart, removed, added)
        removed = mutableListOf()
        added = mutableListOf()
    }

    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        if (
            oldIndex < oldLines.size &&
            newIndex < newLines.size &&
            oldLines[oldIndex] == newLines[newIndex]
        ) {
            flush()
            oldIndex++
            newIndex++
            continue
        }
        if (removed.isEmpty() && added.isEmpty()) {
            oldStart = oldIndex + 1
            newStart = newIndex + 1
        }
        if (
            newIndex < newLines.size &&
            (oldIndex == oldLines.size || lcs[oldIndex][newIndex + 1] > lcs[oldIndex + 1][newIndex])
        ) {
            added += newLines[newIndex++]
        } else {
            removed += oldLines[oldIndex++]
        }
    }
    flush()
    return hunks
}

private const val MAX_DIFF_MATRIX_CELLS = 4_000_000L
