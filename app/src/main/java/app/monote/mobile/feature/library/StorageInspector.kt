package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.LibraryDirectories
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal fun interface StorageTreeReader {
    fun measure(
        directory: Path,
        excluded: Set<Path>,
        include: (Path, BasicFileAttributes) -> Boolean,
        warning: (Path, IOException) -> Unit,
    ): Long
}

class StorageInspector internal constructor(
    private val paths: LibraryDirectories,
    private val cacheDirectory: File,
    private val reader: StorageTreeReader,
) {
    constructor(paths: LibraryDirectories, cacheDirectory: File) : this(
        paths,
        cacheDirectory,
        NioStorageTreeReader,
    )

    fun measure(): StorageBreakdown {
        val systemPath = paths.system.toPath().toAbsolutePath().normalize()
        val warnings = mutableListOf<String>()
        return StorageBreakdown(
            documentsBytes = measureCategory("documents", paths.root.toPath(), setOf(systemPath), warnings, ::isMarkdown),
            attachmentsBytes = measureCategory("attachments", paths.root.toPath(), setOf(systemPath), warnings) { path, attributes ->
                attributes.isRegularFile && !isMarkdown(path, attributes)
            },
            backupsBytes = measureCategory("backups", paths.backups.toPath(), emptySet(), warnings),
            trashBytes = measureCategory("trash", paths.trash.toPath(), emptySet(), warnings),
            recoveryBytes = measureCategory("recovery", paths.recovery.toPath(), emptySet(), warnings),
            cacheBytes = measureCategory("cache", cacheDirectory.toPath(), emptySet(), warnings),
            warnings = warnings,
        )
    }

    private fun measureCategory(
        category: String,
        directory: Path,
        excluded: Set<Path>,
        warnings: MutableList<String>,
        include: (Path, BasicFileAttributes) -> Boolean = { _, attributes -> attributes.isRegularFile },
    ): Long {
        val report: (Path, IOException) -> Unit = { path, error ->
            warnings += "$category: $path: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
        return try {
            reader.measure(directory, excluded, include, report)
        } catch (error: Exception) {
            warnings += "$category: $directory: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            0L
        }
    }
}

private object NioStorageTreeReader : StorageTreeReader {
    override fun measure(
        directory: Path,
        excluded: Set<Path>,
        include: (Path, BasicFileAttributes) -> Boolean,
        warning: (Path, IOException) -> Unit,
    ): Long {
        val normalized = directory.toAbsolutePath().normalize()
        if (!Files.exists(normalized, NOFOLLOW_LINKS)) return 0L
        if (!Files.isDirectory(normalized, NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            warning(normalized, IOException("Storage category root is not a real directory"))
            return 0L
        }
        var total = 0L
        Files.walkFileTree(normalized, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                val current = path.toAbsolutePath().normalize()
                return if (current in excluded || Files.isSymbolicLink(path)) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (!Files.isSymbolicLink(path) && include(path, attributes)) total += attributes.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, exception: IOException): FileVisitResult {
                warning(path, exception)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                if (exception != null) warning(directory, exception)
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }
}

private fun isMarkdown(path: Path, attributes: BasicFileAttributes): Boolean {
    if (!attributes.isRegularFile) return false
    val name = path.fileName.toString().lowercase()
    return name.endsWith(".md") || name.endsWith(".markdown")
}
