package io.github.perfecticons.core.log

/** Semantic severity of one log line; drives colour, not control flow. */
enum class LogTone {
    Default,
    Step,
    Success,
    Warning,
    Error,
    Dim,
}

/**
 * One already-formatted log line.
 *
 * The producing layer owns the prefix (`==>`, indentation, markers) so the UI
 * only has to pick a colour. Keeping this type free of Compose lets domain code
 * emit lines while a build is still running.
 */
data class LogLine(
    val text: String,
    val tone: LogTone,
)
