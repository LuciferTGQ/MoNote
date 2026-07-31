package app.monote.mobile.feature.editor.bridge

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeMessageTest {
    private val codec = BridgeMessageCodec()

    @Test
    fun changedMessageRoundTripsWithoutUnknownFields() {
        val message: WebMessage = WebMessage.Changed(
            revision = 7,
            text = "# 墨笺",
            canUndo = true,
            canRedo = false,
        )

        val encoded = codec.encodeWeb(message)

        assertEquals(
            """{"type":"changed","revision":7,"text":"# 墨笺","canUndo":true,"canRedo":false}""",
            encoded,
        )
        assertEquals(message, codec.decodeWeb(encoded))
    }

    @Test
    fun everyDocumentedVariantRoundTrips() {
        val webMessages = listOf(
            WebMessage.Ready,
            WebMessage.ExternalLink("https://example.com/notes"),
            WebMessage.RenderError("graph TD;A-->B", "invalid diagram"),
        )
        val nativeMessages = listOf(
            NativeMessage.Load(2, "body", EditorMode.SPLIT, EditorTheme.DARK),
            NativeMessage.Command(EditorCommand.UNDO),
            NativeMessage.SetMode(EditorMode.PREVIEW),
            NativeMessage.RefreshPreview(2),
            NativeMessage.SetPreviewPolicy(LargeDocumentPolicy.LIVE),
        )

        webMessages.forEach { assertEquals(it, codec.decodeWeb(codec.encodeWeb(it))) }
        nativeMessages.forEach { assertEquals(it, codec.decodeNative(codec.encodeNative(it))) }
    }

    @Test
    fun unknownMessageOrFieldIsRejected() {
        assertRejected("""{"type":"eval","code":"x"}""")
        assertRejected("""{"type":"ready","extra":true}""")
    }

    @Test
    fun malformedRevisionAndEnumsAreRejected() {
        assertRejected(
            """{"type":"changed","revision":-1,"text":"","canUndo":false,"canRedo":false}""",
        )
        assertRejected(
            """{"type":"changed","revision":1.5,"text":"","canUndo":false,"canRedo":false}""",
        )
        assertNativeRejected(
            """{"type":"load","revision":1,"text":"","mode":"fullscreen","theme":"light"}""",
        )
        assertNativeRejected("""{"type":"command","name":"deleteAll"}""")
        assertNativeRejected(
            """{"type":"setPreviewPolicy","largeDocument":"always"}""",
        )
    }

    @Test
    fun nonHttpExternalLinkIsRejected() {
        assertRejected("""{"type":"externalLink","href":"javascript:alert(1)"}""")
    }

    private fun assertRejected(raw: String) {
        assertThrows(SerializationException::class.java) {
            codec.decodeWeb(raw)
        }
    }

    private fun assertNativeRejected(raw: String) {
        assertThrows(SerializationException::class.java) {
            codec.decodeNative(raw)
        }
    }
}
