package app.monote.mobile.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class UnsafePathException(message: String) : IllegalArgumentException(message)

/**
 * Defends library operations against untrusted relative paths, but cannot prevent a process with
 * write access from swapping links after validation; that TOCTOU boundary is a Task14 follow-up.
 */
class SafePathPolicy(root: File) {
    constructor(root: Path) : this(root.toFile())

    private val canonicalRoot: Path = root.canonicalFile.toPath()

    fun resolve(relative: String): File {
        val relativePath = try {
            canonicalRoot.fileSystem.getPath(relative)
        } catch (error: InvalidPathException) {
            throw UnsafePathException("Invalid library path: $relative").also { it.initCause(error) }
        }
        if (relativePath.isAbsolute) {
            throw UnsafePathException("Absolute paths are not allowed: $relative")
        }

        val candidate = canonicalRoot.resolve(relativePath).normalize()
        if (!candidate.startsWith(canonicalRoot)) {
            throw UnsafePathException("Path escapes the library root: $relative")
        }

        val canonicalAncestor = try {
            existingAncestor(candidate).toRealPath()
        } catch (error: IOException) {
            throw UnsafePathException("Unable to resolve path safely: $relative").also { it.initCause(error) }
        } catch (error: SecurityException) {
            throw UnsafePathException("Unable to resolve path safely: $relative").also { it.initCause(error) }
        }
        if (!canonicalAncestor.startsWith(canonicalRoot)) {
            throw UnsafePathException("Path crosses a link outside the library root: $relative")
        }
        return candidate.toFile()
    }

    private fun existingAncestor(path: Path): Path {
        var ancestor: Path? = path
        while (ancestor != null && !Files.exists(ancestor, NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        return ancestor ?: throw UnsafePathException("Path has no existing ancestor: $path")
    }

    companion object {
        private val unsafeFileNameCharacters = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun sanitizeFileName(name: String): String {
            val sanitized = unsafeFileNameCharacters.replace(name.trim(), "_")
            return sanitized.ifBlank { "未命名.md" }
        }
    }
}
