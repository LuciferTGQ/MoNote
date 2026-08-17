package app.monote.mobile.feature.importing

import java.io.File
import java.io.IOException

data class ImportedDocument(
    val file: File,
    val encoding: String,
    val warnings: List<String>,
)

open class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

class UnsupportedDocumentTypeException(name: String) :
    ImportException("Only .md and .markdown files can be imported: $name")
