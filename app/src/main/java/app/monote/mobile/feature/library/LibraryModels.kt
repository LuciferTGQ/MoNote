package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.core.storage.SafePathPolicy
import app.monote.mobile.core.storage.UnsafePathException
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

sealed interface LibraryResult {
    data class Success(
        val file: File,
        val catalogSynchronized: Boolean = true,
        val warnings: List<String> = emptyList(),
    ) : LibraryResult

    data class Conflict(val existing: File) : LibraryResult
    data class Failure(val message: String, val cause: Throwable) : LibraryResult
}

sealed interface BatchMoveResult {
    data class Success(
        val files: List<File>,
        val catalogSynchronized: Boolean = true,
    ) : BatchMoveResult

    data class Conflict(val existing: File) : BatchMoveResult

    data class Failure(
        val message: String,
        val cause: Throwable,
        val rolledBack: Boolean,
        val recoveryRecords: List<MoveRecoveryRecord> = emptyList(),
        val recoveryPersistenceFailure: String? = null,
    ) : BatchMoveResult
}

data class MoveRecoveryRecord(
    val original: File,
    val current: File,
    val message: String,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
)

data class TrashEntry(
    val stableId: String,
    val originalRelativePath: String,
    val deletedAt: Instant,
    val trashedFile: File,
    val metadataFile: File,
    val catalogSynchronized: Boolean = true,
    val documents: List<TrashDocumentMetadata> = emptyList(),
    val state: TrashEntryState = TrashEntryState.TRASHED,
    val pendingTargetRelativePath: String? = null,
    val pendingTargetFile: File? = null,
    val pendingRestoreTrusted: Boolean = false,
)

@Serializable
enum class TrashEntryState {
    TRASHED,
    PENDING_RESTORE,
}

@Serializable
data class TrashDocumentMetadata(
    val id: String,
    val originalRelativePath: String,
    val title: String,
    val favorite: Boolean = false,
    val tags: Set<String> = emptySet(),
    val lastOpenedAt: Long? = null,
    val size: Long? = null,
    val sha256: String? = null,
)

sealed interface TrashDeleteResult {
    data class Success(val deletedStableIds: Set<String>) : TrashDeleteResult
    data object ConfirmationRequired : TrashDeleteResult
    data class NotFound(val stableId: String) : TrashDeleteResult
    data class Failure(val message: String, val cause: Throwable) : TrashDeleteResult
}

data class StorageBreakdown(
    val documentsBytes: Long,
    val trashBytes: Long,
    val recoveryBytes: Long,
    val cacheBytes: Long,
    val warnings: List<String> = emptyList(),
    val attachmentsBytes: Long = 0,
    val backupsBytes: Long = 0,
)

@Serializable
internal data class TrashMetadata(
    val stableId: String,
    val originalRelativePath: String,
    val deletedAt: String,
    val documents: List<TrashDocumentMetadata> = emptyList(),
    val state: TrashEntryState = TrashEntryState.TRASHED,
    val pendingTargetRelativePath: String? = null,
)

internal class LibraryPathGuard(private val directories: LibraryDirectories) {
    private val root = directories.root.toPath().toAbsolutePath().normalize()
    private val system = directories.system.toPath().toAbsolutePath().normalize()
    private val safePathPolicy = SafePathPolicy(directories.root)

    init {
        if (Files.isSymbolicLink(root)) throw UnsafePathException("Library root cannot be a symbolic link: $root")
    }

    fun contentPath(file: File, allowRoot: Boolean = false, requireExists: Boolean = false): Path {
        val candidate = file.toPath().toAbsolutePath().normalize()
        if (!candidate.startsWith(root) || (!allowRoot && candidate == root)) {
            throw UnsafePathException("Path is outside the library content root: $candidate")
        }
        if (candidate.startsWith(system)) {
            throw UnsafePathException("System storage is not user library content: $candidate")
        }
        validateWithPolicy(candidate)
        rejectSymbolicLinks(candidate)
        if (requireExists && !Files.exists(candidate, NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("Library path does not exist: $candidate")
        }
        return candidate
    }

    fun relativeContentPath(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/')

    fun trashEntryRoot(stableId: String): Path {
        requireValidStableId(stableId)
        val trash = directories.trash.toPath().toAbsolutePath().normalize()
        rejectSymbolicLinks(trash)
        val entryRoot = trash.resolve(stableId).normalize()
        if (entryRoot.parent != trash) throw UnsafePathException("Invalid trash entry ID: $stableId")
        rejectSymbolicLinks(entryRoot)
        return entryRoot
    }

    fun trashContentPath(stableId: String, originalRelativePath: String): Path {
        val entryRoot = trashEntryRoot(stableId)
        val contentRoot = entryRoot.resolve("content")
        val relative = try {
            contentRoot.fileSystem.getPath(originalRelativePath.replace('/', File.separatorChar))
        } catch (error: InvalidPathException) {
            throw UnsafePathException("Invalid original trash path: $originalRelativePath").also {
                it.initCause(error)
            }
        }
        if (relative.isAbsolute) throw UnsafePathException("Original trash path cannot be absolute")
        val candidate = contentRoot.resolve(relative).normalize()
        if (!candidate.startsWith(contentRoot) || candidate == contentRoot) {
            throw UnsafePathException("Original trash path escapes its entry: $originalRelativePath")
        }
        rejectSymbolicLinks(candidate)
        return candidate
    }

    fun requireSimpleName(name: String): String {
        val trimmed = name.trim()
        if (
            trimmed.isEmpty() ||
            trimmed == "." ||
            trimmed == ".." ||
            trimmed.endsWith('.') ||
            SafePathPolicy.sanitizeFileName(trimmed) != trimmed
        ) {
            throw UnsafePathException("Invalid library item name: $name")
        }
        return trimmed
    }

    private fun validateWithPolicy(candidate: Path) {
        val relative = root.relativize(candidate).toString()
        val resolved = safePathPolicy.resolve(relative).toPath().toAbsolutePath().normalize()
        if (resolved != candidate) throw UnsafePathException("Path does not resolve inside the library: $candidate")
    }

    private fun rejectSymbolicLinks(candidate: Path) {
        if (!candidate.startsWith(root)) throw UnsafePathException("Path is outside the library root: $candidate")
        var current = root
        if (Files.isSymbolicLink(current)) throw UnsafePathException("Symbolic links are not allowed: $current")
        root.relativize(candidate).forEach { component ->
            current = current.resolve(component)
            if (Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw UnsafePathException("Symbolic links are not allowed: $current")
            }
        }
    }

    private fun requireValidStableId(stableId: String) {
        if (stableId == "." || stableId == ".." || !STABLE_ID.matches(stableId)) {
            throw UnsafePathException("Invalid stable ID: $stableId")
        }
    }

    private companion object {
        val STABLE_ID = Regex("[A-Za-z0-9._-]+")
    }
}
