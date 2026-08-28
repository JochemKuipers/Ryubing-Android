package org.ryubing.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilTest {

    @Test(expected = java.io.IOException::class)
    fun `extract rejects traversal paths`() {
        val zipBytes = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val root = Files.createTempDirectory("zip-test").toFile()

        try {
            ZipUtil.extractSafely(ByteArrayInputStream(zipBytes), root)
        } finally {
            assertFalse(root.resolve("../outside.txt").exists())
            root.deleteRecursively()
        }
    }

    @Test
    fun `save backup restores after the original is cleared`() {
        val root = Files.createTempDirectory("save-test").toFile()
        val titleId = "0100000000001000"
        val saveId = "0000000000001234"
        val newSaveId = "0000000000005678"
        val store = SaveStore(root.absolutePath, titleId)
        val saveDir = root.resolve("bis/user/save/$saveId").apply { mkdirs() }
        saveDir.resolve("slot/data.bin").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("save data")
        }
        val backup = ByteArrayOutputStream()

        try {
            store.backup(requireNotNull(store.find(saveId)), backup)
            store.clear(requireNotNull(store.find(saveId)))
            root.resolve("bis/user/save/$newSaveId").mkdirs()
            val restored = store.restore(ByteArrayInputStream(backup.toByteArray()), newSaveId)

            assertEquals("save data", restored.directory.resolve("slot/data.bin").readText())
            assertEquals(newSaveId, restored.directory.name)
        } finally {
            root.deleteRecursively()
        }
    }
}