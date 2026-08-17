package app.monote.mobile.feature.editor

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monote.mobile.feature.editor.bridge.DocumentSurfaceController
import app.monote.mobile.feature.editor.bridge.EditorMode
import app.monote.mobile.feature.editor.bridge.EditorTheme
import app.monote.mobile.feature.editor.bridge.NativeMessage
import app.monote.mobile.feature.editor.bridge.WebMessage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentSurfaceTest {
    private lateinit var libraryRoot: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        libraryRoot = context.cacheDir.resolve("document-surface-test").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
    }

    @After
    fun tearDown() {
        DocumentSurfaceTestActivity.clearConfiguration()
        libraryRoot.deleteRecursively()
    }

    @Test
    fun readyLoadAndOneTypedCharacterRoundTripOverTrustedPort() {
        val received = CopyOnWriteArrayList<WebMessage>()
        val ready = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val controller = DocumentSurfaceController { message ->
            received += message
            when (message) {
                WebMessage.Ready -> ready.countDown()
                is WebMessage.Changed -> changed.countDown()
                else -> Unit
            }
        }
        DocumentSurfaceTestActivity.configure(controller, libraryRoot)

        ActivityScenario.launch(DocumentSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("renderer did not send ready", ready.await(20, TimeUnit.SECONDS))
            lateinit var webView: WebView
            val inserted = CountDownLatch(1)
            scenario.onActivity { activity ->
                webView = findWebView(activity.window.decorView)
                    ?: error("DocumentSurface did not create a WebView")
                assertFalse(webView.settings.allowFileAccess)
                assertFalse(webView.settings.allowContentAccess)
                assertTrue(webView.settings.javaScriptEnabled)
                assertEquals(DOCUMENT_SURFACE_URL, webView.url)
                controller.send(
                    NativeMessage.Load(
                        revision = 0,
                        text = "# 墨笺",
                        mode = EditorMode.EDIT,
                        theme = EditorTheme.LIGHT,
                    ),
                )
                webView.evaluateJavascript(
                    """
                    (() => {
                      const editor = document.querySelector('.cm-content');
                      editor.focus();
                      return document.execCommand('insertText', false, 'x');
                    })()
                    """.trimIndent(),
                ) { result ->
                    if (result == "true") inserted.countDown()
                }
            }
            assertTrue("test hook did not insert text", inserted.await(10, TimeUnit.SECONDS))

            assertTrue("renderer did not emit changed", changed.await(10, TimeUnit.SECONDS))
            val changes = received.filterIsInstance<WebMessage.Changed>()
            assertEquals(1, changes.size)
            assertEquals(1, changes.single().revision)
            assertEquals("x# 墨笺", changes.single().text)
        }
        controller.close()
    }

    @Test
    fun libraryHandlerServesOnlySafeFilesInsideTheRoot() {
        val image = libraryRoot.resolve("assets/pixel.png")
        assertTrue(checkNotNull(image.parentFile).mkdirs())
        image.writeBytes(byteArrayOf(1, 2, 3))
        val handler = LibraryAssetPathHandler(libraryRoot)

        val response = handler.handle("assets/pixel.png")

        assertNotNull(response)
        assertEquals("image/png", response?.mimeType)
        assertEquals(listOf<Byte>(1, 2, 3), response?.data?.readBytes()?.toList())
        assertNull(handler.handle("../outside.png"))
        assertNull(handler.handle("%252e%252e/outside.png"))
        assertNull(handler.handle("assets/missing.png"))
    }

    @Test
    fun embeddedSurfaceDoesNotPaintOverEditorControlsAndReceivesTheDocument() {
        val ready = CountDownLatch(1)
        lateinit var controller: DocumentSurfaceController
        controller = DocumentSurfaceController { message ->
            if (message == WebMessage.Ready) {
                controller.send(
                    NativeMessage.Load(
                        revision = 0,
                        text = "# 可见正文",
                        mode = EditorMode.EDIT,
                        theme = EditorTheme.LIGHT,
                    ),
                )
                ready.countDown()
            }
        }
        DocumentSurfaceTestActivity.configure(
            controller = controller,
            libraryRoot = libraryRoot,
            embedded = true,
            text = "# 可见正文",
        )

        ActivityScenario.launch(DocumentSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("embedded renderer did not send ready", ready.await(20, TimeUnit.SECONDS))
            val documentLoaded = CountDownLatch(1)
            val documentRendered = CountDownLatch(1)
            val loadedResult = AtomicReference<String>()
            val webViewTop = AtomicReference<Int>()
            scenario.onActivity { activity ->
                val webView = findWebView(activity.window.decorView)
                    ?: error("Embedded DocumentSurface did not create a WebView")
                webViewTop.set(IntArray(2).also(webView::getLocationOnScreen)[1])
                webView.evaluateJavascript(
                    "document.querySelector('.cm-content')?.textContent === '# 可见正文'",
                ) { result ->
                    loadedResult.set(result)
                    webView.postVisualStateCallback(
                        1L,
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                documentRendered.countDown()
                            }
                        },
                    )
                    documentLoaded.countDown()
                }
            }
            assertTrue("embedded document was not loaded", documentLoaded.await(10, TimeUnit.SECONDS))
            assertEquals("true", loadedResult.get())
            assertTrue("embedded document frame was not committed", documentRendered.await(10, TimeUnit.SECONDS))

            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val darkPixels = countDarkPixels(
                screenshot,
                top = screenshot.height / 20,
                bottom = screenshot.height / 5,
            )
            assertTrue("WebView painted over the editor header", darkPixels > 500)
            val documentPixels = waitForDarkPixels(
                // Exclude the Compose divider at the exact WebView boundary.
                top = webViewTop.get() + 40,
                bottom = (webViewTop.get() + 400).coerceAtMost(screenshot.height),
            )
            assertTrue(
                "Loaded Markdown was not visibly painted; top=${webViewTop.get()}, pixels=$documentPixels",
                documentPixels > 100,
            )
        }
        controller.close()
    }

    private fun waitForDarkPixels(top: Int, bottom: Int): Int {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var pixels: Int
        do {
            pixels = countDarkPixels(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
                top,
                bottom,
            )
            if (pixels > 100) return pixels
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        return pixels
    }

    private fun countDarkPixels(bitmap: android.graphics.Bitmap, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (
                    android.graphics.Color.red(color) +
                    android.graphics.Color.green(color) +
                    android.graphics.Color.blue(color) < 420
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
