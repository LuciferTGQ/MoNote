package app.monote.mobile.core.storage

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

class ConcurrentDocumentModificationException(message: String) : IOException(message)

data class DocumentFingerprint(
    val size: Long,
    val modifiedAt: Long,
    val sha256: String,
) {
    companion object {
        private const val MAX_SNAPSHOT_ATTEMPTS = 3
        private const val BUFFER_SIZE = 32 * 1024

        fun from(file: File): DocumentFingerprint {
            val path = file.toPath()
            repeat(MAX_SNAPSHOT_ATTEMPTS) {
                val before = snapshot(path)
                val hash = sha256File(file)
                val after = snapshot(path)
                if (before == after) {
                    return DocumentFingerprint(before.size, before.modifiedAt.toMillis(), hash)
                }
            }
            throw ConcurrentDocumentModificationException("File changed while fingerprinting: $file")
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toLowerCaseHex()

        fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

        internal fun sha256File(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(Files.newInputStream(file.toPath()), BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toLowerCaseHex()
        }

        internal fun lowerCaseHex(bytes: ByteArray): String = bytes.toLowerCaseHex()

        internal fun ByteArray.toLowerCaseHex(): String = buildString(size * 2) {
            for (byte in this@toLowerCaseHex) {
                append(HEX[byte.toInt().ushr(4) and 0x0f])
                append(HEX[byte.toInt() and 0x0f])
            }
        }

        private fun snapshot(path: Path): Snapshot {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            return Snapshot(
                size = attributes.size(),
                modifiedAt = attributes.lastModifiedTime(),
                fileKey = attributes.fileKey(),
            )
        }

        private data class Snapshot(
            val size: Long,
            val modifiedAt: FileTime,
            val fileKey: Any?,
        )

        private const val HEX = "0123456789abcdef"
    }
}
