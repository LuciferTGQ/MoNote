package app.monote.mobile.feature.importing

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.SafePathPolicy
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface FileNamePolicy {
    fun sanitize(displayName: String): String
}

internal interface ImportFileOperations {
    fun createTempFile(directory: Path, prefix: String, suffix: String): Path
    fun createFile(path: Path): Path
    fun moveReplacing(source: Path, target: Path): Path
    fun deleteIfExists(path: Path): Boolean
}

private object NioImportFileOperations : ImportFileOperations {
    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
        Files.createTempFile(directory, prefix, suffix)

    override fun createFile(path: Path): Path = Files.createFile(path)

    override fun moveReplacing(source: Path, target: Path): Path = try {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, REPLACE_EXISTING)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)
}

class ImportCoordinator private constructor(
    private val pathPolicy: FileNamePolicy,
    private val decoder: TextDecoder,
    private val store: AtomicTextStore,
    private val fileOperations: ImportFileOperations,
    @Suppress("UNUSED_PARAMETER") constructorToken: Unit,
) {
    constructor(
        pathPolicy: FileNamePolicy = FileNamePolicy(SafePathPolicy::sanitizeFileName),
        decoder: TextDecoder = TextDecoder(),
        store: AtomicTextStore = AtomicTextStore(),
    ) : this(pathPolicy, decoder, store, NioImportFileOperations, Unit)

    internal constructor(
        pathPolicy: FileNamePolicy = FileNamePolicy(SafePathPolicy::sanitizeFileName),
        decoder: TextDecoder = TextDecoder(),
        store: AtomicTextStore = AtomicTextStore(),
        fileOperations: ImportFileOperations,
    ) : this(pathPolicy, decoder, store, fileOperations, Unit)

    suspend fun import(source: DocumentSource, destination: File): ImportedDocument {
        val cleanupState = CleanupState()
        return try {
            withContext(Dispatchers.IO) { importOnIo(source, destination, cleanupState) }
        } catch (error: Exception) {
            cleanupState.failure?.let(error::addSuppressed)
            throw error
        }
    }

    private suspend fun importOnIo(
        source: DocumentSource,
        destination: File,
        cleanupState: CleanupState,
    ): ImportedDocument {
        val fileName = SafePathPolicy.sanitizeFileName(pathPolicy.sanitize(source.displayName))
        val nameParts = markdownNameParts(fileName)
        val destinationPath = ensureDirectory(destination)
        val decoded = source.open().use { decoder.decode(it) }
        currentCoroutineContext().ensureActive()

        val staging = fileOperations.createTempFile(destinationPath, ".monote-import-", ".staging")
        var reservation: Path? = null
        var finalPath: Path? = null
        var committed = false
        var primaryFailure: Exception? = null
        var cleanupFailure: Exception? = null
        try {
            currentCoroutineContext().ensureActive()
            store.replace(staging.toFile(), decoded.text)
            reservation = reserveAvailableName(destinationPath, nameParts)
            currentCoroutineContext().ensureActive()
            finalPath = fileOperations.moveReplacing(staging, requireNotNull(reservation))
            committed = true
        } catch (error: Exception) {
            primaryFailure = error
        } finally {
            cleanupFailure = cleanUp(staging, reservation, committed)
            cleanupState.failure = cleanupFailure
        }

        val failure = primaryFailure
        if (failure != null) {
            throw failure
        }

        check(committed) { "Import did not commit" }
        val warnings = if (cleanupFailure == null) decoded.warnings else decoded.warnings + "Temporary cleanup failed"
        return ImportedDocument(requireNotNull(finalPath).toFile(), decoded.encoding, warnings)
    }

    private fun markdownNameParts(fileName: String): NameParts {
        val extensionIndex = fileName.lastIndexOf('.')
        val extension = if (extensionIndex >= 0) fileName.substring(extensionIndex).lowercase() else ""
        if (extension != ".md" && extension != ".markdown") {
            throw UnsupportedDocumentTypeException(fileName)
        }
        return NameParts(fileName.substring(0, extensionIndex), fileName.substring(extensionIndex))
    }

    private fun ensureDirectory(destination: File): Path {
        val path = destination.toPath().toAbsolutePath().normalize()
        try {
            Files.createDirectories(path)
        } catch (error: Exception) {
            throw ImportException("Unable to create import destination: $path", error)
        }
        if (!Files.isDirectory(path)) {
            throw ImportException("Import destination is not a directory: $path")
        }
        return path
    }

    private fun reserveAvailableName(destination: Path, name: NameParts): Path {
        var number = 1
        while (true) {
            val candidate = destination.resolve(if (number == 1) "${name.base}${name.extension}" else "${name.base} ($number)${name.extension}")
            try {
                return fileOperations.createFile(candidate)
            } catch (_: FileAlreadyExistsException) {
                number += 1
            }
        }
    }

    private fun cleanUp(staging: Path, reservation: Path?, committed: Boolean): Exception? {
        var failure: Exception? = null
        val paths = buildList {
            add(staging.resolveSibling("${staging.fileName}.monote-tmp"))
            add(staging)
            if (!committed && reservation != null) add(reservation)
        }
        paths.forEach { path ->
            try {
                fileOperations.deleteIfExists(path)
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        return failure
    }

    private data class NameParts(val base: String, val extension: String)

    private class CleanupState(var failure: Exception? = null)
}
