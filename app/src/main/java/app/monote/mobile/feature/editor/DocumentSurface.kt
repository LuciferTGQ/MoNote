package app.monote.mobile.feature.editor

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.monote.mobile.feature.editor.bridge.DocumentSurfaceController
import java.io.ByteArrayInputStream
import java.io.File

const val DOCUMENT_SURFACE_ORIGIN = "https://appassets.androidplatform.net"
const val DOCUMENT_SURFACE_URL = "$DOCUMENT_SURFACE_ORIGIN/renderer/index.html"

@Composable
fun DocumentSurface(
    controller: DocumentSurfaceController,
    libraryRoot: File,
    modifier: Modifier = Modifier,
    onExternalLink: (Uri) -> Unit = {},
) {
    val currentExternalLink by rememberUpdatedState(onExternalLink)
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val libraryHandler = LibraryAssetPathHandler(libraryRoot)
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(Uri.parse(DOCUMENT_SURFACE_ORIGIN).host!!)
                .setHttpAllowed(false)
                .addPathHandler(
                    "/renderer/",
                    RendererPathHandler(context, libraryHandler),
                )
                .addPathHandler("/library/", libraryHandler)
                .build()
            restrictedWebView(
                context = context,
                assetLoader = assetLoader,
                controller = controller,
                onExternalLink = { currentExternalLink(it) },
            ).also {
                webView = it
                it.loadUrl(DOCUMENT_SURFACE_URL)
            }
        },
    )

    DisposableEffect(controller) {
        onDispose {
            controller.detach()
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun restrictedWebView(
    context: android.content.Context,
    assetLoader: WebViewAssetLoader,
    controller: DocumentSurfaceController,
    onExternalLink: (Uri) -> Unit,
): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        domStorageEnabled = false
        databaseEnabled = false
        blockNetworkLoads = true
        mediaPlaybackRequiresUserGesture = true
        defaultTextEncodingName = "utf-8"
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webViewClient = RestrictedSurfaceClient(
        assetLoader = assetLoader,
        controller = controller,
        onExternalLink = onExternalLink,
    )
}

private class RestrictedSurfaceClient(
    private val assetLoader: WebViewAssetLoader,
    private val controller: DocumentSurfaceController,
    private val onExternalLink: (Uri) -> Unit,
) : WebViewClient() {
    private var channelEstablished = false

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse {
        val uri = request.url
        if (uri.scheme != "https" || uri.host != Uri.parse(DOCUMENT_SURFACE_ORIGIN).host) {
            return blockedResponse(403, "Forbidden")
        }
        return assetLoader.shouldInterceptRequest(uri)
            ?: blockedResponse(404, "Not Found")
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val uri = request.url
        if (isDocumentSurface(uri)) return false
        if (request.isForMainFrame && isExternalHttp(uri)) {
            onExternalLink(uri)
        }
        return true
    }

    @SuppressLint("RequiresFeature")
    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (channelEstablished || !isDocumentSurface(Uri.parse(url))) return
        if (!supportsDocumentChannel()) {
            channelEstablished = true
            controller.reportError(
                UnsupportedOperationException(
                    "The installed WebView does not support secure message channels",
                ),
            )
            return
        }
        val ports = WebViewCompat.createWebMessageChannel(view)
        controller.attach(ports[0])
        WebViewCompat.postWebMessage(
            view,
            WebMessageCompat("", arrayOf(ports[1])),
            Uri.parse(DOCUMENT_SURFACE_ORIGIN),
        )
        channelEstablished = true
    }

    private fun isDocumentSurface(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == Uri.parse(DOCUMENT_SURFACE_ORIGIN).host &&
            uri.path == "/renderer/index.html"

    private fun isExternalHttp(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.host != Uri.parse(DOCUMENT_SURFACE_ORIGIN).host

    private fun supportsDocumentChannel(): Boolean =
        REQUIRED_WEB_MESSAGE_FEATURES.all(WebViewFeature::isFeatureSupported)
}

private class RendererPathHandler(
    context: android.content.Context,
    private val libraryHandler: LibraryAssetPathHandler,
) : WebViewAssetLoader.PathHandler {
    private val packagedAssets = WebViewAssetLoader.AssetsPathHandler(context)

    override fun handle(path: String): WebResourceResponse? =
        packagedAssets.handle("renderer/$path") ?: libraryHandler.handle(path)
}

private fun blockedResponse(
    statusCode: Int,
    reason: String,
): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        statusCode,
        reason,
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

private val REQUIRED_WEB_MESSAGE_FEATURES = arrayOf(
    WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
    WebViewFeature.POST_WEB_MESSAGE,
    WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
    WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
    WebViewFeature.WEB_MESSAGE_PORT_CLOSE,
)
