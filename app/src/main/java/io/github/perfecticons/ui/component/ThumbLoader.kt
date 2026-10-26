package io.github.perfecticons.ui.component

import android.content.ComponentName
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.github.perfecticons.domain.model.IconId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Asynchronous thumbnail pipeline.
 *
 * Images are parsed on a bounded multi-thread pool, downsampled to the display
 * size and kept in a byte-budgeted LRU cache. Composition never blocks on a
 * decode, rows decode in parallel as they scroll in, and a cache hit renders
 * immediately. Keys include the file's timestamp and size so replacing an image
 * picks up the new content without leaving the screen.
 */
object ThumbLoader {

    /** Cache budget in bytes; holds thousands of downsampled thumbnails. */
    private const val CACHE_BYTES = 32 * 1024 * 1024

    /** Several decodes in parallel without starving the UI thread. */
    private val decodeDispatcher = Dispatchers.Default.limitedParallelism(4)

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun fileKey(file: File, targetPx: Int): String =
        "f|${file.absolutePath}|${file.lastModified()}|${file.length()}|$targetPx"

    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun fileThumb(file: File, targetPx: Int): Bitmap? = withContext(decodeDispatcher) {
        val key = fileKey(file, targetPx)
        cache.get(key) ?: decodeSampled(file, targetPx)?.also { cache.put(key, it) }
    }

    suspend fun drawableThumb(key: String, targetPx: Int, load: () -> Drawable?): Bitmap? =
        withContext(decodeDispatcher) {
            val cacheKey = "d|$key|$targetPx"
            cache.get(cacheKey) ?: runCatching {
                load()?.let { drawable ->
                    val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, targetPx, targetPx)
                    drawable.draw(canvas)
                    bitmap
                }
            }?.getOrNull()?.also { cache.put(cacheKey, it) }
        }

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }
}

/**
 * [file] as a [targetDp]-sized thumbnail, decoded off the composition thread.
 *
 * Returns null when the file is absent or not decodable. The cache key embeds
 * the file's mtime and size and is recomputed on every composition, so clearing
 * or replacing an image drops the previous bitmap immediately instead of
 * serving a stale thumbnail from the LRU cache.
 */
@Composable
fun rememberFileThumb(file: File?, targetDp: Dp): ImageBitmap? {
    if (file == null) return null
    val targetPx = with(LocalDensity.current) { targetDp.roundToPx() }
    val key = ThumbLoader.fileKey(file, targetPx)
    var bitmap by remember(key) { mutableStateOf(ThumbLoader.peek(key)) }
    LaunchedEffect(key) {
        bitmap = ThumbLoader.fileThumb(file, targetPx)
    }
    return bitmap?.let { remember(it) { it.asImageBitmap() } }
}

/** App icon variant: the package manager lookup runs on the decode pool too. */
@Composable
fun rememberAppIconThumb(packageName: String, targetDp: Dp): ImageBitmap? {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { targetDp.roundToPx() }
    val key = remember(packageName, targetPx) { "d|$packageName|$targetPx" }
    var bitmap by remember(key) { mutableStateOf(ThumbLoader.peek(key)) }
    LaunchedEffect(key) {
        bitmap = ThumbLoader.drawableThumb(packageName, targetPx) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
    }
    return bitmap?.let { remember(it) { it.asImageBitmap() } }
}

/**
 * Icon of one launchable entry: the component's own icon when it declares one,
 * otherwise the application icon. Aliases and activities that declare an icon
 * render differently from the app, and the theme writes those ids separately, so
 * the preview has to tell them apart.
 *
 * [packageName] and [className] are passed explicitly instead of decoding them
 * from [id]: a `pkg.Class` id cannot be split back into its package, because the
 * package boundary is not recoverable from the string alone.
 *
 * The icon is read from the component's declared resource rather than through
 * `getActivityIcon`, which refuses to draw a component the package manager
 * currently has disabled. Every alias an app switches its home-screen icon
 * through is disabled until it is picked, and those are exactly the entries
 * worth previewing here.
 */
@Composable
fun rememberEntryIconThumb(
    id: String,
    packageName: String,
    className: String?,
    targetDp: Dp,
): ImageBitmap? {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { targetDp.roundToPx() }
    val key = remember(id, targetPx) { "e|$id|$targetPx" }
    var bitmap by remember(key) { mutableStateOf(ThumbLoader.peek(key)) }
    LaunchedEffect(key) {
        bitmap = ThumbLoader.drawableThumb(key, targetPx) {
            val manager = context.packageManager
            val activityIcon = className?.let { name ->
                runCatching {
                    // MATCH_DISABLED_COMPONENTS is required: an alias is disabled
                    // until the app flips it on, and without the flag the lookup
                    // returns nothing for exactly the entries being previewed.
                    val info = manager.getActivityInfo(
                        ComponentName(packageName, name),
                        PackageManager.MATCH_DISABLED_COMPONENTS,
                    )
                    when {
                        info.icon != 0 -> manager.getResourcesForApplication(info.applicationInfo)
                            .getDrawable(info.icon, null)
                        // The component declares no icon of its own and inherits the
                        // application one.
                        else -> manager.getApplicationIcon(packageName)
                    }
                }.getOrNull()
            }
            activityIcon
                ?: runCatching { manager.getApplicationIcon(packageName) }.getOrNull()
        }
    }
    return bitmap?.let { remember(it) { it.asImageBitmap() } }
}
