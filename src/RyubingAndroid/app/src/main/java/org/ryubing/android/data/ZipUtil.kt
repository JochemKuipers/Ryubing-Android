package org.ryubing.android.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtil {

    private const val MAX_ENTRIES = 20_000
    private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024

    fun extractSafely(input: InputStream, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalFile
        var entries = 0
        var bytes = 0L

        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (++entries > MAX_ENTRIES) throw IOException("Archive contains too many files")
                val name = entry.name.replace('\\', '/')
                if (name.startsWith('/') || name.split('/').any { it == ".." }) {
                    throw IOException("Archive contains an unsafe path: ${entry.name}")
                }
                val output = File(root, name).canonicalFile
                if (output != root && !output.path.startsWith(root.path + File.separator)) {
                    throw IOException("Archive contains an unsafe path: ${entry.name}")
                }

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { file ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = zip.read(buffer)
                        while (read >= 0) {
                            bytes += read
                            if (bytes > MAX_UNCOMPRESSED_BYTES) throw IOException("Archive is too large")
                            file.write(buffer, 0, read)
                            read = zip.read(buffer)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    fun zipDirectory(
        directory: File,
        output: OutputStream,
        prefix: String = "",
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ) {
        require(directory.isDirectory) { "Directory does not exist: $directory" }
        ZipOutputStream(output.buffered()).use { zip ->
            extraEntries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            val normalizedPrefix = prefix.trim('/').takeIf { it.isNotEmpty() }
            directory.walkTopDown().filter { it != directory }.forEach { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                val name = listOfNotNull(normalizedPrefix, relative).joinToString("/") +
                    if (file.isDirectory) "/" else ""
                zip.putNextEntry(ZipEntry(name))
                if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}