package io.github.perfecticons.domain.log

import io.github.perfecticons.core.log.LogLine
import io.github.perfecticons.core.log.LogTone

/**
 * Collects build progress and forwards each line as soon as it is produced.
 *
 * A theme build copies thousands of files and takes seconds, so buffering until
 * the end would make the UI look frozen. [onLine] is invoked synchronously on the
 * calling thread; the ViewModel marshals it onto its own state.
 */
class BuildLog(
    private val onLine: (LogLine) -> Unit = {},
) {
    private val sink = mutableListOf<LogLine>()

    fun step(text: String) = add("==> $text", LogTone.Step)
    fun info(text: String) = add("    $text", LogTone.Default)
    fun warn(text: String) = add("  ! $text", LogTone.Warning)
    fun error(text: String) = add("  x $text", LogTone.Error)
    fun done(text: String) = add("  ✓ $text", LogTone.Success)
    fun dim(text: String) = add("    $text", LogTone.Dim)

    fun asText(): String = sink.joinToString("\n") { it.text }

    private fun add(text: String, tone: LogTone) {
        val line = LogLine(text, tone)
        sink += line
        onLine(line)
    }
}
