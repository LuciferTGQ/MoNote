package app.monote.mobile.feature.editor.bridge

import app.monote.mobile.feature.editor.DocumentHeading
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
            WebMessage.OutlineChanged(
                listOf(DocumentHeading("heading-1", "第一章", 1, 1)),
            ),
            WebMessage.SearchResult(current = 2, total = 4),
            WebMessage.ActiveHeadingChanged("heading-1"),
            WebMessage.ReadingPositionChanged(
                editorLine = 8,
                editorColumn = 3,
                editorProgress = 0.25f,
                previewHeadingId = "heading-1",
                previewProgress = 0.5f,
            ),
            WebMessage.PreviewTapped,
        )
        val nativeMessages = listOf(
            NativeMessage.Load(2, "body", EditorMode.SPLIT, EditorTheme.DARK),
            NativeMessage.Command(EditorCommand.UNDO),
            NativeMessage.SetMode(EditorMode.READ),
            NativeMessage.SetFontSize(20),
            NativeMessage.RefreshPreview(2),
            NativeMessage.SetPreviewPolicy(LargeDocumentPolicy.LIVE),
            NativeMessage.SearchDocument("复习", SearchAction.RESET),
            NativeMessage.NavigateToHeading("heading-1"),
            NativeMessage.RestoreReadingPosition(
                editorLine = 8,
                editorColumn = 3,
                editorProgress = 0.25f,
                previewHeadingId = "heading-1",
                previewProgress = 0.5f,
            ),
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
        assertNativeRejected("""{"type":"setFontSize","pixels":17}""")
        assertNativeRejected(
            """{"type":"searchDocument","query":"${"x".repeat(257)}","action":"reset"}""",
        )
        assertNativeRejected(
            """{"type":"navigateToHeading","headingId":""}""",
        )
        assertNativeRejected(
            """{"type":"restoreReadingPosition","editorLine":1,"editorColumn":0,"editorProgress":1.1,"previewHeadingId":null,"previewProgress":0.0}""",
        )
        assertRejected(
            """{"type":"outlineChanged","headings":[{"id":"same","title":"A","level":1,"sourceLine":1},{"id":"same","title":"B","level":2,"sourceLine":2}]}""",
        )
        assertRejected(
            """{"type":"searchResult","current":2,"total":1}""",
        )
        assertRejected(
            """{"type":"readingPositionChanged","editorLine":0,"editorColumn":0,"editorProgress":0.0,"previewHeadingId":null,"previewProgress":0.0}""",
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
