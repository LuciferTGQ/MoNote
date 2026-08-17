package app.monote.mobile.core.storage

import java.io.File
import java.nio.file.Path

data class LibraryDirectories(
    val root: File,
    val inbox: File,
    val system: File,
    val recovery: File,
    val backups: File,
    val trash: File,
)

class LibraryPaths(private val root: File) {
    constructor(root: Path) : this(root.toFile())

    fun ensureCreated(): LibraryDirectories {
        val absoluteRoot = root.absoluteFile
        val system = absoluteRoot.resolve("_MoNoteSystem")
        return LibraryDirectories(
            root = ensureDirectory(absoluteRoot),
            inbox = ensureDirectory(absoluteRoot.resolve("收件箱")),
            system = ensureDirectory(system),
            recovery = ensureDirectory(system.resolve("recovery")),
            backups = ensureDirectory(system.resolve("backups")),
            trash = ensureDirectory(system.resolve("trash")),
        )
    }

    private fun ensureDirectory(directory: File): File {
        if (directory.exists() && !directory.isDirectory) {
            throw IllegalStateException("Library path is occupied by a file: $directory")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create library directory: $directory")
        }
        return directory
    }
}
