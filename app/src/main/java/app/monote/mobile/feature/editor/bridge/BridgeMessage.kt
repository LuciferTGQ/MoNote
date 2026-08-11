package app.monote.mobile.feature.editor.bridge

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
    @SerialName("refreshPreview")
    data class RefreshPreview(val revision: Long) : NativeMessage

    @Serializable
    @SerialName("setPreviewPolicy")
    data class SetPreviewPolicy(
        val largeDocument: LargeDocumentPolicy,
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
            is NativeMessage.SetSplitRatio -> if (message.ratio !in 0.25f..0.75f) {
                throw SerializationException("Split ratio is outside the supported range")
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
}
