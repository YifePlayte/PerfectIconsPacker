package io.github.perfecticons.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectIdRef
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.FilterSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Reads the upstream icon repository directly, without cloning it.
 *
 * `meta.git` is a shallow partial clone (`blob:none`) holding only the commit and
 * trees. `blobs.git` is a normal non-shallow bare repository that receives blobs by
 * SHA. A shallow repository rejects SHA requests for blobs it never fetched, which
 * is why two stores are needed.
 */
class IconRepository(
    private val root: File,
    private val remoteUrls: List<String> = listOf(DEFAULT_REMOTE),
) {
    private var activeRemote: String = remoteUrls.firstOrNull() ?: DEFAULT_REMOTE

    private val metaDir = File(root, "meta.git")
    private val blobsDir = File(root, "blobs.git")
    val snapshotDir = File(root, "snapshot")

    suspend fun sync(
        styles: Set<String>,
        onProgress: (SyncProgress) -> Unit,
    ): RepoSnapshot = RepoLock.snapshot.withLock { syncLocked(styles, onProgress) }

    private suspend fun syncLocked(
        styles: Set<String>,
        onProgress: (SyncProgress) -> Unit,
    ): RepoSnapshot = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val opened = mutableListOf<Repository>()
        try {
            root.mkdirs()
            val meta = openBare(metaDir).also { opened += it }
            val blobs = openBare(blobsDir).also { opened += it }

            onProgress(SyncProgress.FetchingTrees(attempt = 1))
            activeRemote = fetchTrees(meta)

            val head = meta.exactRef(MAIN_REF)
                ?: error("无法获取 $MAIN_REF")
            val commitId = head.objectId
            onProgress(SyncProgress.Resolving(commitId.name))

            val selected = collectSelectedPaths(meta, commitId, styles)
            if (selected.isEmpty()) error("上游仓库没有可用图标资源")

            val missing = selected.filter { !blobs.objectDatabase.has(ObjectId.fromString(it.blobId)) }

            if (missing.isNotEmpty()) {
                configureRemote(blobs, activeRemote)
                val batches = missing.chunked(BLOB_BATCH_SIZE)
                var fetched = 0
                batches.forEachIndexed { index, batch ->
                    coroutineContext.ensureActive()
                    fetchBatch(blobs, batch)
                    fetched += batch.size
                    onProgress(
                        SyncProgress.FetchingBlobs(
                            fetched = fetched,
                            total = missing.size,
                            batch = index + 1,
                            batches = batches.size,
                        ),
                    )
                }
            }

            onProgress(SyncProgress.Materializing(written = 0, total = selected.size))
            val sized = selected.map { file ->
                file.copy(
                    size = runCatching {
                        blobs.open(ObjectId.fromString(file.blobId)).size.toLong()
                    }.getOrDefault(0L),
                )
            }
            materialize(blobs, sized, onProgress)

            val snapshot = RepoSnapshot(
                commit = commitId.name,
                styles = styles.sorted(),
                syncedAt = System.currentTimeMillis(),
                files = sized,
            )
            RepoSnapshotStore.save(root, snapshot)

            val elapsed = System.currentTimeMillis() - startedAt
            onProgress(SyncProgress.Done(commitId.name, sized.size, snapshot.totalBytes, elapsed))

            snapshot
        } catch (t: Throwable) {
            Log.e(TAG, "sync failed", t)
            onProgress(SyncProgress.Failed(t.message ?: t::class.java.simpleName, t))
            throw t
        } finally {
            // Failure paths used to leak the bare repository handles.
            opened.forEach { runCatching { it.close() } }
        }
    }

    fun status(): RepoStatus = RepoStatus(
        snapshot = RepoSnapshotStore.load(root),
        snapshotDir = snapshotDir,
        gitDir = root,
    )

    /**
     * Latest upstream `main` commit from a lightweight ls-remote, or `null`
     * when every endpoint is unreachable. Used to tell the user whether their
     * snapshot is current without downloading anything.
     */
    suspend fun remoteHead(): String? = withContext(Dispatchers.IO) {
        val endpoints = remoteUrls.filter { it.isNotBlank() }.ifEmpty { listOf(DEFAULT_REMOTE) }
        for (url in endpoints) {
            val head = runCatching {
                Git.lsRemoteRepository()
                    .setRemote(url)
                    .setHeads(true)
                    .setTransportConfigCallback { transport -> transport.timeout = 60 }
                    .call()
                    .firstOrNull { it.name == MAIN_HEAD }
                    ?.objectId
                    ?.name
            }.getOrNull()
            if (head != null) return@withContext head
        }
        null
    }

    /**
     * Style folders present upstream (`themes/<style>`), read from the fetched
     * tree. Empty before the first sync; `common` is not a selectable style.
     */
    fun availableStyles(): List<String> {
        val meta = openBare(metaDir)
        try {
            val head = meta.exactRef(MAIN_REF)?.objectId ?: return emptyList()
            RevWalk(meta).use { walk ->
                val tree = walk.parseCommit(head).tree
                TreeWalk(meta).use { tw ->
                    tw.addTree(tree)
                    tw.isRecursive = false
                    while (tw.next()) {
                        if (tw.nameString != "themes" || !tw.isSubtree) continue
                        tw.enterSubtree()
                        val styles = mutableListOf<String>()
                        while (tw.next()) {
                            if (tw.isSubtree && tw.nameString != PathSelector.COMMON_STYLE) {
                                styles += tw.nameString
                            }
                        }
                        return styles.sorted()
                    }
                }
            }
        } finally {
            meta.close()
        }
        return emptyList()
    }

    /** Returns the snapshot only if it matches the requested style set. */
    fun snapshotForStyles(styles: Set<String>): RepoSnapshot? {
        val snapshot = RepoSnapshotStore.load(root) ?: return null
        return snapshot.takeIf { it.styles.toSet() == styles }
    }

    fun clear() {
        root.deleteRecursively()
    }

    /** Strips any userinfo so credentials never reach logcat. */
    private fun redactUrl(url: String): String =
        url.replace(Regex("([^/]*//)[^@/]*@"), "$1")

    private fun openBare(dir: File): Repository {
        dir.mkdirs()
        return FileRepositoryBuilder()
            .setGitDir(dir)
            .setBare()
            .build()
            .also { repository ->
                if (!File(dir, "config").exists()) repository.create(true)
            }
    }

    private fun configureRemote(repository: Repository, url: String) {
        repository.config.apply {
            setString("remote", "origin", "url", url)
            setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            setBoolean("gc", null, "auto", false)
            save()
        }
    }

    private suspend fun fetchTrees(repository: Repository): String =
        retrying("读取仓库目录") { _, url ->
            configureRemote(repository, url)
            Transport.open(repository, "origin").use { transport ->
                transport.setDepth(1)
                transport.setFilterSpec(FilterSpec.fromFilterLine("blob:none"))
                transport.timeout = NETWORK_TIMEOUT_SECONDS
                transport.fetch(
                    NullProgressMonitor.INSTANCE,
                    listOf(RefSpec("+refs/heads/main:$MAIN_REF")),
                )
            }
            url
        }

    private suspend fun fetchBatch(repository: Repository, batch: List<RepoFile>) {
        val refs: List<Ref> = batch.mapIndexed { index, file ->
            ObjectIdRef.Unpeeled(
                Ref.Storage.NETWORK,
                "refs/heads/__batch/$index",
                ObjectId.fromString(file.blobId),
            )
        }
        retrying("下载资源") { _, url ->
            configureRemote(repository, url)
            Transport.open(repository, "origin").use { transport ->
                transport.timeout = NETWORK_TIMEOUT_SECONDS
                transport.openFetch().use { connection ->
                    connection.fetch(NullProgressMonitor.INSTANCE, refs, emptySet())
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    /**
     * Tries every configured endpoint, then retries the whole list with
     * exponential backoff. The accelerated endpoint is preferred, but the direct
     * repository URL is always the last resort.
     */
    private suspend fun <T> retrying(operation: String, block: (attempt: Int, url: String) -> T): T {
        val endpoints = remoteUrls.filter { it.isNotBlank() }.ifEmpty { listOf(DEFAULT_REMOTE) }
        var last: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            for (url in endpoints) {
                coroutineContext.ensureActive()
                try {
                    return block(attempt, url)
                } catch (t: Throwable) {
                    last = t
                    Log.w(TAG, "$operation failed via ${redactUrl(url)}", t)
                }
            }
            if (attempt < MAX_ATTEMPTS) {
                val delayMs = RETRY_BASE_DELAY_MS shl (attempt - 1)
                Log.w(TAG, "$operation failed on attempt $attempt, retrying in $delayMs ms", last)
                kotlinx.coroutines.delay(delayMs)
            }
        }
        throw last ?: IllegalStateException("$operation failed")
    }

    private fun collectSelectedPaths(
        repository: Repository,
        commitId: ObjectId,
        styles: Set<String>,
    ): List<RepoFile> = buildList {
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk(repository).use { tw ->
                tw.addTree(commit.tree)
                tw.isRecursive = true
                while (tw.next()) {
                    val path = tw.pathString
                    if (!PathSelector.isSelected(path, styles)) continue
                    add(RepoFile(path, tw.getObjectId(0).name, 0L))
                }
            }
        }
    }

    private fun ObjectId.sizeOrZero(repository: Repository): Long =
        runCatching { repository.open(this).size.toLong() }.getOrDefault(0L)

    private fun materialize(
        repository: Repository,
        files: List<RepoFile>,
        onProgress: (SyncProgress) -> Unit,
    ) {
        val staging = File(root, "snapshot.tmp")
        staging.deleteRecursively()
        staging.mkdirs()

        files.forEachIndexed { index, file ->
            val target = File(staging, file.path)
            // Tree paths are remote data; nothing may land outside staging.
            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                "非法资源路径：${file.path}"
            }
            target.parentFile?.mkdirs()
            repository.open(ObjectId.fromString(file.blobId)).openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (index % PROGRESS_EVERY == 0 || index == files.lastIndex) {
                onProgress(SyncProgress.Materializing(written = index + 1, total = files.size))
            }
        }

        snapshotDir.deleteRecursively()
        if (!staging.renameTo(snapshotDir)) {
            staging.copyRecursively(snapshotDir, overwrite = true)
            staging.deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "IconRepository"
        const val DEFAULT_REMOTE = "https://github.com/pzcn/Perfect-Icons-Completion-Project.git"
        private const val MAIN_REF = "refs/remotes/origin/main"
        private const val MAIN_HEAD = "refs/heads/main"
        private const val BLOB_BATCH_SIZE = 100
        private const val PROGRESS_EVERY = 200
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_BASE_DELAY_MS = 1_500L
        private const val NETWORK_TIMEOUT_SECONDS = 60
    }
}
