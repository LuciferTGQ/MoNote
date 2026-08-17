package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Stores pending-restore trust markers outside user-visible library storage.
 *
 * Callers must provide an app-private directory such as Context.noBackupFilesDir.
 */
class PendingRestoreTrustStore(
    directory: File,
    private val textStore: AtomicTextStore = AtomicTextStore(),
) {
    private val root = directory.toPath().toAbsolutePath().normalize()

    fun trust(stableId: String, canonicalMetadata: String) {
        val marker = marker(stableId)
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root)) { "Pending restore trust directory cannot be a symbolic link" }
        textStore.replace(marker.toFile(), digest(canonicalMetadata))
    }

    fun matches(stableId: String, canonicalMetadata: String): Boolean {
        val marker = marker(stableId)
        if (
            !Files.isRegularFile(marker, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(marker) ||
            Files.size(marker) > MAX_MARKER_BYTES
        ) {
            return false
        }
        return try {
            val actual = Files.newBufferedReader(marker, Charsets.UTF_8)
                .use { it.readText() }
                .trim()
                .toByteArray(Charsets.UTF_8)
            val expected = digest(canonicalMetadata).toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(actual, expected)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun remove(stableId: String) {
        val marker = marker(stableId)
        if (Files.isSymbolicLink(root)) {
            throw IllegalStateException("Pending restore trust directory cannot be a symbolic link")
        }
        Files.deleteIfExists(marker)
    }

    private fun marker(stableId: String): Path {
        require(STABLE_ID.matches(stableId)) { "Invalid pending restore stable ID: $stableId" }
        val marker = root.resolve("$stableId.sha256").normalize()
        check(marker.parent == root) { "Pending restore marker escapes its private directory" }
        return marker
    }

    private fun digest(canonicalMetadata: String): String =
        DocumentFingerprint.sha256(canonicalMetadata)

    private companion object {
        const val MAX_MARKER_BYTES = 128L
        val STABLE_ID = Regex("[A-Za-z0-9._-]+")
    }
}
