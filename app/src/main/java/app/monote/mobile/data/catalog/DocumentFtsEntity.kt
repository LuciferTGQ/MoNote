package app.monote.mobile.data.catalog

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(notIndexed = ["documentId"])
@Entity(tableName = "document_fts")
data class DocumentFtsEntity(
    val documentId: String,
    val title: String,
    val body: String,
)

/** SQLite's built-in FTS4 tokenizer does not segment a contiguous Han sentence. */
internal fun ftsSearchText(value: String): String {
    return "$value ${ftsIndexTokens(value).joinToString(" ")}".trim()
}

internal fun ftsIndexTokens(value: String): List<String> = wordRuns(value).flatMap { run ->
    if (run.isHan) hanTokens(run.text) else listOf(run.text)
}

internal fun hanTokens(value: String): List<String> {
    val result = mutableListOf<String>()
    val run = mutableListOf<String>()
    fun flush() {
        if (run.isNotEmpty()) result += run
        if (run.size > 1) result += run.zipWithNext { first, second -> first + second }
        run.clear()
    }
    value.codePoints().forEach { codePoint ->
        val text = String(Character.toChars(codePoint))
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) run += text else flush()
    }
    flush()
    return result
}

internal fun hanQueryTokens(value: String): List<String> {
    val result = mutableListOf<String>()
    val run = mutableListOf<String>()
    fun flush() {
        when (run.size) {
            1 -> result += run
            else -> result += run.zipWithNext { first, second -> first + second }
        }
        run.clear()
    }
    value.codePoints().forEach { codePoint ->
        val text = String(Character.toChars(codePoint))
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) run += text else flush()
    }
    flush()
    return result
}

internal fun ftsQueryTokens(value: String): List<String> {
    return wordRuns(value).flatMap { run ->
        if (run.isHan) hanQueryTokens(run.text) else listOf(run.text)
    }
}

private fun wordRuns(value: String): List<WordRun> {
    val result = mutableListOf<WordRun>()
    val text = StringBuilder()
    var isHanRun = false
    fun flush() {
        if (text.isEmpty()) return
        result += WordRun(text.toString(), isHanRun)
        text.clear()
    }
    value.codePoints().forEach { codePoint ->
        val isHan = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
        val isWord = isHan || Character.isLetterOrDigit(codePoint) || codePoint == '_'.code
        if (!isWord) {
            flush()
        } else {
            if (text.isNotEmpty() && isHan != isHanRun) flush()
            isHanRun = isHan
            text.appendCodePoint(codePoint)
        }
    }
    flush()
    return result
}

private data class WordRun(val text: String, val isHan: Boolean)
