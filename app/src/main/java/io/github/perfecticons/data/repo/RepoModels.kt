package io.github.perfecticons.data.repo

import java.io.File

/** A file selected from the upstream tree. */
data class RepoFile(
    val path: String,
    val blobId: String,
    val size: Long,
)

/** Snapshot metadata persisted next to the materialized files. */
data class RepoSnapshot(
    val commit: String,
    val styles: List<String>,
    val syncedAt: Long,
    val files: List<RepoFile>,
    /**
     * Upstream repository the snapshot was fetched from. Null for snapshots
     * written before this field existed. Compared against the configured
     * repository to flag a snapshot that came from somewhere else.
     */
    val sourceRepo: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

data class RepoStatus(
    val snapshot: RepoSnapshot?,
    val snapshotDir: File,
    val gitDir: File,
) {
    val isSynced: Boolean get() = snapshot != null
}

sealed interface SyncProgress {
    data class FetchingTrees(val attempt: Int) : SyncProgress
    data class Resolving(val commit: String) : SyncProgress
    data class FetchingBlobs(val fetched: Int, val total: Int, val batch: Int, val batches: Int) : SyncProgress
    data class Materializing(val written: Int, val total: Int) : SyncProgress
    data class Done(val commit: String, val files: Int, val bytes: Long, val elapsedMs: Long) : SyncProgress
    data class Failed(val message: String, val cause: Throwable? = null) : SyncProgress
}
