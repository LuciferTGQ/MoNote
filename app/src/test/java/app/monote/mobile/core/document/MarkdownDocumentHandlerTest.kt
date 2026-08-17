package app.monote.mobile.core.document

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownDocumentHandlerTest {
    private val temporaryRoots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun markdownHandlerUsesUtf8AndExposesOnlyMarkdownExtensions() = runBlocking {
        val handler = MarkdownDocumentHandler()
        val file = temporaryDirectory().resolve("笔记.md").toFile()

        handler.write(file, "# 你好")

        assertEquals(DocumentFormat.Markdown, handler.format)
        assertEquals(setOf("md", "markdown"), handler.extensions)
        assertEquals("# 你好", file.readText(StandardCharsets.UTF_8))
        assertEquals("# 你好", handler.read(file))
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-markdown-handler-")
        .also(temporaryRoots::add)
}
