package io.github.perfecticons.data.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Routes JGit HTTP traffic through a user-configured proxy.
 *
 * JGit resolves proxies through the JVM-wide [ProxySelector], so installing this
 * once at application start covers every transport without touching the
 * repository layer. Android keeps a single process per app, so the global scope is
 * effectively application scoped.
 */
object ProxyConfig {

    data class Settings(
        val host: String,
        val port: Int,
    ) {
        val isValid: Boolean
            get() = host.isNotBlank() && port in 1..65535
    }

    @Volatile
    var settings: Settings? = null
        private set

    private var installed = false

    fun apply(settings: Settings?) {
        this.settings = settings?.takeIf { it.isValid }
        if (!installed) {
            ProxySelector.setDefault(AppProxySelector())
            installed = true
        }
    }

    private class AppProxySelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val current = settings ?: return listOf(Proxy.NO_PROXY)
            return listOf(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(current.host, current.port),
                ),
            )
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
}
