package org.ryubing.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContentFileStoreTest {

    @Test
    fun `copyFile sanitizes names and replaces existing copies`() {
        val root = Files.createTempDirectory("content-test").toFile()
        val source = root.resolve("source").apply { mkdirs() }
        source.resolve("Bad Name:v2?.nsp").writeText("version 2")

        try {
            val first = ContentFileStore.copyFile(
                source.resolve("Bad Name:v2?.nsp").absolutePath,
                root.absolutePath,
                "0100000000010000",
                "updates",
                "Bad Name:v2?.nsp",
            )
            assertTrue(first.contains("Bad Name_v2_.nsp"))
            assertEquals("version 2", File(first).readText())

            // Changing the source and copying again must replace the previous copy.
            // (Bump the mtime explicitly: same-length rewrites are only detected via mtime,
            // and coarse filesystem timestamp granularity could otherwise hide the edit.)
            val modified = source.resolve("Bad Name:v2?.nsp")
            modified.writeText("version 3")
            modified.setLastModified(System.currentTimeMillis() + 10_000)
            val second = ContentFileStore.copyFile(
                source.resolve("Bad Name:v2?.nsp").absolutePath,
                root.absolutePath,
                "0100000000010000",
                "updates",
                "Bad Name:v2?.nsp",
            )
            assertEquals(first, second)
            assertEquals("version 3", File(second).readText())

            // No stray partial files left behind.
            assertTrue(requireNotNull(File(first).parentFile).listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }
}
