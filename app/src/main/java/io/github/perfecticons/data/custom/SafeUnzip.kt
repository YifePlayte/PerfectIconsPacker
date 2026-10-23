package io.github.perfecticons.data.custom

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Extracts zip entries after validating every path.
 *
 * Imported archives come from users, so traversal, absolute paths, duplicate
 * entries, oversized entries and excessive entry counts are all rejected before
 * anything touches the destination.
 */
object SafeUnzip {

    const val MAX_ENTRIES = 20_000
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    class UnsafeArchiveException(message: String) : IOException(message)

    fun extract(zipFile: File, destination: File) {
        destination.mkdirs()
        try {
            extractInternal(zipFile, destination)
        } catch (t: Throwable) {
            // Never leave a partially extracted archive behind: callers use this
            // output as if it were a complete import.
            destination.deleteRecursively()
            throw t
        }
    }

    private fun extractInternal(zipFile: File, destination: File) {
        val seen = HashSet<String>()
        var total = 0L
        var count = 0

        zipFile.inputStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) {
                        throw UnsafeArchiveException("压缩包条目过多")
                    }

                    val rawName = entry.name
                    validateRaw(rawName)
                    val normalized = normalize(rawName)
                    if (entry.isDirectory) {
                        if (normalized.isNotEmpty()) {
                            File(destination, normalized).mkdirs()
                        }
                        zip.closeEntry()
                        continue
                    }
                    if (normalized.isEmpty()) {
                        throw UnsafeArchiveException("空文件名")
                    }
                    if (!seen.add(normalized)) {
                        throw UnsafeArchiveException("重复条目：$normalized")
                    }

                    val target = File(destination, normalized)
                    val parent = target.parentFile
                        ?: throw UnsafeArchiveException("非法目标：$normalized")
                    parent.mkdirs()
                    val rootPath = destination.canonicalPath.trimEnd(File.separatorChar)
                    val parentPath = parent.canonicalPath
                    if (parentPath != rootPath &&
                        !parentPath.startsWith(rootPath + File.separator)
                    ) {
                        throw UnsafeArchiveException("路径穿越：$normalized")
                    }

                    var written = 0L
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            total += read
                            if (written > MAX_ENTRY_BYTES) {
                                throw UnsafeArchiveException("单个文件过大：$normalized")
                            }
                            if (total > MAX_TOTAL_BYTES) {
                                throw UnsafeArchiveException("压缩包解压后过大")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    fun listEntries(zipFile: File): List<String> = buildList {
        zipFile.inputStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name.replace('\\', '/'))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun normalize(path: String): String = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")

    /**
     * Validates the entry name before normalization, because normalization
     * removes the leading empty segment that marks an absolute path.
     */
    private fun validateRaw(rawName: String) {
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) throw UnsafeArchiveException("绝对路径：$rawName")
        if (name.contains('\u0000')) throw UnsafeArchiveException("非法字符：$rawName")
        if (name.split('/').any { it == ".." }) throw UnsafeArchiveException("路径穿越：$rawName")
    }
}
