package app.monote.mobile.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class SafePathPolicyTest {
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
    fun resolveAcceptsAPathInsideTheRoot() {
        val root = temporaryDirectory().resolve("library").createDirectories()

        val resolved = SafePathPolicy(root).resolve("收件箱/复习.md")

        assertEquals(root.resolve("收件箱").resolve("复习.md").toFile(), resolved)
    }

    @Test(expected = UnsafePathException::class)
    fun resolveRejectsTraversalOutsideTheRoot() {
        SafePathPolicy(temporaryDirectory()).resolve("../outside.md")
    }

    @Test(expected = UnsafePathException::class)
    fun resolveRejectsAbsolutePaths() {
        val absolutePath = temporaryDirectory().resolve("outside.md").toAbsolutePath().toString()

        SafePathPolicy(temporaryDirectory()).resolve(absolutePath)
    }

    @Test(expected = UnsafePathException::class)
    fun resolveWrapsAnInvalidPathAsUnsafe() {
        SafePathPolicy(temporaryDirectory()).resolve("invalid\u0000path.md")
    }

    @Test(expected = UnsafePathException::class)
    fun resolveRejectsRootPrefixSibling() {
        val parent = temporaryDirectory()
        val root = parent.resolve("library").createDirectories()
        parent.resolve("library-copy").createDirectories()

        SafePathPolicy(root).resolve("../library-copy/escape.md")
    }

    @Test(expected = UnsafePathException::class)
    fun resolveRejectsAnExistingLinkToOutsideTheRoot() {
        val parent = temporaryDirectory()
        val root = parent.resolve("library").createDirectories()
        val outside = parent.resolve("outside").createDirectories()
        val link = root.resolve("linked")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (error: IOException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        }

        SafePathPolicy(root).resolve("linked/escape.md")
    }

    @Test(expected = UnsafePathException::class)
    fun resolveRejectsABrokenLinkWhoseTargetIsOutsideTheRoot() {
        val parent = temporaryDirectory()
        val root = parent.resolve("library").createDirectories()
        val brokenOutsideTarget = parent.resolve("outside-target")
        val link = root.resolve("broken-linked")
        try {
            Files.createSymbolicLink(link, brokenOutsideTarget)
        } catch (error: IOException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable in this test environment", error)
        }

        SafePathPolicy(root).resolve("broken-linked/escape.md")
    }

    @Test
    fun sanitizeFileNameReplacesUnsafeCharactersAndKeepsExtension() {
        assertEquals("复习_计划_.md", SafePathPolicy.sanitizeFileName("复习:计划?.md"))
        assertEquals("a_b.md", SafePathPolicy.sanitizeFileName("a\u0000b.md"))
        assertEquals("未命名.md", SafePathPolicy.sanitizeFileName("   "))
        assertTrue(SafePathPolicy.sanitizeFileName("笔记.md").endsWith(".md"))
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("monote-safe-path-")
        .also(temporaryRoots::add)
}
