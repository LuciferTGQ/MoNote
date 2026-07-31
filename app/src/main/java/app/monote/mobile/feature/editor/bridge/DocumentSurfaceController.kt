package app.monote.mobile.feature.editor.bridge

import android.annotation.SuppressLint
import androidx.annotation.MainThread
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import kotlinx.serialization.SerializationException

@SuppressLint("RequiresFeature")
class DocumentSurfaceController(
    private val codec: BridgeMessageCodec = BridgeMessageCodec(),
    private val onProtocolError: (Throwable) -> Unit = {},
    private val onMessage: (WebMessage) -> Unit,
) {
    private var port: WebMessagePortCompat? = null
    private val pending = ArrayDeque<String>()

    @MainThread
    internal fun attach(nativePort: WebMessagePortCompat) {
        detach()
        port = nativePort
        nativePort.setWebMessageCallback(
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(
                    port: WebMessagePortCompat,
                    message: WebMessageCompat?,
                ) {
                    val encoded = message?.data
                    if (
                        message == null ||
                        message.type != WebMessageCompat.TYPE_STRING ||
                        encoded == null
                    ) {
                        onProtocolError(SerializationException("Bridge messages must be strings"))
                        return
                    }
                    val decoded = try {
                        codec.decodeWeb(encoded)
                    } catch (error: SerializationException) {
                        onProtocolError(error)
                        return
                    }
                    onMessage(decoded)
                }
            },
        )
        while (pending.isNotEmpty()) {
            nativePort.postMessage(WebMessageCompat(pending.removeFirst()))
        }
    }

    @MainThread
    fun send(message: NativeMessage) {
        val encoded = codec.encodeNative(message)
        val activePort = port
        if (activePort == null) {
            pending.addLast(encoded)
        } else {
            activePort.postMessage(WebMessageCompat(encoded))
        }
    }

    @MainThread
    fun detach() {
        port?.close()
        port = null
    }

    @MainThread
    fun close() {
        detach()
        pending.clear()
    }

    @MainThread
    internal fun reportError(error: Throwable) {
        onProtocolError(error)
    }
}
