package org.ryubing.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    @Test
    fun `eden single game restore installs under commit 0`() {
        val root = Files.createTempDirectory("eden-save").toFile()
        val titleId = "0100E95001CFE000"
        val saveId = "0000000000009999"
        val store = SaveStore(root.absolutePath, titleId)
        root.resolve("bis/user/save/$saveId").mkdirs()
        val zip = edenZip(titleId to mapOf("slot/data.bin" to "eden-save"))

        try {
            val restored = store.restore(ByteArrayInputStream(zip), saveId)
            assertEquals("eden-save", restored.directory.resolve("0/slot/data.bin").readText())
            assertFalse(restored.directory.resolve("1").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `eden future layout unwraps nested 0 folder`() {
        val root = Files.createTempDirectory("eden-future").toFile()
        val titleId = "0100E95001CFE000"
        val saveId = "0000000000009999"
        val store = SaveStore(root.absolutePath, titleId)
        root.resolve("bis/user/save/$saveId").mkdirs()
        val zip = edenZip(titleId to mapOf("0/slot/data.bin" to "nested"))

        try {
            val restored = store.restore(ByteArrayInputStream(zip), saveId)
            assertEquals("nested", restored.directory.resolve("0/slot/data.bin").readText())
            assertFalse(restored.directory.resolve("0/0").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `eden multi game restore uses selected title`() {
        val root = Files.createTempDirectory("eden-multi").toFile()
        val titleId = "0100E95001CFE000"
        val otherId = "0100000000010000"
        val saveId = "0000000000009999"
        val store = SaveStore(root.absolutePath, titleId)
        root.resolve("bis/user/save/$saveId").mkdirs()
        val zip = edenZip(
            titleId to mapOf("a.bin" to "mine"),
            otherId to mapOf("b.bin" to "other"),
        )

        try {
            val extracted = store.extractAndInspect(ByteArrayInputStream(zip))
            assertTrue(extracted.kind is SaveArchiveKind.Eden)
            assertEquals(2, (extracted.kind as SaveArchiveKind.Eden).titleIds.size)

            val restored = store.restoreExtracted(extracted, saveId, titleId)
            assertEquals("mine", restored.directory.resolve("0/a.bin").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `eden restore rejects a different game`() {
        val root = Files.createTempDirectory("eden-wrong").toFile()
        val store = SaveStore(root.absolutePath, "0100E95001CFE000")
        val saveId = "0000000000009999"
        root.resolve("bis/user/save/$saveId").mkdirs()
        val zip = edenZip("0100000000010000" to mapOf("a.bin" to "other"))

        try {
            store.restore(ByteArrayInputStream(zip), saveId)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("different game"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun edenZip(vararg games: Pair<String, Map<String, String>>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                games.forEach { (titleId, files) ->
                    files.forEach { (path, content) ->
                        zip.putNextEntry(ZipEntry("$titleId/$path"))
                        zip.write(content.toByteArray())
                        zip.closeEntry()
                    }
                }
            }
        }.toByteArray()
}
