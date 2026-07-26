package app.monote.mobile.feature.importing

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecoderTest {
    private val decoder = TextDecoder()

    @Test
    fun decodesUtf8WithoutBom() {
        val decoded = decoder.decode(ByteArrayInputStream("复习笔记".toByteArray(StandardCharsets.UTF_8)))

        assertEquals("复习笔记", decoded.text)
        assertEquals("UTF-8", decoded.encoding)
    }

    @Test
    fun decodesUtf8BomWithoutIncludingBomInText() {
        val decoded = decoder.decode(ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "note".toByteArray()))

        assertEquals("note", decoded.text)
        assertEquals("UTF-8", decoded.encoding)
    }

    @Test
    fun decodesUtf16LittleEndianBomWithoutIncludingBomInText() {
        val decoded = decoder.decode(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "笔记".toByteArray(Charsets.UTF_16LE)))

        assertEquals("笔记", decoded.text)
        assertEquals("UTF-16LE", decoded.encoding)
    }

    @Test
    fun decodesUtf16BigEndianBomWithoutIncludingBomInText() {
        val decoded = decoder.decode(ByteArrayInputStream(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "笔记".toByteArray(Charsets.UTF_16BE)))

        assertEquals("笔记", decoded.text)
        assertEquals("UTF-16BE", decoded.encoding)
    }

    @Test
    fun usesExplicitUtf16HintWhenNoBomExists() {
        val decoded = decoder.decode(ByteArrayInputStream("note".toByteArray(Charsets.UTF_16LE)), EncodingHint.UTF16_LE)

        assertEquals("note", decoded.text)
        assertEquals("UTF-16LE", decoded.encoding)
    }

    @Test
    fun usesExplicitBigEndianUtf16HintWhenNoBomExists() {
        val decoded = decoder.decode(ByteArrayInputStream("note".toByteArray(Charsets.UTF_16BE)), EncodingHint.UTF16_BE)

        assertEquals("note", decoded.text)
        assertEquals("UTF-16BE", decoded.encoding)
    }

    @Test
    fun bomTakesPrecedenceOverConflictingHint() {
        val decoded = decoder.decode(
            ByteArrayInputStream(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "note".toByteArray(Charsets.UTF_16BE)),
            EncodingHint.UTF8,
        )

        assertEquals("note", decoded.text)
        assertEquals("UTF-16BE", decoded.encoding)
    }

    @Test(expected = TextDecodingException::class)
    fun rejectsMalformedUtf8InsteadOfReplacingIt() {
        decoder.decode(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test(expected = TextDecodingException::class)
    fun rejectsUtf32BomInsteadOfMisidentifyingItAsUtf16() {
        decoder.decode(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)))
    }

    @Test
    fun decodesEmptyAndBomOnlyFilesAsEmptyText() {
        assertEquals("", decoder.decode(ByteArrayInputStream(byteArrayOf())).text)
        assertEquals("", decoder.decode(ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))).text)
        assertEquals("", decoder.decode(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))).text)
    }

    @Test
    fun byteArrayDecoderHandlesBomAndExplicitHint() {
        val bom = decoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "note".toByteArray())
        val hinted = decoder.decode("note".toByteArray(Charsets.UTF_16LE), EncodingHint.UTF16_LE)

        assertEquals("note", bom.text)
        assertEquals("UTF-8", bom.encoding)
        assertEquals("note", hinted.text)
        assertEquals("UTF-16LE", hinted.encoding)
    }

    @Test(expected = TextDecodingException::class)
    fun byteArrayDecoderRejectsMalformedUtf8() {
        decoder.decode(byteArrayOf(0xC3.toByte(), 0x28))
    }

    @Test(expected = TextDecodingException::class)
    fun byteArrayDecoderRejectsUtf32Bom() {
        decoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00))
    }

    @Test
    fun decodesFiveMiBOfUtf8WithinTheDefaultLimit() {
        val input = ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() }

        val decoded = decoder.decode(input)

        assertEquals(input.size, decoded.text.length)
        assertEquals("UTF-8", decoded.encoding)
    }

    @Test(expected = TextInputTooLargeException::class)
    fun rejectsByteArrayAboveConfiguredLimit() {
        TextDecoder(maxInputBytes = 3).decode(byteArrayOf(1, 2, 3, 4))
    }

    @Test(expected = TextInputTooLargeException::class)
    fun rejectsUnknownLengthInputStreamAboveConfiguredLimit() {
        TextDecoder(maxInputBytes = 3).decode(UnknownLengthInputStream(remaining = 4))
    }

    @Test
    fun oversizedStreamIsRejectedAfterReadingAtMostOneBytePastTheLimit() {
        val input = UnknownLengthInputStream(remaining = 1024 * 1024)

        try {
            TextDecoder(maxInputBytes = 3).decode(input)
            throw AssertionError("Expected TextInputTooLargeException")
        } catch (_: TextInputTooLargeException) {
        }

        assertTrue("read ${input.bytesRead} bytes", input.bytesRead <= 4)
    }

    private class UnknownLengthInputStream(private var remaining: Int) : InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int = if (remaining-- > 0) {
            bytesRead += 1
            'a'.code
        } else {
            -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return -1
            val count = minOf(length, remaining)
            repeat(count) { buffer[offset + it] = 'a'.code.toByte() }
            remaining -= count
            bytesRead += count
            return count
        }
    }
}
