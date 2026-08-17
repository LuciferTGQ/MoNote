package app.monote.mobile.ui.navigation

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.monote.mobile.feature.importing.TreeDocument
import app.monote.mobile.feature.importing.TreeDocumentSource
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ResolvedUriMetadata(val displayName: String, val mimeType: String?)

suspend fun resolveUriMetadata(context: Context, uri: Uri): ResolvedUriMetadata = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "未命名" }
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    ResolvedUriMetadata(name, type)
}

class ContentResolverTreeDocumentSource(
    context: Context,
    private val treeUri: Uri,
    override val displayName: String,
) : TreeDocumentSource {
    private val resolver = context.applicationContext.contentResolver
    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

    override suspend fun visitChildren(
        parentId: String?,
        limit: Int,
        visitor: suspend (TreeDocument) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        require(limit >= 0)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId ?: rootDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            var delivered = 0
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (delivered >= limit) return@withContext true
                val mimeType = cursor.getString(2)
                val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                visitor(
                    TreeDocument(
                        id = cursor.getString(0),
                        displayName = cursor.getString(1) ?: "未命名",
                        isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                        mimeType = mimeType,
                        size = size,
                    ),
                )
                delivered += 1
            }
        }
        false
    }

    override suspend fun open(document: TreeDocument): InputStream = withContext(Dispatchers.IO) {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, document.id)
        resolver.openInputStream(documentUri) ?: throw IOException("无法读取：${document.displayName}")
    }
}
