package io.github.perfecticons.data.net

import java.net.URI

/**
 * Resolves the repository URL actually used for a sync.
 *
 * Users may supply their own acceleration endpoint, for example a GitHub proxy.
 * Two forms are accepted:
 *
 *  - prefix:  `https://example.test/` becomes `https://example.test/https://github.com/...`
 *  - template: `https://example.test/fetch?url={url}` replaces `{url}` with the
 *    encoded-free repository URL
 *
 * No default acceleration service is built in. An empty value means direct access,
 * and the direct URL always remains the final fallback.
 */
object RepoEndpoint {

    fun normalizeBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    fun isUsableAccelerator(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (raw.any { it.isWhitespace() }) return false
        val probe = raw.replace("{url}", "https://example.com/repo.git")
        return isHttpUrl(probe)
    }

    fun resolve(baseUrl: String, accelerator: String?): String? {
        val raw = accelerator?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val base = normalizeBase(baseUrl)
        val resolved = if (raw.contains("{url}")) {
            raw.replace("{url}", base)
        } else {
            raw.trimEnd('/') + "/" + base
        }
        if (resolved == base) return null
        return resolved.takeIf(::isHttpUrl)
    }

    /** Accelerated endpoint first when configured, direct URL last. */
    fun candidates(baseUrl: String, accelerator: String?): List<String> {
        val base = normalizeBase(baseUrl)
        // Only well-formed HTTP(S) endpoints reach the network, even when the
        // stored settings were edited outside the app.
        return listOfNotNull(resolve(base, accelerator), base.takeIf(::isHttpUrl)).distinct()
    }

    fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme == "https" || uri.scheme == "http") &&
            !uri.host.isNullOrBlank() &&
            // Credentials in the URL would end up in prefs, git config and logs.
            uri.userInfo == null
    }.getOrDefault(false)
}
