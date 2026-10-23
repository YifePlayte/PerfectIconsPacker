package io.github.perfecticons.data.repo

import android.content.Context
import java.io.File

object RepoPaths {
    fun root(context: Context): File = File(context.filesDir, "repo")

    fun customRoot(context: Context): File = File(context.filesDir, "custom")

    fun buildsRoot(context: Context): File = File(context.filesDir, "builds")

    /**
     * Extracts the bundled HyperOS 4 mask once and returns it. The file lives in
     * the custom root so users can inspect or replace it through SAF, but the
     * original asset is copied verbatim.
     */
    fun defaultTransformConfig(context: Context): File {
        val target = File(context.filesDir, "defaults/transform_config.xml")
        if (target.isFile) return target
        target.parentFile?.mkdirs()
        context.assets.open("mask/hyperos4_transform_config.xml").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
