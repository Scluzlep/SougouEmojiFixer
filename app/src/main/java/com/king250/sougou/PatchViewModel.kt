package com.king250.sougou

import android.app.Application
import android.net.Uri
import com.king250.sougou.patch.NotoArchiveCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.king250.sougou.patch.AppSettingsStore
import com.king250.sougou.patch.NotoDownloadSource
import com.king250.sougou.patch.PatchConfiguration
import com.king250.sougou.patch.PatchController
import com.king250.sougou.patch.PatchUiState
import com.king250.sougou.patch.RunLogEntry
import com.king250.sougou.patch.UnicodeVersionOption
import com.king250.sougou.patch.UnicodeVersionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PatchViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = PatchController(application)
    private val settingsStore = AppSettingsStore(application)
    private val versionRepository = UnicodeVersionRepository()
    private val mutableConfiguration = MutableStateFlow(PatchConfiguration())
    private val mutableArchiveMessage = MutableStateFlow(
        "自动下载官方 Noto ZIP 并复用缓存；也可导入本地 ZIP。"
    )
    private val mutableArchiveBusy = MutableStateFlow(false)
    val archiveMessage = mutableArchiveMessage.asStateFlow()
    val archiveBusy = mutableArchiveBusy.asStateFlow()
    private val mutableVersions = MutableStateFlow(
        listOf(
            UnicodeVersionOption(
                PatchConfiguration.DEFAULT_UNICODE_FOLDER,
                PatchConfiguration.DEFAULT_EMOJI_VERSION,
            ),
        ),
    )
    private val mutableVersionsLoading = MutableStateFlow(false)
    private val mutableVersionsMessage = MutableStateFlow("等待获取 Unicode 稳定版本…")
    private var versionsLoadedThisRun = false
    private var orderEditing = false

    val state: StateFlow<PatchUiState> = controller.state
    val logs: StateFlow<List<RunLogEntry>> = controller.logs
    val configuration = mutableConfiguration.asStateFlow()
    val versions = mutableVersions.asStateFlow()
    val versionsLoading = mutableVersionsLoading.asStateFlow()
    val versionsMessage = mutableVersionsMessage.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.configuration.collect { configuration ->
                mutableConfiguration.value = configuration
            }
        }
        viewModelScope.launch {
            settingsStore.cachedVersions()
                .takeIf { it.isNotEmpty() }
                ?.let { mutableVersions.value = it }
        }
        viewModelScope.launch {
            controller.state
                .map { it.currentOrder }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { order ->
                    if (!orderEditing &&
                        !controller.state.value.isRunning &&
                        mutableConfiguration.value.groupOrder != order
                    ) {
                        mutableConfiguration.value =
                            mutableConfiguration.value.copy(groupOrder = order)
                    }
                }
        }
    }

    fun start() {
        controller.start()
        if (!versionsLoadedThisRun) refreshVersions()
    }

    fun stop() = controller.stop()

    fun install() {
        if (mutableArchiveBusy.value || !controller.state.value.canInstall) return
        viewModelScope.launch {
            mutableArchiveBusy.value = true
            try {
                val context = getApplication<Application>()
                val uri = NotoArchiveCache.cached(context) ?:
                    NotoArchiveCache.download(context) { mutableArchiveMessage.value = it }
                controller.install(mutableConfiguration.value, uri)
            } catch (error: Exception) {
                mutableArchiveMessage.value = "ZIP 获取失败：${error.message}（可选择本地导入）"
            } finally {
                mutableArchiveBusy.value = false
            }
        }
    }

    fun importArchive(uri: Uri) {
        if (mutableArchiveBusy.value || controller.state.value.isRunning) return
        viewModelScope.launch {
            mutableArchiveBusy.value = true
            try {
                val context = getApplication<Application>()
                NotoArchiveCache.importZip(context, uri) { mutableArchiveMessage.value = it }
            } catch (error: Exception) {
                mutableArchiveMessage.value = "导入失败：${error.message}"
            } finally {
                mutableArchiveBusy.value = false
            }
        }
    }

    fun applyOrder(order: List<Int>) {
        updateConfiguration { copy(groupOrder = order) }
        controller.applyOrder(mutableConfiguration.value)
    }

    fun refreshOrder() = controller.refreshOrder()

    fun setOrderEditing(editing: Boolean) {
        orderEditing = editing
    }

    fun refreshVersions() {
        if (mutableVersionsLoading.value) return
        versionsLoadedThisRun = true
        viewModelScope.launch {
            mutableVersionsLoading.value = true
            mutableVersionsMessage.value = "正在读取 Unicode 官方目录…"
            val result = runCatching { versionRepository.fetchStableVersions() }
            val discovered = result.getOrNull()
            if (discovered != null && discovered.isNotEmpty()) {
                mutableVersions.value = discovered
                settingsStore.cacheVersions(discovered)
                if (!settingsStore.hasExplicitVersion()) {
                    selectVersion(discovered.first(), explicit = false)
                }
                mutableVersionsMessage.value =
                    "已获取 ${discovered.size} 个稳定版本；默认使用最新版本"
            } else {
                mutableVersionsMessage.value =
                    "版本目录获取失败，使用缓存：${result.exceptionOrNull()?.message ?: "网络错误"}"
            }
            mutableVersionsLoading.value = false
        }
    }

    fun selectVersion(option: UnicodeVersionOption) = selectVersion(option, explicit = true)

    private fun selectVersion(option: UnicodeVersionOption, explicit: Boolean) {
        updateConfiguration {
            copy(
                unicodeFolder = option.unicodeFolder,
                emojiVersion = option.emojiVersion,
            )
        }
        viewModelScope.launch {
            settingsStore.markVersionSelected(explicit)
        }
    }

    fun setIncludeNonFullyQualified(value: Boolean) = updateConfiguration {
        copy(includeNonFullyQualified = value)
    }

    fun setIncludeUnsupportedSystemEmoji(value: Boolean) = updateConfiguration {
        copy(includeUnsupportedSystemEmoji = value)
    }

    fun setNotoDownloadSource(value: NotoDownloadSource) = updateConfiguration {
        copy(notoDownloadSource = value)
    }

    fun moveGroup(fromIndex: Int, toIndex: Int) {
        val order = mutableConfiguration.value.groupOrder.toMutableList()
        if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) {
            return
        }
        val moved = order.removeAt(fromIndex)
        order.add(toIndex, moved)
        updateConfiguration { copy(groupOrder = order) }
    }

    fun resetGroupOrder() = updateConfiguration {
        copy(groupOrder = com.king250.sougou.patch.EmojiCatalog.orderGroups.map { it.packageId })
    }

    fun clearLogs() = controller.clearLogs()

    private fun updateConfiguration(transform: PatchConfiguration.() -> PatchConfiguration) {
        val updated = mutableConfiguration.value.transform()
        mutableConfiguration.value = updated
        viewModelScope.launch {
            settingsStore.save(updated)
        }
    }

    override fun onCleared() {
        controller.close()
    }
}
