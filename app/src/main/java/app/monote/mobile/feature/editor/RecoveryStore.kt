package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RecoveryMetadata(
    val version: Int = 1,
    val stableId: String,
    val originalPath: String,
    val baselineSha256: String,
    val draftSha256: String,
    val draftFileName: String,
    val updatedAt: Long,
)

data class RecoveryDraft(
    val metadata: RecoveryMetadata,
    val text: String,
)

class RecoveryStore(
    directory: File,
    private val textStore: AtomicTextStore = AtomicTextStore(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecoveryDraftSink {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    override suspend fun write(session: DocumentSession) = withContext(ioDispatcher) {
        ensureRoot()
        if (!utf8LengthAtMost(session.text, MAX_DRAFT_BYTES)) {
            throw IOException("Recovery draft exceeds the supported size")
        }
        val stableId = requireSafeDocumentId(session.id)
        val draftSha256 = session.contentSha256
        val draftFileName = "$stableId.$draftSha256.md"
        val draftFile = child(draftFileName)
        ensureNotSymbolicLink(draftFile)
        textStore.replace(draftFile, session.text)

        val metadata = RecoveryMetadata(
            stableId = stableId,
            originalPath = session.file.absoluteFile.normalize().path,
            baselineSha256 = session.baseline.sha256,
            draftSha256 = draftSha256,
            draftFileName = draftFileName,
            updatedAt = nowMillis(),
        )
        val metadataFile = child("$stableId.json")
        ensureNotSymbolicLink(metadataFile)
        textStore.replace(metadataFile, json.encodeToString(metadata))
        removeObsoleteDrafts(stableId, keep = draftFileName)
    }

    suspend fun read(stableId: String): RecoveryDraft? = withContext(ioDispatcher) {
        val safeId = requireSafeDocumentId(stableId)
        ensureRoot()
        val metadataFile = child("$safeId.json")
        if (!isReadableRegularFile(metadataFile, MAX_METADATA_BYTES)) return@withContext null
        try {
            val metadata = json.decodeFromString<RecoveryMetadata>(
                metadataFile.readText(Charsets.UTF_8),
            )
            if (!metadata.isValidFor(safeId)) return@withContext null
            val draftFile = child(metadata.draftFileName)
            if (!isReadableRegularFile(draftFile, MAX_DRAFT_BYTES)) return@withContext null
            val text = draftFile.readText(Charsets.UTF_8)
            if (DocumentFingerprint.sha256(text) != metadata.draftSha256) {
                return@withContext null
            }
            RecoveryDraft(metadata, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun candidate(stableId: String, original: File): RecoveryDraft? {
        val draft = read(stableId) ?: return null
        if (draft.metadata.originalPath != original.absoluteFile.normalize().path) return null
        if (!original.isFile) return draft
        val originalFingerprint = withContext(ioDispatcher) {
            DocumentFingerprint.from(original)
        }
        if (draft.metadata.draftSha256 == originalFingerprint.sha256) return null
        return draft.takeIf { it.metadata.updatedAt > originalFingerprint.modifiedAt }
    }

    override suspend fun delete(stableId: String) = withContext(ioDispatcher) {
        val safeId = requireSafeDocumentId(stableId)
        ensureRoot()
        Files.deleteIfExists(child("$safeId.json").toPath())
        root.toFile().listFiles().orEmpty()
            .filter { it.name.startsWith("$safeId.") && it.extension == "md" }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun RecoveryMetadata.isValidFor(expectedId: String): Boolean =
        version == 1 &&
            stableId == expectedId &&
            originalPath.isNotBlank() &&
            SHA256.matches(baselineSha256) &&
            SHA256.matches(draftSha256) &&
            draftFileName == "$expectedId.$draftSha256.md" &&
            updatedAt >= 0

    private fun ensureRoot() {
        rejectSymbolicLinkAncestors(root)
        Files.createDirectories(root)
        rejectSymbolicLinkAncestors(root)
        check(Files.isDirectory(root)) { "Recovery path is not a directory: $root" }
    }

    private fun rejectSymbolicLinkAncestors(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (
                Files.exists(current, NOFOLLOW_LINKS) &&
                Files.isSymbolicLink(current)
            ) {
                throw SecurityException("Recovery path contains a symbolic link: $current")
            }
            current = current.parent
        }
    }

    private fun child(name: String): File {
        val path = root.resolve(name).normalize()
        check(path.parent == root) { "Recovery path escapes its directory" }
        return path.toFile()
    }

    private fun ensureNotSymbolicLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw SecurityException("Recovery file cannot be a symbolic link: ${file.name}")
        }
    }

    private fun isReadableRegularFile(file: File, maxBytes: Long): Boolean =
        file.isFile &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.length() in 1..maxBytes

    private fun removeObsoleteDrafts(stableId: String, keep: String) {
        root.toFile().listFiles().orEmpty()
            .filter {
                it.name != keep &&
                    it.name.startsWith("$stableId.") &&
                    it.extension == "md"
            }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun utf8LengthAtMost(value: String, maximum: Long): Boolean {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val character = value[index]
            bytes += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                Character.isHighSurrogate(character) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > maximum) return false
            index++
        }
        return true
    }

    private companion object {
        const val MAX_METADATA_BYTES = 64L * 1024
        const val MAX_DRAFT_BYTES = 64L * 1024 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
