package io.github.perfecticons.domain.build

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.PathParser
import io.github.perfecticons.domain.model.AssembledTheme
import io.github.perfecticons.domain.validation.XmlSupport
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream

/**
 * Generates the optional MTZ artwork: an icon grid preview and a wallpaper.
 *
 * Nothing third-party is bundled. The wallpaper is a neutral gradient drawn at
 * build time, and the preview is composed from icons that are already part of the
 * theme, so no extra licensing applies.
 */
class ThemeArtwork(
    private val theme: AssembledTheme,
    private val workDir: File,
    /**
     * Icon ids in preview priority order. Usually derived from the launcher apps
     * installed on this device so the preview starts with familiar icons.
     */
    private val priority: List<String> = emptyList(),
) : MtzBuilder.ArtworkProvider {
    override fun renderPreviews(): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        listOf(GRID_2X3, GRID_4X3, GRID_LOCKSCREEN).forEach { spec ->
            renderGrid(spec)?.let { result += "preview/${spec.fileName}" to it }
        }
        return result
    }

    private fun renderGrid(spec: PreviewGrid): File? {
        val cell = spec.cell
        val columns = spec.columns
        val rows = spec.rows
        val width = spec.width
        val height = spec.height
        val byId = theme.staticIcons.associateBy { it.id }
        val ranked = buildList {
            priority.forEach { id -> byId[id]?.let { add(it) } }
            theme.staticIcons.forEach { icon -> if (icon.id !in priority) add(icon) }
        }
        val inset = (cell * PREVIEW_ICON_INSET_RATIO).toInt()
        val iconSize = cell - 2 * inset
        val mask = loadMaskPath()
        val candidates = ranked
            .take(columns * rows)
            .mapNotNull { icon ->
                // Decode above the draw size so the pre-clip scale-up stays sharp.
                val decodeSize = (iconSize * PREVIEW_LAYER_SCALE).toInt()
                val background = icon.background?.let { decode(File(it), decodeSize) }
                val foreground = icon.foreground?.let { decode(File(it), decodeSize) }
                val composed = compose(background, foreground, iconSize, mask)
                background?.recycle()
                foreground?.recycle()
                composed
            }
        if (candidates.isEmpty()) return null

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                PREVIEW_BACKGROUND_TOP,
                PREVIEW_BACKGROUND_BOTTOM,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val gapX = (width - columns * cell) / (columns + 1)
        val gapY = gapX
        val gridHeight = rows * cell + (rows - 1) * gapY
        val startY = (height - gridHeight) / 2

        candidates.forEachIndexed { index, bitmap ->
            val column = index % columns
            val row = index / columns
            // Icons sit centered in their cell with an equal bleed margin on
            // every side so the masked artwork never touches its neighbours.
            val cellLeft = gapX + column * (cell + gapX)
            val cellTop = startY + row * (cell + gapY)
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(
                    cellLeft + inset,
                    cellTop + inset,
                    cellLeft + inset + iconSize,
                    cellTop + inset + iconSize,
                ),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            bitmap.recycle()
        }

        val file = File(workDir, spec.fileName)
        FileOutputStream(file).use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, stream)
        }
        output.recycle()
        return file
    }


    private fun decode(file: File, targetSize: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetSize * 2 || bounds.outHeight / sample > targetSize * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /**
     * Composites the layers inside the icon mask. The mask is the
     * `ConfigIconMask` SVG path from the transform config, expressed in a
     * 100x100 space; it is scaled to the target size and used as an
     * anti-aliased clip so previews match how the launcher masks icons.
     */
    private fun compose(background: Bitmap?, foreground: Bitmap?, size: Int, mask: Path?): Bitmap {
        val composed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOfNotNull(background, foreground).forEach { layer ->
            val scale = PREVIEW_LAYER_SCALE * size.toFloat() / maxOf(layer.width, layer.height)
            val drawnWidth = layer.width * scale
            val drawnHeight = layer.height * scale
            val left = (size - drawnWidth) / 2f
            val top = (size - drawnHeight) / 2f
            canvas.drawBitmap(
                layer,
                null,
                Rect(
                    left.toInt(),
                    top.toInt(),
                    (left + drawnWidth).toInt(),
                    (top + drawnHeight).toInt(),
                ),
                paint,
            )
        }
        if (mask == null) return composed

        val masked = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(masked)
        val scaledMask = Path(mask)
        scaledMask.transform(Matrix().apply { postScale(size / 100f, size / 100f) })
        maskCanvas.drawPath(
            scaledMask,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(composed, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            },
        )
        composed.recycle()
        return masked
    }

    /** The icon mask SVG path from the effective transform config, if any. */
    private fun loadMaskPath(): Path? {
        val config = theme.transformConfig ?: return null
        val root = XmlSupport.parse(File(config)) ?: return null
        val nodes = root.getElementsByTagName("Config")
        val value = (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .firstOrNull { it.getAttribute("name") == "ConfigIconMask" }
            ?.getAttribute("value")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { PathParser.createPathFromPathData(value) }.getOrNull()
    }

    private data class PreviewGrid(
        val columns: Int,
        val rows: Int,
        val width: Int,
        val height: Int,
        val cell: Int,
        val fileName: String,
    )

    private companion object {
        /** Grids are named `<rows>x<columns>`: 3 columns in every preview. */
        val GRID_2X3 = PreviewGrid(3, 2, 486, 320, 128, "preview_icons_2x3_0.jpg")
        val GRID_4X3 = PreviewGrid(3, 4, 1080, 1560, 280, "preview_icons_4x3_0.jpg")

        /** A 4x3 icon render at full-screen resolution for the lockscreen slot. */
        val GRID_LOCKSCREEN = PreviewGrid(3, 4, 1080, 2340, 280, "preview_lockscreen_0.jpg")

        /** Equal bleed margin on every side of a cell's icon artwork. */
        const val PREVIEW_ICON_INSET_RATIO = 0.125f

        /**
         * Layer scale applied from the center before the mask clip. Android
         * adaptive icon layers keep a transparent bleed around the artwork, so
         * clipping them directly leaves the art floating inside the mask; the
         * layers are scaled up first and only then clipped. Tune with
         * `docs/preview-scale-tuner.html`.
         */
        const val PREVIEW_LAYER_SCALE = 1.5f
        const val PREVIEW_QUALITY = 92


        const val PREVIEW_BACKGROUND_TOP = 0xFFF7F7FA.toInt()
        const val PREVIEW_BACKGROUND_BOTTOM = 0xFFE6E6EE.toInt()
    }
}
