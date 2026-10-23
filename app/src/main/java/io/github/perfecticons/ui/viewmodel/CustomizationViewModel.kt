package io.github.perfecticons.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.perfecticons.R
import io.github.perfecticons.data.apps.InstalledApp
import io.github.perfecticons.data.apps.InstalledApps
import io.github.perfecticons.data.custom.AddonEntry
import io.github.perfecticons.data.custom.CustomizationStore
import io.github.perfecticons.data.custom.DynamicEntry
import io.github.perfecticons.data.custom.StaticEntry
import io.github.perfecticons.data.repo.IconRepository
import io.github.perfecticons.data.repo.RepoPaths
import io.github.perfecticons.domain.importer.DynamicIconImporter
import io.github.perfecticons.domain.importer.ImportSummary
import io.github.perfecticons.domain.importer.StaticIconImporter
import io.github.perfecticons.domain.validation.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CustomizationUiState(
    val staticIcons: List<StaticEntry> = emptyList(),
    val dynamicIcons: List<DynamicEntry> = emptyList(),
    val addons: List<AddonEntry> = emptyList(),
    val transformConfig: String? = null,
    val disabledStatic: Set<String> = emptySet(),
    val disabledDynamic: Set<String> = emptySet(),
    val availableStatic: List<String> = emptyList(),
    val availableDynamic: List<String> = emptyList(),
    val apps: List<InstalledApp> = emptyList(),
    val isLoading: Boolean = false,
    /** True while a user-initiated pull-to-refresh runs; drives the indicator. */
    val isRefreshing: Boolean = false,
    val message: String? = null,
    val lastImport: ImportSummary? = null,
)

class CustomizationViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CustomizationStore(RepoPaths.customRoot(application))
    private val repository = IconRepository(RepoPaths.root(application))
    private val workDir = File(application.cacheDir, "imports")

    private val _state = MutableStateFlow(CustomizationUiState())
    val state: StateFlow<CustomizationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.ensureLayout() }
            refresh()
        }
    }

    /**
     * Rescans the customization tree. The directory is the single source of
     * truth, so this is also the way to pick up edits made outside the app
     * through SAF.
     *
     * @param fromPull true when triggered by pull-to-refresh: keeps the
     *   indicator visible until the scan finishes instead of showing the
     *   inline loading state.
     */
    fun refresh(fromPull: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = !fromPull && _state.value.staticIcons.isEmpty(),
                isRefreshing = fromPull,
            )
            val loaded = withContext(Dispatchers.IO) {
                val static = store.staticIcons()
                val dynamic = store.dynamicIcons()
                val addons = store.addonFiles()
                Loaded(
                    static = static,
                    dynamic = dynamic,
                    addons = addons,
                    transform = store.readTransformConfig(),
                    disabledStatic = store.disabledStaticIds(),
                    disabledDynamic = store.disabledDynamicIds(),
                    availableStatic = availableStaticIds(),
                    availableDynamic = availableDynamicIds(),
                )
            }
            _state.value = _state.value.copy(
                staticIcons = loaded.static,
                dynamicIcons = loaded.dynamic,
                addons = loaded.addons,
                transformConfig = loaded.transform,
                disabledStatic = loaded.disabledStatic,
                disabledDynamic = loaded.disabledDynamic,
                availableStatic = loaded.availableStatic,
                availableDynamic = loaded.availableDynamic,
                isLoading = false,
                isRefreshing = false,
            )
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { InstalledApps(getApplication()).load() }
            _state.value = _state.value.copy(apps = apps)
        }
    }

    /** Dispatches the add flow's confirm: static placeholders or dynamic placeholders. */
    fun createEntries(ids: List<String>, dynamic: Boolean) {
        if (dynamic) createDynamic(ids) else saveStatic(ids, null, null)
    }

    /** Saves the picked layers under every selected id; omitted layers stay untouched. */
    fun saveStatic(
        ids: List<String>,
        background: ByteArray?,
        foreground: ByteArray?,
    ) {
        if (ids.isEmpty()) return
        viewModelScope.launch { saveStaticInternal(ids, background, foreground) }
    }

    /** Reads the picked PNG off the main thread and saves it as one layer. */
    fun saveStaticLayer(ids: List<String>, uri: Uri, background: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { readUri(uri) }
            if (bytes == null) {
                setMessage(getApplication<Application>().getString(R.string.common_save_failed))
                return@launch
            }
            saveStaticInternal(
                ids,
                if (background) bytes else null,
                if (background) null else bytes,
            )
        }
    }

    private suspend fun saveStaticInternal(
        ids: List<String>,
        background: ByteArray?,
        foreground: ByteArray?,
    ) {
        val failures = withContext(Dispatchers.IO) {
            ids.mapNotNull { id ->
                store.saveStatic(id, background, foreground)
                    .exceptionOrNull()
                    ?.let { error -> "$id: ${error.message ?: "保存失败"}" }
            }
        }
        if (failures.isEmpty()) {
            setMessage(
                getApplication<Application>()
                    .getString(R.string.custom_saved, ids.joinToString(", ")),
            )
        } else {
            setMessage(failures.joinToString("；"))
        }
        refresh()
    }

    /** Clears one layer of an entry; the entry itself stays. */
    fun clearStaticLayer(id: String, layer: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearStaticLayer(id, layer) }
            refresh()
        }
    }

    fun deleteStatic(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteStatic(id) }
            setMessage("已删除 $id")
            refresh()
        }
    }

    fun deleteDynamic(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteDynamic(id) }
            setMessage("已删除 $id")
            refresh()
        }
    }

    /** Copies the picked archive off the main thread, then imports it. */
    fun importStaticZip(uri: Uri) {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val zip = copyToCache(uri) ?: return@withContext null
                runCatching { StaticIconImporter(store, workDir).importZip(zip) }
                    .also { zip.delete() }
                    .getOrNull()
            } ?: return@launch
            _state.value = _state.value.copy(lastImport = summary)
            setMessage("静态导入：新增 ${summary.added}，覆盖 ${summary.replaced}，跳过 ${summary.skipped}")
            refresh()
        }
    }

    /** Creates placeholder entries for the given ids; icons are imported per row later. */
    fun createDynamic(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failures = withContext(Dispatchers.IO) {
                ids.mapNotNull { id ->
                    store.createDynamicEntry(id)
                        .exceptionOrNull()
                        ?.let { error -> "$id: ${error.message ?: "保存失败"}" }
                }
            }
            if (failures.isEmpty()) {
                setMessage(
                    getApplication<Application>()
                        .getString(R.string.custom_saved, ids.joinToString(", ")),
                )
            } else {
                setMessage(failures.joinToString("；"))
            }
            refresh()
        }
    }

    fun importDynamicZip(uri: Uri, overwrite: Boolean) {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val zip = copyToCache(uri) ?: return@withContext null
                runCatching { DynamicIconImporter(store, workDir).importZip(zip, overwrite) }
                    .also { zip.delete() }
                    .getOrNull()
            } ?: return@launch
            _state.value = _state.value.copy(lastImport = summary)
            setMessage("动态导入：新增 ${summary.added}，覆盖 ${summary.replaced}，跳过 ${summary.skipped}")
            refresh()
        }
    }

    fun importDynamicSingle(uri: Uri, id: String, overwrite: Boolean) {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val zip = copyToCache(uri) ?: return@withContext null
                runCatching { DynamicIconImporter(store, workDir).importSingle(zip, id, overwrite) }
                    .also { zip.delete() }
                    .getOrNull()
            } ?: return@launch
            _state.value = _state.value.copy(lastImport = summary)
            val item = summary.items.firstOrNull()
            setMessage(
                when (val status = item?.status) {
                    is io.github.perfecticons.domain.importer.ImportStatus.Skipped -> "跳过：${status.reason}"
                    else -> "已导入 $id"
                },
            )
            refresh()
        }
    }

    fun importAddon(uri: Uri, relativePath: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val source = copyToCache(uri, name = relativePath.substringAfterLast('/'))
                    ?: return@withContext Result.failure(IllegalStateException("读取文件失败"))
                runCatching { store.importAddon(source, relativePath) }.also { source.delete() }
            }
            result.fold(
                onSuccess = { setMessage("已添加 $relativePath"); refresh() },
                onFailure = { setMessage(it.message ?: "添加失败") },
            )
        }
    }

    fun deleteAddon(relativePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteAddon(relativePath) }
            setMessage("已删除 $relativePath")
            refresh()
        }
    }

    fun saveTransformConfig(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bytes = readUri(uri)
                    ?: return@withContext Result.failure(IllegalStateException("读取文件失败"))
                store.saveTransformConfig(bytes)
            }
            result.fold(
                onSuccess = { setMessage("已更新图标遮罩"); refresh() },
                onFailure = { setMessage(it.message ?: "遮罩无效") },
            )
        }
    }

    fun resetTransformConfig() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteTransformConfig() }
            setMessage("已恢复默认 HyperOS 4 遮罩")
            refresh()
        }
    }

    fun setStaticDisabled(id: String, disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setStaticDisabled(id, disabled) }
            refresh()
        }
    }

    fun setDynamicDisabled(id: String, disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setDynamicDisabled(id, disabled) }
            refresh()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun setMessage(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    /** Reads a picked document; call from Dispatchers.IO. */
    private fun readUri(uri: Uri): ByteArray? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** Copies a picked document into the import cache; call from Dispatchers.IO. */
    private fun copyToCache(
        uri: Uri,
        name: String = "import-${System.currentTimeMillis()}.zip",
    ): File? {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "import.bin" }
        val file = File(getApplication<Application>().cacheDir, safe)
        return runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file
        }.getOrNull()
    }

    private fun availableStaticIds(): List<String> {
        val snapshot = repository.status().snapshot ?: return emptyList()
        val ids = sortedSetOf<String>()
        File(repository.snapshotDir, "icons").listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { ids += it.name }
        snapshot.styles.forEach { style ->
            File(repository.snapshotDir, "themes/$style/icons").listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { ids += it.name }
        }
        return ids.toList()
    }

    private fun availableDynamicIds(): List<String> {
        val snapshot = repository.status().snapshot ?: return emptyList()
        val ids = sortedSetOf<String>()
        val roots = buildList {
            snapshot.styles.forEach { add("themes/$it/layer_animating_icons") }
            add("themes/common/layer_animating_icons")
        }
        roots.forEach { relative ->
            File(repository.snapshotDir, relative).listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { ids += it.name }
        }
        return ids.toList()
    }

    private data class Loaded(
        val static: List<StaticEntry>,
        val dynamic: List<DynamicEntry>,
        val addons: List<AddonEntry>,
        val transform: String?,
        val disabledStatic: Set<String>,
        val disabledDynamic: Set<String>,
        val availableStatic: List<String>,
        val availableDynamic: List<String>,
    )
}
