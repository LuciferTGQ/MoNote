package app.monote.mobile.data.catalog

import app.monote.mobile.core.storage.ConcurrentDocumentModificationException
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.feature.importing.TextDecoder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

data class IndexedDocumentSnapshot(
    val body: String,
    val fingerprint: DocumentFingerprint,
)

internal class IndexedDocumentReader(
    private val decoder: TextDecoder = TextDecoder(),
    private val afterRead: (File) -> Unit = {},
) {
    fun read(file: File): IndexedDocumentSnapshot {
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val before = attributes(file.toPath())
            val bytes = Files.newInputStream(file.toPath()).use(decoder::readBytes)
            afterRead(file)
            val after = attributes(file.toPath())
            if (before == after) {
                return IndexedDocumentSnapshot(
                    body = decoder.decode(bytes).text,
                    fingerprint = DocumentFingerprint(
                        size = before.size,
                        modifiedAt = before.modifiedAt.toMillis(),
                        sha256 = DocumentFingerprint.sha256(bytes),
                    ),
                )
            }
        }
        throw ConcurrentDocumentModificationException("File changed while reading index snapshot: $file")
    }

    private fun attributes(path: Path): SnapshotAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return SnapshotAttributes(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }

    private data class SnapshotAttributes(
        val size: Long,
        val modifiedAt: FileTime,
        val fileKey: Any?,
    )

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 3
    }
}
