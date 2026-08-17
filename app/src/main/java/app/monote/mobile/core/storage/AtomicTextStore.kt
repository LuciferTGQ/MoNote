package app.monote.mobile.core.storage

import java.io.File
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * Safely replaces trusted library files, but does not defend against a separate malicious process
 * replacing links after validation. ATOMIC_MOVE depends on the file system; fallback moves and the
 * lack of a parent-directory fsync do not provide a power-loss transaction (Task14 follow-up).
 * [beforeReplace] narrows external-change detection to the last application-level step before the
 * move, but another process can still race the operating-system move after that callback.
 * Fixed temporary paths are serialized with every path in one replacement; any same-thread nested
 * call is rejected with IllegalStateException to preserve the global lock ordering.
 */
class AtomicTextStore {
    fun replace(
        target: File,
        text: String,
        backup: File? = null,
        beforeReplace: (() -> Unit)? = null,
        beforeCommit: (() -> Unit)? = null,
    ) {
        val targetPath = target.toPath().toAbsolutePath().normalize()
        val backupPath = backup?.toPath()?.toAbsolutePath()?.normalize()
        val targetTemporary = temporaryPathFor(targetPath).toAbsolutePath().normalize()
        val backupTemporary = backupPath?.let(::temporaryPathFor)?.toAbsolutePath()?.normalize()
        val reservedPaths = listOfNotNull(targetPath, targetTemporary, backupPath, backupTemporary)
        require(reservedPaths.distinct().size == reservedPaths.size) {
            "Target, backup, and their temporary paths must be distinct"
        }

        val active = requireNotNull(activeTargets.get())
        check(active.isEmpty()) { "Reentrant replacement is not allowed" }
        active.addAll(reservedPaths)
        val lockIndices = reservedPaths.map(::lockIndex).distinct().sorted()
        lockIndices.forEach { locks[it].lock() }
        try {
            replaceText(
                targetPath,
                targetTemporary,
                text,
                backupPath,
                backupTemporary,
                beforeCommit,
                beforeReplace,
            )
        } finally {
            lockIndices.asReversed().forEach { locks[it].unlock() }
            active.removeAll(reservedPaths.toSet())
            if (active.isEmpty()) activeTargets.remove()
        }
    }

    private fun replaceText(
        target: Path,
        temporary: Path,
        text: String,
        backup: Path?,
        backupTemporary: Path?,
        beforeCommit: (() -> Unit)?,
        beforeReplace: (() -> Unit)?,
    ) {
        ensureTargetIsWritableFile(target)
        try {
            Files.deleteIfExists(temporary)
            writeTextAndVerify(temporary, text)
            beforeCommit?.invoke()

            if (backup != null && Files.exists(target)) {
                ensureTargetIsWritableFile(backup)
                val temporaryBackup = backupTemporary ?: error("Missing backup temporary path")
                try {
                    Files.deleteIfExists(temporaryBackup)
                    copyAndVerify(target, temporaryBackup)
                    moveReplacement(temporaryBackup, backup)
                } finally {
                    Files.deleteIfExists(temporaryBackup)
                }
            }
            beforeReplace?.invoke()
            moveReplacement(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun ensureTargetIsWritableFile(target: Path) {
        val parent = target.parent ?: throw IllegalArgumentException("Target must have a parent directory: $target")
        Files.createDirectories(parent)
        if (!Files.isDirectory(parent)) {
            throw IllegalStateException("Target parent is not a directory: $parent")
        }
        if (Files.exists(target) && Files.isDirectory(target)) {
            throw IllegalStateException("Target is a directory: $target")
        }
    }

    private fun temporaryPathFor(target: Path): Path = target.resolveSibling("${target.fileName}.monote-tmp")

    private fun writeTextAndVerify(path: Path, text: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, CREATE, TRUNCATE_EXISTING, WRITE).use { channel ->
            OutputStreamWriter(DigestOutputStream(Channels.newOutputStream(channel), digest), Charsets.UTF_8).use { writer ->
                writer.write(text)
            }
        }
        FileChannel.open(path, WRITE).use { channel ->
            channel.force(true)
        }
        val expected = DocumentFingerprint.lowerCaseHex(digest.digest())
        check(DocumentFingerprint.sha256File(path.toFile()) == expected) {
            "Temporary file checksum mismatch: $path"
        }
    }

    private fun copyAndVerify(source: Path, temporary: Path) {
        val expected = DocumentFingerprint.sha256File(source.toFile())
        FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE).use { output ->
            Files.newInputStream(source).use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val bytes = ByteBuffer.wrap(buffer, 0, count)
                    while (bytes.hasRemaining()) output.write(bytes)
                }
            }
            output.force(true)
        }
        check(DocumentFingerprint.sha256File(temporary.toFile()) == expected) {
            "Backup checksum mismatch: $temporary"
        }
    }

    private fun moveReplacement(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    }

    private companion object {
        private const val COPY_BUFFER_SIZE = 32 * 1024
        private val locks = Array(64) { ReentrantLock() }
        private val activeTargets = ThreadLocal.withInitial { mutableSetOf<Path>() }

        private fun lockIndex(path: Path): Int = (path.hashCode() and Int.MAX_VALUE) % locks.size
    }
}
