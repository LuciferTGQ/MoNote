package app.monote.mobile.feature.editor.bridge

import app.monote.mobile.feature.editor.DocumentHeading
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAX_SAFE_REVISION = 9_007_199_254_740_991L

@Serializable
sealed interface WebMessage {
    @Serializable
    @SerialName("ready")
    data object Ready : WebMessage

    @Serializable
    @SerialName("changed")
    data class Changed(
        val revision: Long,
        val text: String,
        val canUndo: Boolean,
        val canRedo: Boolean,
    ) : WebMessage

    @Serializable
    @SerialName("externalLink")
    data class ExternalLink(val href: String) : WebMessage

    @Serializable
    @SerialName("renderError")
    data class RenderError(
        val block: String,
        val message: String,
    ) : WebMessage

    @Serializable
    @SerialName("outlineChanged")
    data class OutlineChanged(val headings: List<DocumentHeading>) : WebMessage

    @Serializable
    @SerialName("searchResult")
    data class SearchResult(
        val current: Int,
        val total: Int,
    ) : WebMessage

    @Serializable
    @SerialName("activeHeadingChanged")
    data class ActiveHeadingChanged(val headingId: String?) : WebMessage

    @Serializable
    @SerialName("readingPositionChanged")
    data class ReadingPositionChanged(
        val editorLine: Int,
        val editorColumn: Int,
        val editorProgress: Float,
        val previewHeadingId: String?,
        val previewProgress: Float,
    ) : WebMessage

    @Serializable
    @SerialName("previewTapped")
    data object PreviewTapped : WebMessage
}

@Serializable
sealed interface NativeMessage {
    @Serializable
    @SerialName("load")
    data class Load(
        val revision: Long,
        val text: String,
        val mode: EditorMode,
        val theme: EditorTheme,
    ) : NativeMessage

    @Serializable
    @SerialName("command")
    data class Command(val name: EditorCommand) : NativeMessage

    @Serializable
    @SerialName("setMode")
    data class SetMode(val mode: EditorMode) : NativeMessage

    @Serializable
    @SerialName("setSplitRatio")
    data class SetSplitRatio(val ratio: Float) : NativeMessage

    @Serializable
    @SerialName("setFontSize")
    data class SetFontSize(val pixels: Int) : NativeMessage

    @Serializable
    @SerialName("refreshPreview")
    data class RefreshPreview(val revision: Long) : NativeMessage

    @Serializable
    @SerialName("setPreviewPolicy")
    data class SetPreviewPolicy(
        val largeDocument: LargeDocumentPolicy,
    ) : NativeMessage

    @Serializable
    @SerialName("searchDocument")
    data class SearchDocument(
        val query: String,
        val action: SearchAction,
    ) : NativeMessage

    @Serializable
    @SerialName("navigateToHeading")
    data class NavigateToHeading(val headingId: String) : NativeMessage

    @Serializable
    @SerialName("restoreReadingPosition")
    data class RestoreReadingPosition(
        val editorLine: Int,
        val editorColumn: Int,
        val editorProgress: Float,
        val previewHeadingId: String?,
        val previewProgress: Float,
    ) : NativeMessage
}

@Serializable
enum class EditorMode {
    @SerialName("edit")
    EDIT,

    @SerialName("preview")
    PREVIEW,

    @SerialName("split")
    SPLIT,

    @SerialName("read")
    READ,
}

@Serializable
enum class SearchAction {
    @SerialName("reset")
    RESET,

    @SerialName("next")
    NEXT,

    @SerialName("previous")
    PREVIOUS,
}

@Serializable
enum class EditorTheme {
    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,
}

@Serializable
enum class EditorCommand {
    @SerialName("undo")
    UNDO,

    @SerialName("redo")
    REDO,

    @SerialName("bold")
    BOLD,

    @SerialName("italic")
    ITALIC,

    @SerialName("heading")
    HEADING,

    @SerialName("link")
    LINK,

    @SerialName("image")
    IMAGE,

    @SerialName("strike")
    STRIKE,

    @SerialName("bulletList")
    BULLET_LIST,

    @SerialName("taskList")
    TASK_LIST,

    @SerialName("quote")
    QUOTE,

    @SerialName("code")
    CODE,

    @SerialName("table")
    TABLE,

    @SerialName("math")
    MATH,

    @SerialName("mermaid")
    MERMAID,
}

@Serializable
enum class LargeDocumentPolicy {
    @SerialName("manual")
    MANUAL,

    @SerialName("live")
    LIVE,
}

