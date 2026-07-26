package app.monote.mobile.core.document

import java.io.File

enum class DocumentFormat {
    Markdown,
}

interface DocumentHandler {
    val format: DocumentFormat
    val extensions: Set<String>

    suspend fun read(file: File): String

    suspend fun write(
        file: File,
        text: String,
        backup: File? = null,
        beforeCommit: (() -> Unit)? = null,
    )
}
