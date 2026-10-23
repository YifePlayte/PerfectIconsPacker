package io.github.perfecticons.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.perfecticons.data.net.ProxyConfig
import io.github.perfecticons.data.net.RepoEndpoint
import io.github.perfecticons.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RepoSettings(
    val repoUrl: String,
    val accelerator: String,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _proxy = MutableStateFlow(repository.proxy)
    val proxy: StateFlow<ProxyConfig.Settings?> = _proxy.asStateFlow()

    private val _repo = MutableStateFlow(
        RepoSettings(repository.repoUrl, repository.accelerator),
    )
    val repo: StateFlow<RepoSettings> = _repo.asStateFlow()

    fun updateProxy(host: String, portText: String) {
        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) return
        val settings = ProxyConfig.Settings(host.trim(), port)
        repository.proxy = settings
        _proxy.value = settings
    }

    fun clearProxy() {
        repository.proxy = null
        _proxy.value = null
    }

    /**
     * Saves the repository endpoint configuration. The accelerator is optional and
     * must be an http(s) prefix or a template containing `{url}`.
     */
    fun updateRepo(repoUrl: String, accelerator: String): Boolean {
        val base = repoUrl.trim()
        if (!RepoEndpoint.isHttpUrl(base)) return false
        val trimmed = accelerator.trim()
        if (trimmed.isNotEmpty() && !RepoEndpoint.isUsableAccelerator(trimmed)) return false
        repository.repoUrl = base
        repository.accelerator = trimmed
        _repo.value = RepoSettings(repository.repoUrl, repository.accelerator)
        return true
    }

    fun resetRepo() {
        repository.repoUrl = SettingsRepository.DEFAULT_REPO
        repository.accelerator = ""
        _repo.value = RepoSettings(repository.repoUrl, repository.accelerator)
    }
}
