package io.github.perfecticons.domain.build

import io.github.perfecticons.domain.model.AssembledTheme
import java.io.File

/**
 * Produces the single module zip that both Magisk and KernelSU accept.
 *
 * Layout follows the Magisk module spec (module.prop, customize.sh, META-INF)
 * because KernelSU's installer reads the same structure and ignores META-INF.
 * customize.sh deliberately does not set SKIPUNZIP so both installers extract the
 * complete module tree themselves.
 */
class ModuleBuilder(
    private val theme: AssembledTheme,
    private val workDir: File,
    private val onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
) {
    fun build(output: File, info: ModuleInfo): File {
        workDir.mkdirs()
        val archives = IconArchives(theme, workDir, onProgress).build(includeDynamicIcons = false)
        val icons = archives.icons
        val dynamicIcons = requireNotNull(archives.dynamicIcons) { "模块缺少 dynamicicons" }

        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, "${output.name}.tmp")
        temp.delete()

        ThemeZipWriter(temp.outputStream()).use { writer ->
            writer.addBytes("module.prop", info.toModuleProp().toByteArray(), deflated = true)
            writer.addBytes("customize.sh", customizeScript(info.style).toByteArray(), deflated = true)
            writer.addBytes(
                "META-INF/com/google/android/updater-script",
                UPDATER_SCRIPT.toByteArray(),
                deflated = true,
            )
            writer.addBytes(
                "META-INF/com/google/android/update-binary",
                UPDATE_BINARY.toByteArray(),
                deflated = true,
            )

            // Stage under a neutral directory. customize.sh moves it to the path
            // this ROM actually uses, because /system/media is a symlink to
            // /product/media on HyperOS 4 and a frozen archive path cannot know
            // where the device resolved it.
            writer.addFile("$STAGING/theme/default/icons", icons)
            writer.addFile("$STAGING/theme/default/dynamicicons", dynamicIcons)

            theme.flatIcons.forEach { (id, source) ->
                writer.addFile("$STAGING/theme/miui_mod_icons/$id.png", File(source))
            }

            theme.addons.forEach { (relative, source) ->
                val entry = "$STAGING/theme/default/$relative"
                if (SafeZipPath.isSafe(entry) &&
                    !SafeZipPath.isReservedFor(SafeZipPath.Target.Module, relative)
                ) {
                    writer.addFile(entry, File(source))
                }
            }
        }

        if (!temp.renameTo(output)) {
            temp.copyTo(output, overwrite = true)
            temp.delete()
        }
        return output
    }

    data class ModuleInfo(
        val id: String,
        val name: String,
        val version: String,
        val versionCode: Int,
        val author: String,
        val description: String,
        val style: String,
    ) {
        fun toModuleProp(): String = buildString {
            appendLine("id=$id")
            appendLine("name=${prop(name)}")
            appendLine("version=${prop(version)}")
            appendLine("versionCode=$versionCode")
            appendLine("author=${prop(author)}")
            appendLine("description=${prop(description)}")
            appendLine("theme=${prop(style)}")
        }

        /** Keeps free text on a single property line. */
        private fun prop(value: String): String =
            value.replace(Regex("[\\r\\n\\u0085\\u2028\\u2029]"), " ")
    }

    companion object {
        /**
         * Archive staging directory. The installer relocates it to
         * `system/<realpath of /system/media>/theme` at install time.
         */
        const val STAGING = "theme_staging"

        /**
         * `customize.sh` 的安装横幅与提示。主题名由 [ModuleInfo.style] 注入
         * （单选，调用方已兜底为空时用 HyperOS 4）。分隔线宽度跟随标题。
         */
        fun customizeScript(style: String): String {
            val title = "完美图标补全计划 · $style"
            val rule = "─".repeat(title.length + 2)
            return """
                #!/sbin/sh
                # $title
                # 同一份模块同时适配 Magisk 与 KernelSU。

                ui_print "$rule"
                ui_print " $title"
                ui_print "$rule"

                [ "${'$'}API" -ge 33 ] || abort "- 需要 Android 13 或更高版本。"

                if [ -L "/system/media" ]; then
                  MEDIAPATH=system"${'$'}(realpath /system/media)"
                elif [ -d "/system/media" ]; then
                  MEDIAPATH=system/media
                else
                  abort "- 无法定位 /system/media，ROM 可能不受支持。"
                fi

                ui_print "- 目标路径：${'$'}MEDIAPATH/theme"
                if [ ! -d "${'$'}MODPATH/$STAGING/theme" ]; then
                  abort "- 模块内容缺失，请重新构建。"
                fi
                # Move each entry rather than the staging directory itself: renaming
                # "<dir>/." fails with EBUSY on the module update mount.
                mkdir -p "${'$'}MODPATH/${'$'}MEDIAPATH"
                rm -rf "${'$'}MODPATH/${'$'}MEDIAPATH/theme"
                mv "${'$'}MODPATH/$STAGING/theme" "${'$'}MODPATH/${'$'}MEDIAPATH/theme" || abort "- 安装失败。"
                rm -rf "${'$'}MODPATH/$STAGING"

                ui_print "- 已写入：${'$'}MEDIAPATH/theme/default"
                ls "${'$'}MODPATH/${'$'}MEDIAPATH/theme/default" 2>/dev/null | while read -r name; do
                  ui_print "    ${'$'}name"
                done

                # 单张图标单独成目录，内容和 default 是两个体系，这里报一下规模，
                # 方便对照构建日志里的「单张图标：N」。
                FLAT_DIR="${'$'}MODPATH/${'$'}MEDIAPATH/theme/miui_mod_icons"
                if [ -d "${'$'}FLAT_DIR" ]; then
                  FLAT_COUNT=${'$'}(ls "${'$'}FLAT_DIR" 2>/dev/null | wc -l | tr -d ' ')
                  ui_print "- 单张图标：${'$'}FLAT_COUNT 个（${'$'}MEDIAPATH/theme/miui_mod_icons）"
                fi

                set_perm_recursive "${'$'}MODPATH" 0 0 0755 0644
                rm -rf /data/system/package_cache/* 2>/dev/null
                ui_print "- 安装完成，请重启设备。"
            """.trimIndent()
        }

        val UPDATER_SCRIPT = "#MAGISK\n"

        val UPDATE_BINARY = """
            #!/sbin/sh
            umask 022

            OUTFD=${'$'}2
            ZIPFILE=${'$'}3

            ui_print() { echo "${'$'}1"; }

            require_new_magisk() {
              ui_print "*******************************"
              ui_print " Please install Magisk v20.4+! "
              ui_print "*******************************"
              exit 1
            }

            mount /data 2>/dev/null

            [ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
            . /data/adb/magisk/util_functions.sh
            [ "${'$'}MAGISK_VER_CODE" -lt 20400 ] && require_new_magisk
            install_module
            exit 0
        """.trimIndent()
    }
}
