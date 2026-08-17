package app.monote.mobile.core.document

import app.monote.mobile.core.storage.AtomicTextStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MarkdownDocumentHandler(private val store: AtomicTextStore = AtomicTextStore()) : DocumentHandler {
    override val format: DocumentFormat = DocumentFormat.Markdown
    override val extensions: Set<String> = setOf("md", "markdown")

    override suspend fun read(file: File): String = withContext(Dispatchers.IO) {
        Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { it.readText() }
    }

    override suspend fun write(
        file: File,
        text: String,
        backup: File?,
        beforeCommit: (() -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        store.replace(file, text, backup, beforeCommit = beforeCommit)
    }
}
