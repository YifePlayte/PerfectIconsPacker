package io.github.perfecticons.domain

import io.github.perfecticons.data.custom.SafeUnzip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeUnzipTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(entries: List<Pair<String, ByteArray>>): File {
        val file = temp.newFile("archive-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * Writes entries into the central directory verbatim. [ZipOutputStream]
     * strips a leading slash and silently allows duplicate names, which would
     * hide the exact cases the extractor must reject.
     */
    private fun rawZip(entries: List<Pair<String, ByteArray>>): File {
        val file = temp.newFile("raw-${System.nanoTime()}.zip")
        val body = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        val buffer = java.io.ByteArrayOutputStream()
        val data = java.io.ByteArrayOutputStream()
        var offset = 0

        entries.forEach { (path, bytes) ->
            val nameBytes = path.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(bytes) }.value
            val localStart = offset

            data.reset()
            data.write(intLe(0x04034b50))
            data.write(shortLe(20))
            data.write(shortLe(0x0800))
            data.write(shortLe(0))
            data.write(shortLe(0))
            data.write(shortLe(0))
            data.write(intLe(crc.toInt()))
            data.write(intLe(bytes.size))
            data.write(intLe(bytes.size))
            data.write(shortLe(nameBytes.size))
            data.write(shortLe(0))
            data.write(nameBytes)
            data.write(bytes)
            buffer.write(data.toByteArray())
            offset += data.size()

            central.write(intLe(0x02014b50))
            central.write(shortLe(20))
            central.write(shortLe(20))
            central.write(shortLe(0x0800))
            central.write(shortLe(0))
            central.write(shortLe(0))
            central.write(shortLe(0))
            central.write(intLe(crc.toInt()))
            central.write(intLe(bytes.size))
            central.write(intLe(bytes.size))
            central.write(shortLe(nameBytes.size))
            central.write(shortLe(0))
            central.write(shortLe(0))
            central.write(shortLe(0))
            central.write(shortLe(0))
            central.write(intLe(0))
            central.write(intLe(localStart))
            central.write(nameBytes)
        }

        val centralStart = offset
        buffer.write(central.toByteArray())
        offset += central.size()
        buffer.write(intLe(0x06054b50))
        buffer.write(shortLe(0))
        buffer.write(shortLe(0))
        buffer.write(shortLe(entries.size))
        buffer.write(shortLe(entries.size))
        buffer.write(intLe(central.size()))
        buffer.write(intLe(centralStart))
        buffer.write(shortLe(0))
        file.writeBytes(buffer.toByteArray())
        return file
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun assertRejected(entries: List<Pair<String, ByteArray>>) {
        val destination = temp.newFolder("out-${System.nanoTime()}")
        val failure = runCatching {
            SafeUnzip.extract(rawZip(entries), destination)
        }.exceptionOrNull()
        assertTrue("应拒绝该压缩包", failure is SafeUnzip.UnsafeArchiveException)
        assertEquals(0, destination.walkTopDown().count { it.isFile })
    }

    @Test
    fun `parent traversal is rejected`() {
        assertRejected(listOf("../escape.txt" to byteArrayOf(1)))
        assertRejected(listOf("a/../../escape.txt" to byteArrayOf(1)))
    }

    @Test
    fun `absolute path is rejected`() {
        assertRejected(listOf("/etc/passwd" to byteArrayOf(1)))
    }

    @Test
    fun `backslash traversal is rejected`() {
        assertRejected(listOf("..\\..\\escape.txt" to byteArrayOf(1)))
    }

    @Test
    fun `null byte is rejected`() {
        assertRejected(listOf("bad\u0000name.txt" to byteArrayOf(1)))
    }

    @Test
    fun `duplicate entries are rejected`() {
        assertRejected(
            listOf(
                "same.txt" to byteArrayOf(1),
                "same.txt" to byteArrayOf(2),
            ),
        )
    }

    @Test
    fun `dot segments are normalized instead of escaping`() {
        val destination = temp.newFolder("out-dot")
        SafeUnzip.extract(
            zip(listOf("./icons/com.example/0.png" to byteArrayOf(9))),
            destination,
        )
        assertTrue(File(destination, "icons/com.example/0.png").isFile)
    }

    @Test
    fun `sibling prefix directory is not treated as inside root`() {
        val parent = temp.newFolder("parent")
        val root = File(parent, "root").apply { mkdirs() }
        val sibling = File(parent, "root-evil").apply { mkdirs() }
        assertFalse(sibling.canonicalPath.startsWith(root.canonicalPath + File.separator))
        // The implementation must compare with a separator suffix; sibling paths
        // sharing a string prefix must not pass the containment check.
        assertTrue(sibling.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun `oversized entry is rejected`() {
        val destination = temp.newFolder("out-big")
        val big = ByteArray((SafeUnzip.MAX_ENTRY_BYTES + 1).toInt())
        val failure = runCatching {
            SafeUnzip.extract(zip(listOf("big.bin" to big)), destination)
        }.exceptionOrNull()
        assertTrue(failure is SafeUnzip.UnsafeArchiveException)
    }

    @Test
    fun `valid archive extracts every file`() {
        val destination = temp.newFolder("out-valid")
        SafeUnzip.extract(
            zip(
                listOf(
                    "layer_animating_icons/com.example/config.xml" to "<Config/>".toByteArray(),
                    "layer_animating_icons/com.example/0/quiet/quietImage.png" to byteArrayOf(1, 2),
                ),
            ),
            destination,
        )
        assertEquals(
            "<Config/>",
            File(destination, "layer_animating_icons/com.example/config.xml").readText(),
        )
        assertEquals(
            2,
            File(destination, "layer_animating_icons/com.example/0/quiet/quietImage.png").length(),
        )
    }
}
