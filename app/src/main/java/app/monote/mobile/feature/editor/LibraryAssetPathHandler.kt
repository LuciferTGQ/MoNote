package app.monote.mobile.feature.editor

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import app.monote.mobile.core.storage.SafePathPolicy
import java.io.File
import java.io.IOException
import java.util.Locale

class LibraryAssetPathHandler(
    libraryRoot: File,
    private val pathPolicy: SafePathPolicy = SafePathPolicy(libraryRoot),
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val decoded = decodePath(path) ?: return null
        val mimeType = imageMimeType(decoded) ?: return null
        val file = try {
            pathPolicy.resolve(decoded)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (!file.isFile || !file.canRead()) return null
        return try {
            WebResourceResponse(mimeType, null, file.inputStream())
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun decodePath(path: String): String? {
        var decoded = path.substringBefore('?').substringBefore('#')
        repeat(MAX_DECODE_PASSES) {
            val next = Uri.decode(decoded)
            if (next == decoded) {
                return decoded.takeIf(::isSafeDecodedPath)
            }
            decoded = next
        }
        return decoded.takeIf {
            !ENCODED_OCTET.containsMatchIn(it) && isSafeDecodedPath(it)
        }
    }

    private fun isSafeDecodedPath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.startsWith('\\') &&
            !path.contains('\\') &&
            !path.contains('\u0000')

    private fun imageMimeType(path: String): String? =
        when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            else -> null
        }

    private companion object {
        const val MAX_DECODE_PASSES = 8
        val ENCODED_OCTET = Regex("%[0-9a-fA-F]{2}")
    }
}