class BridgeMessageCodec(
    private val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        encodeDefaults = true
    },
) {
    fun encodeWeb(message: WebMessage): String {
        validateWeb(message)
        return json.encodeToString(WebMessage.serializer(), message)
    }

    fun decodeWeb(encoded: String): WebMessage =
        json.decodeFromString(WebMessage.serializer(), encoded).also(::validateWeb)

    fun encodeNative(message: NativeMessage): String {
        validateNative(message)
        return json.encodeToString(NativeMessage.serializer(), message)
    }

    fun decodeNative(encoded: String): NativeMessage =
        json.decodeFromString(NativeMessage.serializer(), encoded).also(::validateNative)

    private fun validateWeb(message: WebMessage) {
        when (message) {
            WebMessage.Ready -> Unit
            is WebMessage.Changed -> validateRevision(message.revision)
            is WebMessage.ExternalLink -> validateExternalLink(message.href)
            is WebMessage.RenderError -> Unit
            is WebMessage.OutlineChanged -> validateOutline(message.headings)
            is WebMessage.SearchResult -> validateSearchResult(message.current, message.total)
            is WebMessage.ActiveHeadingChanged -> message.headingId?.let(::validateHeadingId)
            is WebMessage.ReadingPositionChanged -> validateReadingPosition(
                message.editorLine,
                message.editorColumn,
                message.editorProgress,
                message.previewHeadingId,
                message.previewProgress,
            )
            WebMessage.PreviewTapped -> Unit
        }
    }

    private fun validateNative(message: NativeMessage) {
        when (message) {
            is NativeMessage.Load -> validateRevision(message.revision)
            is NativeMessage.RefreshPreview -> validateRevision(message.revision)
            is NativeMessage.Command,
            is NativeMessage.SetMode,
            is NativeMessage.SetPreviewPolicy,
            -> Unit
            is NativeMessage.SearchDocument -> if (message.query.length > MAX_SEARCH_QUERY) {
                throw SerializationException("Search query is too long")
            }
            is NativeMessage.NavigateToHeading -> validateHeadingId(message.headingId)
            is NativeMessage.RestoreReadingPosition -> validateReadingPosition(
                message.editorLine,
                message.editorColumn,
                message.editorProgress,
                message.previewHeadingId,
                message.previewProgress,
            )
            is NativeMessage.SetSplitRatio -> if (message.ratio !in 0.25f..0.75f) {
                throw SerializationException("Split ratio is outside the supported range")
            }
            is NativeMessage.SetFontSize -> if (message.pixels !in SUPPORTED_FONT_SIZES) {
                throw SerializationException("Font size is outside the supported set")
            }
        }
    }

    private fun validateRevision(revision: Long) {
        if (revision !in 0..MAX_SAFE_REVISION) {
            throw SerializationException("Revision is outside the JavaScript safe integer range")
        }
    }

    private fun validateExternalLink(href: String) {
        val uri = try {
            URI(href)
        } catch (error: Exception) {
            throw SerializationException("External link is not a valid URI", error)
        }
        if (
            uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrBlank()
        ) {
            throw SerializationException("External link must be an absolute HTTP(S) URL")
        }
    }

    private fun validateOutline(headings: List<DocumentHeading>) {
        if (headings.size > MAX_REPORTED_ITEMS) {
            throw SerializationException("Outline contains too many headings")
        }
        if (headings.map(DocumentHeading::id).toSet().size != headings.size) {
            throw SerializationException("Outline heading IDs must be unique")
        }
        headings.forEach { heading ->
            validateHeadingId(heading.id)
            if (
                heading.title.isBlank() ||
                heading.title.length > MAX_HEADING_TITLE ||
                heading.title.any(Char::isISOControl) ||
                heading.level !in 1..6 ||
                heading.sourceLine !in 1..MAX_SOURCE_POSITION
            ) {
                throw SerializationException("Outline heading is invalid")
            }
        }
    }

    private fun validateSearchResult(current: Int, total: Int) {
        if (
            total !in 0..MAX_REPORTED_ITEMS ||
            (total == 0 && current != 0) ||
            (total > 0 && current !in 1..total)
        ) {
            throw SerializationException("Search result is invalid")
        }
    }

    private fun validateReadingPosition(
        editorLine: Int,
        editorColumn: Int,
        editorProgress: Float,
        previewHeadingId: String?,
        previewProgress: Float,
    ) {
        if (
            editorLine !in 1..MAX_SOURCE_POSITION ||
            editorColumn !in 0..MAX_SOURCE_POSITION ||
            !editorProgress.isFinite() || editorProgress !in 0f..1f ||
            !previewProgress.isFinite() || previewProgress !in 0f..1f
        ) {
            throw SerializationException("Reading position is invalid")
        }
        previewHeadingId?.let(::validateHeadingId)
    }

    private fun validateHeadingId(headingId: String) {
        if (
            headingId.isBlank() ||
            headingId.length > MAX_HEADING_ID ||
            headingId.any(Char::isISOControl)
        ) {
            throw SerializationException("Heading ID is invalid")
        }
    }

    private companion object {
        val SUPPORTED_FONT_SIZES = setOf(14, 16, 20)
        const val MAX_SEARCH_QUERY = 256
        const val MAX_REPORTED_ITEMS = 1_000
        const val MAX_HEADING_ID = 256
        const val MAX_HEADING_TITLE = 512
        const val MAX_SOURCE_POSITION = 10_000_000
    }
}
