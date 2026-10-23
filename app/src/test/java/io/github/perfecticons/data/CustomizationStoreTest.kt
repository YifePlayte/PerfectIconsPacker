package io.github.perfecticons.data

import io.github.perfecticons.data.custom.CustomizationStore
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomizationStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun png(width: Int = 8, height: Int = 8): ByteArray {
        val header = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(header)
        header[16] = (width shr 24).toByte()
        header[17] = (width shr 16).toByte()
        header[18] = (width shr 8).toByte()
        header[19] = width.toByte()
        header[20] = (height shr 24).toByte()
        header[21] = (height shr 16).toByte()
        header[22] = (height shr 8).toByte()
        header[23] = height.toByte()
        return header
    }

    @Test
    fun `saving one layer keeps the other`() {
        val root = temp.newFolder("custom")
        val store = CustomizationStore(root)
        store.ensureLayout()

        store.saveStatic("com.test", png(), null).getOrThrow()
        store.saveStatic("com.test", null, png()).getOrThrow()

        val dir = File(root, "static/com.test")
        assertTrue(File(dir, "0.png").isFile)
        assertTrue(File(dir, "1.png").isFile)
    }

    @Test
    fun `saving without any layer creates a placeholder entry`() {
        val root = temp.newFolder("custom")
        val store = CustomizationStore(root)
        store.ensureLayout()

        store.saveStatic("com.test", null, null).getOrThrow()

        val entry = store.staticIcons().single()
        assertTrue(!entry.hasBackground && !entry.hasForeground)
    }

    @Test
    fun `clearing a layer keeps the entry and the other layer`() {
        val root = temp.newFolder("custom")
        val store = CustomizationStore(root)
        store.ensureLayout()
        store.saveStatic("com.test", png(), png()).getOrThrow()

        store.clearStaticLayer("com.test", "0.png").getOrThrow()

        val entry = store.staticIcons().single()
        assertTrue(!entry.hasBackground)
        assertTrue(entry.hasForeground)
    }

    @Test
    fun `saving a single layer is valid`() {
        val root = temp.newFolder("custom")
        val store = CustomizationStore(root)
        store.ensureLayout()

        store.saveStatic("com.test", png(), null).getOrThrow()

        val entry = store.staticIcons().single()
        assertTrue(entry.hasBackground)
        assertTrue(!entry.hasForeground)
    }

    @Test
    fun `dynamic placeholder entry is valid and importable`() {
        val root = temp.newFolder("custom")
        val store = CustomizationStore(root)
        store.ensureLayout()

        store.createDynamicEntry("com.test").getOrThrow()

        val entry = store.dynamicIcons().single()
        assertTrue(entry.fileCount == 0)
    }

    @Test
    fun `dynamic previews stack per layer in order`() {
        val root = temp.newFolder("custom")
        val store = CustomizationStore(root)
        store.ensureLayout()
        File(root, "dynamic/com.test/1/quiet").mkdirs()
        File(root, "dynamic/com.test/0/quiet").mkdirs()
        File(root, "dynamic/com.test/1/quiet/quietImage.png").writeBytes(png())
        File(root, "dynamic/com.test/0/quiet/quietImage.png").writeBytes(png())

        val entry = store.dynamicIcons().single()

        assertTrue(entry.previews.map { it.invariantSeparatorsPath } == listOf(
            "${root.invariantSeparatorsPath}/dynamic/com.test/0/quiet/quietImage.png",
            "${root.invariantSeparatorsPath}/dynamic/com.test/1/quiet/quietImage.png",
        ))
    }
}
