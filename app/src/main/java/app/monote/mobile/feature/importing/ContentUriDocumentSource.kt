package app.monote.mobile.feature.importing

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentUriDocumentSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val displayName: String,
    override val mimeType: String?,
    val size: Long? = null,
) : DocumentSource {
    override suspend fun open(): InputStream = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)
            ?: throw FileNotFoundException("无法读取所选文件：$displayName")
    }
}
