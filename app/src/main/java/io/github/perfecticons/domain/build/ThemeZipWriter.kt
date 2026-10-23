package io.github.perfecticons.domain.build

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zip writer tuned for theme archives.
 *
 * PNG files are already compressed, and inner archives are deflated already, so
 * both are stored to avoid double compression. Text entries are deflated.
 */
class ThemeZipWriter(
    output: OutputStream,
    private val storedExtensions: Set<String> = setOf("png", "jpg", "jpeg", "webp", "zip"),
) : java.io.Closeable {
    private val zip = ZipOutputStream(output)
    private var closed = false

    /** Inner theme archives carry no extension; they are already compressed. */
    private val storedNames = setOf("icons", "dynamicicons")

    fun addFile(entryName: String, file: File) {
        val path = SafeZipPath.requireSafe(entryName)
        val entry = ZipEntry(path)
        if (file.extension.lowercase() in storedExtensions ||
            path.substringAfterLast('/').lowercase() in storedNames
        ) {
            entry.method = ZipEntry.STORED
            entry.size = file.length()
            entry.compressedSize = file.length()
            entry.crc = crcOf(file)
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        entry.time = file.lastModified().coerceAtLeast(0L)
        zip.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zip, DEFAULT_BUFFER_SIZE) }
        zip.closeEntry()
    }

    fun addBytes(entryName: String, bytes: ByteArray, deflated: Boolean = true) {
        val path = SafeZipPath.requireSafe(entryName)
        val entry = ZipEntry(path)
        if (deflated) {
            entry.method = ZipEntry.DEFLATED
        } else {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun addDirectory(entryName: String) {
        if (entryName.isBlank()) return
        val path = SafeZipPath.requireSafe(entryName)
        zip.putNextEntry(ZipEntry("$path/"))
        zip.closeEntry()
    }

    override fun close() {
        if (closed) return
        closed = true
        zip.finish()
        zip.close()
    }

    private fun crcOf(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
