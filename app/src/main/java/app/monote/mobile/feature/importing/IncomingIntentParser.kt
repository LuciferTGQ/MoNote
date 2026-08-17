package app.monote.mobile.feature.importing

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.monote.mobile.core.storage.SafePathPolicy
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IncomingRequest(
    val documents: List<ContentUriDocumentSource> = emptyList(),
    val error: String? = null,
    val id: String = UUID.randomUUID().toString(),
)

sealed interface IncomingParseResult {
    data class Accepted(val request: IncomingRequest) : IncomingParseResult
    data class Unsupported(val reason: String) : IncomingParseResult
}

class IncomingIntentParser(
    private val resolver: ContentResolver,
    private val maxDocuments: Int = MAX_DOCUMENTS,
) {
    suspend fun parse(intent: Intent): IncomingParseResult = withContext(Dispatchers.IO) {
        try {
            val uris = extractUris(intent)
            if (uris.isEmpty()) return@withContext IncomingParseResult.Unsupported("没有可读取的 Markdown 文件")
            if (uris.size > maxDocuments) {
                return@withContext IncomingParseResult.Unsupported("一次最多接收 $maxDocuments 个文件")
            }
            val sources = uris.map { uri -> sourceFor(uri, intent.type) }
            IncomingParseResult.Accepted(IncomingRequest(documents = sources))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            IncomingParseResult.Unsupported(failure.message ?: "无法读取分享的文件")
        }
    }

    private fun extractUris(intent: Intent): List<Uri> {
        val candidates = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> buildList {
                intent.data?.let(::add)
                addClipData(intent.clipData)
            }

            Intent.ACTION_SEND -> buildList {
                stream(intent)?.let(::add)
                addClipData(intent.clipData)
            }

            Intent.ACTION_SEND_MULTIPLE -> buildList {
                streams(intent).take(maxDocuments + 1).forEach(::add)
                addClipData(intent.clipData)
            }

            else -> emptyList()
        }
        return candidates.filter { it.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) }.distinct()
    }

    private fun MutableList<Uri>.addClipData(clipData: ClipData?) {
        if (clipData == null) return
        repeat(minOf(clipData.itemCount, maxDocuments + 1)) { index ->
            clipData.getItemAt(index).uri?.let(::add)
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    private fun sourceFor(uri: Uri, intentMimeType: String?): ContentUriDocumentSource {
        val metadata = queryMetadata(uri)
        val providerMimeType = runCatching { resolver.getType(uri) }.getOrNull().normalizedMimeType()
        val declaredMimeType = intentMimeType.normalizedMimeType()
        val effectiveMimeType = providerMimeType ?: declaredMimeType
        metadata.size?.let { size ->
            require(size in 0..MAX_DOCUMENT_BYTES) { "文件过大：${metadata.displayName}" }
        }
        val safeName = validatedName(metadata.displayName, effectiveMimeType)
        verifyReadable(uri)
        return ContentUriDocumentSource(
            resolver = resolver,
            uri = uri,
            displayName = safeName,
            mimeType = effectiveMimeType,
            size = metadata.size,
        )
    }

    private fun queryMetadata(uri: Uri): UriMetadata {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("无法读取文件信息")
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            return UriMetadata(name.orEmpty(), size)
        }
        throw IOException("无法读取文件信息")
    }

    private fun validatedName(displayName: String, mimeType: String?): String {
        val trimmed = displayName.trim()
        require(trimmed.isNotEmpty()) { "文件名为空" }
        require(displayName.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES) { "文件名过长" }
        require(SafePathPolicy.sanitizeFileName(displayName) == displayName) { "文件名不安全：$displayName" }
        val lowerName = displayName.lowercase(Locale.ROOT)
        val markdownExtension = lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
        if (markdownExtension) {
            require(mimeType == null || mimeType in MARKDOWN_MIME_TYPES || mimeType in GENERIC_MIME_TYPES) {
                "不是 Markdown 文件：$displayName"
            }
            return displayName
        }
        require(mimeType in MARKDOWN_MIME_TYPES) { "不是 Markdown 文件：$displayName" }
        require('.' !in displayName) { "Markdown 文件扩展名不受支持：$displayName" }
        val normalized = "$displayName.md"
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES) { "文件名过长" }
        return normalized
    }

    private fun verifyReadable(uri: Uri) {
        resolver.openInputStream(uri)?.use { input -> input.read() }
            ?: throw IOException("无法读取分享的文件")
    }

    private fun String?.normalizedMimeType(): String? = this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)

    private data class UriMetadata(val displayName: String, val size: Long?)

    private companion object {
        const val MAX_DOCUMENTS = 32
        const val MAX_DOCUMENT_BYTES = 16L * 1024 * 1024
        const val MAX_FILE_NAME_BYTES = 240
        val MARKDOWN_MIME_TYPES = setOf("text/markdown", "text/x-markdown")
        val GENERIC_MIME_TYPES = setOf("text/plain", "application/octet-stream")
    }
}
