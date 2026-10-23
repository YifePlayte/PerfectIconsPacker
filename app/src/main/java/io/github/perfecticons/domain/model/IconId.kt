package io.github.perfecticons.domain.model

/** Normalizes package name, class name and `package#class` icon identifiers. */
object IconId {

    /**
     * The launcher looks up icons by three rules, increasing in priority:
     *  1. package name
     *  2. activity class name, when it starts with the package name
     *  3. `package#class`, for classes outside the package namespace
     *
     * The returned value is the directory name actually written to the theme.
     */
    fun resolve(packageName: String, className: String?): String {
        if (className.isNullOrBlank()) return packageName
        return if (className.startsWith("$packageName.")) {
            className
        } else {
            "$packageName#$className"
        }
    }

    fun isValid(id: String): Boolean =
        id.isNotBlank() &&
            id != "." &&
            id != ".." &&
            !id.startsWith('.') &&
            id.none { it == '/' || it == '\\' || it == '\u0000' }

    fun packageOf(id: String): String = id.substringBefore('#')
}
