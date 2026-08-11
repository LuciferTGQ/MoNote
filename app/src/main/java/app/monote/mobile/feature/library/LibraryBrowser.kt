package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.LibraryDirectories
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class LibraryBrowser(paths: LibraryDirectories) {
    private val guard = LibraryPathGuard(paths)
    val root: File = paths.root.toPath().toAbsolutePath().normalize().toFile()

    fun folder(candidate: File): File {
        val path = guard.contentPath(candidate, allowRoot = true, requireExists = true)
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Not a library directory: $path" }
        return path.toFile()
    }

    fun folders(parent: File): List<File> {
        val safeParent = folder(parent).toPath()
        return Files.newDirectoryStream(safeParent).use { stream ->
            stream.asSequence()
                .filter { Files.isDirectory(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .map { guard.contentPath(it.toFile(), requireExists = true).toFile() }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
    }

    fun catalogFile(relativePath: String): File {
        val candidate = root.resolve(relativePath)
        return guard.contentPath(candidate, requireExists = true).toFile()
    }

    fun moveDestination(relativePath: String): File =
        if (relativePath.isBlank()) root else folder(root.resolve(relativePath))
}
