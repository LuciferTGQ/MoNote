package app.monote.mobile.feature.importing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class FakeShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = documents[uri.lastPathSegment]?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val document = documents[uri.lastPathSegment] ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> document.name
                    OpenableColumns.SIZE -> document.file.length()
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val document = documents[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString())
        if (!document.readable) throw FileNotFoundException("blocked by fake provider")
        return ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private data class SharedDocument(
        val name: String,
        val mimeType: String,
        val file: File,
        val readable: Boolean,
    )

    companion object {
        const val AUTHORITY = "app.monote.mobile.debug.share"
        private val documents = linkedMapOf<String, SharedDocument>()

        fun clear() = documents.clear()

        fun document(root: File, name: String, body: String, mimeType: String, readable: Boolean = true): Uri {
            val id = UUID.randomUUID().toString()
            val file = root.resolve("incoming-$id").apply { writeText(body) }
            documents[id] = SharedDocument(name, mimeType, file, readable)
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).build()
        }

        fun setReadable(uri: Uri, readable: Boolean) {
            val id = requireNotNull(uri.lastPathSegment)
            documents[id] = requireNotNull(documents[id]).copy(readable = readable)
        }
    }
}
