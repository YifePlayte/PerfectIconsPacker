package io.github.perfecticons.data.repo

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes snapshot access across components: a sync replaces the tree while
 * a build reads it, and the two run from independent view models.
 */
object RepoLock {
    val snapshot: Mutex = Mutex()
}
