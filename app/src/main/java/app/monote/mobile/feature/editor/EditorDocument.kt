package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File

data class EditorDocument(val id: String, val file: File)

fun editorDocumentFor(file: File, libraryRoot: File): EditorDocument {
    val relative = libraryRoot.toPath().toAbsolutePath().normalize()
        .relativize(file.toPath().toAbsolutePath().normalize())
        .toString()
        .replace(File.separatorChar, '/')
    return EditorDocument(
        id = "path-${DocumentFingerprint.sha256(relative).take(32)}",
        file = file,
    )
}
