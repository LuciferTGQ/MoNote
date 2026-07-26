package app.monote.mobile.feature.importing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction.REPORT

enum class EncodingHint(
    internal val charsetName: String,
    internal val canonicalName: String,
) {
    UTF8("UTF-8", "UTF-8"),
    UTF16_LE("UTF-16LE", "UTF-16LE"),
    UTF16_BE("UTF-16BE", "UTF-16BE"),
}

data class DecodedText(
    val text: String,
    val encoding: String,
    val warnings: List<String> = emptyList(),
)

class TextDecodingException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

class TextInputTooLargeException(val limit: Int) : IllegalArgumentException("Text input exceeds $limit bytes")

class TextDecoder(private val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES) {
    init {
        require(maxInputBytes > 0) { "maxInputBytes must be positive" }
    }

    /** A BOM wins over a hint because it is part of the document's self-description. */
    fun decode(input: InputStream, hint: EncodingHint? = null): DecodedText {
        return decode(readBytes(input), hint)
    }

    /** Reads one bounded byte snapshot that callers may both decode and fingerprint. */
    fun readBytes(input: InputStream): ByteArray {
        return ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val remainingWithSentinel = (maxInputBytes.toLong() - total + 1)
                    .coerceAtMost(buffer.size.toLong())
                    .toInt()
                val count = input.read(buffer, 0, remainingWithSentinel)
                if (count < 0) break
                if (count == 0) continue
                if (count > maxInputBytes - total) throw TextInputTooLargeException(maxInputBytes)
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        }
    }

    /** A BOM wins over a hint because it is part of the document's self-description. */
    fun decode(bytes: ByteArray, hint: EncodingHint? = null): DecodedText {
        if (bytes.size > maxInputBytes) throw TextInputTooLargeException(maxInputBytes)
        if (hasUtf32Bom(bytes)) {
            throw TextDecodingException("UTF-32 encoded documents are not supported", UnsupportedEncodingException("UTF-32"))
        }
        val detected = detectBom(bytes)
        val encoding = detected?.first ?: hint ?: EncodingHint.UTF8
        val contentOffset = detected?.second ?: 0
        val text = try {
            java.nio.charset.Charset.forName(encoding.charsetName)
                .newDecoder()
                .onMalformedInput(REPORT)
                .onUnmappableCharacter(REPORT)
                .decode(ByteBuffer.wrap(bytes, contentOffset, bytes.size - contentOffset))
                .toString()
        } catch (error: CharacterCodingException) {
            throw TextDecodingException("Unable to decode document as ${encoding.canonicalName}", error)
        }
        return DecodedText(text = text, encoding = encoding.canonicalName)
    }

    private fun detectBom(bytes: ByteArray): Pair<EncodingHint, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> EncodingHint.UTF8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> EncodingHint.UTF16_LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> EncodingHint.UTF16_BE to 2
        else -> null
    }

    private fun hasUtf32Bom(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()) ||
                (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte())
            )

    private companion object {
        const val DEFAULT_MAX_INPUT_BYTES = 16 * 1024 * 1024
        const val READ_BUFFER_BYTES = 32 * 1024
    }
}
