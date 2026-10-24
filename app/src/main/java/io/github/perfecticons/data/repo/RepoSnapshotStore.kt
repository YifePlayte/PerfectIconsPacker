package io.github.perfecticons.data.repo

import android.util.Log
import java.io.File
import java.util.Properties

/**
 * Persists snapshot metadata next to the materialized files.
 *
 * A plain properties file keeps the dependency surface small and makes the
 * snapshot inspectable during development.
 */
object RepoSnapshotStore {

    private const val FILE_NAME = "snapshot.properties"
    private const val TAG = "RepoSnapshotStore"

    fun save(root: File, snapshot: RepoSnapshot) {
        val target = File(root, FILE_NAME)
        val temp = File(root, "$FILE_NAME.tmp")
        val properties = Properties().apply {
            setProperty("commit", snapshot.commit)
            setProperty("styles", snapshot.styles.joinToString(","))
            setProperty("syncedAt", snapshot.syncedAt.toString())
            snapshot.sourceRepo?.let { setProperty("sourceRepo", it) }
            setProperty("file.count", snapshot.files.size.toString())
            snapshot.files.forEachIndexed { index, file ->
                setProperty("file.$index.path", file.path)
                setProperty("file.$index.blob", file.blobId)
                setProperty("file.$index.size", file.size.toString())
            }
        }
        temp.outputStream().use { properties.store(it, null) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun load(root: File): RepoSnapshot? {
        val source = File(root, FILE_NAME)
        if (!source.isFile) return null
        return runCatching {
            val properties = Properties().apply { source.inputStream().use { load(it) } }
            val count = properties.getProperty("file.count")?.toIntOrNull() ?: 0
            val files = (0 until count).mapNotNull { index ->
                val path = properties.getProperty("file.$index.path") ?: return@mapNotNull null
                val blob = properties.getProperty("file.$index.blob") ?: return@mapNotNull null
                RepoFile(
                    path = path,
                    blobId = blob,
                    size = properties.getProperty("file.$index.size")?.toLongOrNull() ?: 0L,
                )
            }
            RepoSnapshot(
                commit = properties.getProperty("commit") ?: return null,
                styles = properties.getProperty("styles")
                    ?.split(',')
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                syncedAt = properties.getProperty("syncedAt")?.toLongOrNull() ?: 0L,
                files = files,
                sourceRepo = properties.getProperty("sourceRepo"),
            )
        }.onFailure { Log.w(TAG, "cannot read snapshot", it) }.getOrNull()
    }
}
