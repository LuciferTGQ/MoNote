package app.monote.mobile.feature.importing

import java.io.InputStream

interface DocumentSource {
    val displayName: String
    val mimeType: String?

    suspend fun open(): InputStream
}
