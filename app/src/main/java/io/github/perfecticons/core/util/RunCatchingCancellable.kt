package io.github.perfecticons.core.util

import kotlinx.coroutines.CancellationException

/** [runCatching] that keeps structured cancellation intact. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (t: Throwable) {
    Result.failure(t)
}
