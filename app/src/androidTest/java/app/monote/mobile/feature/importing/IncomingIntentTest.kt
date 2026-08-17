package app.monote.mobile.feature.importing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.content.ContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monote.mobile.AppContainer
import app.monote.mobile.MainActivity
import app.monote.mobile.MoNoteApplication
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingIntentTest {
    private lateinit var resolver: ContentResolver
    private lateinit var cacheDir: java.io.File
    private lateinit var parser: IncomingIntentParser

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = context.cacheDir
        FakeShareProvider.clear()
        resolver = context.contentResolver
        parser = IncomingIntentParser(resolver)
    }

    @Test
    fun viewEditSendAndSendMultipleAreAccepted() = runBlocking {
        val first = document("first.md", "# first", "text/markdown")
        val second = document("second.markdown", "# second", "text/x-markdown")

        assertEquals(listOf("first.md"), accepted(Intent(Intent.ACTION_VIEW).setDataAndType(first, "text/markdown")))
        assertEquals(listOf("first.md"), accepted(Intent(Intent.ACTION_EDIT).setDataAndType(first, "text/markdown")))
        assertEquals(
            listOf("first.md"),
            accepted(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, first)),
        )
        assertEquals(
            listOf("first.md", "second.markdown"),
            accepted(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("text/plain")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second)),
            ),
        )
    }

    @Test
    fun clipDataFallbackIsAcceptedAndDeduplicated() = runBlocking {
        val uri = document("clip.md", "clip", "application/octet-stream")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/octet-stream").apply {
            clipData = ClipData.newUri(resolver, "clip", uri).apply {
                addItem(ClipData.Item(uri))
            }
        }

        assertEquals(listOf("clip.md"), accepted(intent))
    }

    @Test
    fun genericMimeWithMarkdownExtensionIsAccepted() = runBlocking {
        val uri = document("微信复习.md", "# test", "application/octet-stream")
        val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertEquals(listOf("微信复习.md"), accepted(intent))
    }

    @Test
    fun genericMimeWithoutMarkdownExtensionIsRejected() = runBlocking {
        val uri = document("photo.jpg", "not markdown", "application/octet-stream")
        val result = parser.parse(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri))

        assertTrue(result is IncomingParseResult.Unsupported)
    }

    @Test
    fun knownNonTextMimeCannotMasqueradeBehindMarkdownExtension() = runBlocking {
        val uri = document("disguised.md", "image bytes", "image/jpeg")

        val result = parser.parse(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri))

        assertTrue(result is IncomingParseResult.Unsupported)
    }

    @Test
    fun explicitMarkdownMimeAddsMissingExtension() = runBlocking {
        val uri = document("extensionless", "# note", "text/markdown")

        val names = accepted(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown"))

        assertEquals(listOf("extensionless.md"), names)
    }

    @Test
    fun manifestAdvertisesAllSupportedExternalActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://example/note.md")
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown"),
            Intent(Intent.ACTION_EDIT).setDataAndType(uri, "text/markdown"),
            Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri)),
        )

        intents.forEach { intent ->
            val matches = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("MoNote did not match ${intent.action}", matches.any { it.activityInfo.packageName == context.packageName })
        }
    }

    @Test
    fun coldStartRequestIsReplayedUntilUiAcknowledgesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(context)
        val uri = document("cold-start.md", "# cold", "text/markdown")

        container.receiveIncomingIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown"))

        val request = container.incomingRequests.replayCache.single()
        assertEquals(listOf("cold-start.md"), request.documents.map { it.displayName })
        container.acknowledgeIncomingRequest(request.id)
        assertTrue(container.incomingRequests.replayCache.isEmpty())
    }

    @Test
    fun mainActivityForwardsColdAndWarmExternalIntents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as MoNoteApplication).container
        container.incomingRequests.replayCache.lastOrNull()?.let { container.acknowledgeIncomingRequest(it.id) }
        val coldUri = document("activity-cold.md", "cold", "text/markdown")

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(coldUri, "text/markdown")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val cold = withTimeout(10_000) {
            container.incomingRequests.first { request -> request.documents.any { it.displayName == "activity-cold.md" } }
        }
        container.acknowledgeIncomingRequest(cold.id)

        val warmUri = document("activity-warm.md", "warm", "text/markdown")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, warmUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val warm = withTimeout(10_000) {
            container.incomingRequests.first { request -> request.documents.any { it.displayName == "activity-warm.md" } }
        }
        container.acknowledgeIncomingRequest(warm.id)
    }

    @Test
    fun unsafeDisplayNameIsRejected() = runBlocking {
        val uri = document("../escape.md", "unsafe", "text/markdown")

        assertTrue(parser.parse(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")) is IncomingParseResult.Unsupported)
    }

    @Test
    fun overlongUtf8DisplayNameIsRejectedBeforeFilesystemImport() = runBlocking {
        val uri = document("复".repeat(100) + ".md", "long", "text/markdown")

        assertTrue(
            parser.parse(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")) is IncomingParseResult.Unsupported,
        )
    }

    @Test
    fun missingOrNonContentStreamIsRejected() = runBlocking {
        assertTrue(parser.parse(Intent(Intent.ACTION_SEND).setType("text/markdown")) is IncomingParseResult.Unsupported)
        val fileUri = Uri.parse("file:///sdcard/note.md")
        assertTrue(parser.parse(Intent(Intent.ACTION_VIEW, fileUri).setType("text/markdown")) is IncomingParseResult.Unsupported)
    }

    @Test
    fun unreadableUriIsRejectedDuringParsing() = runBlocking {
        val uri = document("blocked.md", "blocked", "text/markdown", readable = false)

        assertTrue(parser.parse(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")) is IncomingParseResult.Unsupported)
    }

    @Test
    fun sourceReportsProviderFailureAfterConfirmation() = runBlocking {
        val uri = document("later.md", "later", "text/markdown")
        val result = parser.parse(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")) as IncomingParseResult.Accepted
        FakeShareProvider.setReadable(uri, false)

        val failure = runCatching { result.request.documents.single().open().use { it.readBytes() } }.exceptionOrNull()

        assertTrue(failure is FileNotFoundException)
    }

    private suspend fun accepted(intent: Intent): List<String> {
        val result = parser.parse(intent)
        assertTrue("Expected Accepted but was $result", result is IncomingParseResult.Accepted)
        return (result as IncomingParseResult.Accepted).request.documents.map { it.displayName }
    }

    private fun document(name: String, body: String, mimeType: String, readable: Boolean = true): Uri =
        FakeShareProvider.document(cacheDir, name, body, mimeType, readable)
}
