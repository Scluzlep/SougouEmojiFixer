package com.king250.sougou.patch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class PatchController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PatchUiState())
    private var registered = false
    private var sessionId = UUID.randomUUID().toString()
    private var pingJob: Job? = null
    private val logStore = RunLogStore(appContext)
    private val mutableLogs = MutableStateFlow(logStore.read())

    val state: StateFlow<PatchUiState> = mutableState.asStateFlow()
    val logs: StateFlow<List<RunLogEntry>> = mutableLogs.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PatchProtocol.ACTION_LOG) {
                val entry = RunLogEntry(
                    timestamp = intent.getLongExtra(
                        PatchProtocol.EXTRA_LOG_TIME,
                        System.currentTimeMillis(),
                    ),
                    level = intent.getStringExtra(PatchProtocol.EXTRA_LOG_LEVEL) ?: "INFO",
                    message = intent.getStringExtra(PatchProtocol.EXTRA_MESSAGE).orEmpty(),
                )
                logStore.append(entry)
                mutableLogs.value = logStore.read()
                return
            }
            if (intent.action != PatchProtocol.ACTION_STATUS) return
            if (intent.getStringExtra(PatchProtocol.EXTRA_SESSION_ID) != sessionId) return

            val phase = intent.getStringExtra(PatchProtocol.EXTRA_PHASE)
                ?.let { runCatching { PatchPhase.valueOf(it) }.getOrNull() }
                ?: return
            val current = intent.getIntExtra(PatchProtocol.EXTRA_CURRENT, 0)
            val total = intent.getIntExtra(PatchProtocol.EXTRA_TOTAL, 0)
            val message = intent.getStringExtra(PatchProtocol.EXTRA_MESSAGE).orEmpty()
            val groupEntryCounts = intent
                .getIntArrayExtra(PatchProtocol.EXTRA_GROUP_ENTRY_COUNTS)
                ?.takeIf { it.size == EmojiCatalog.groups.size }
                ?.let { counts ->
                    EmojiCatalog.groups.mapIndexed { index, group ->
                        group.packageId to counts[index]
                    }.toMap()
                }
                ?: mutableState.value.groupEntryCounts
            val currentOrder = intent
                .getIntArrayExtra(PatchProtocol.EXTRA_GROUP_ORDER)
                ?.toList()
                ?.takeIf { it.size == ORDERABLE_IDS.size && it.toSet() == ORDERABLE_IDS }
                ?: mutableState.value.currentOrder

            mutableState.value = mutableState.value.copy(
                hookReady = true,
                phase = phase,
                current = current,
                total = total,
                message = message,
                groupEntryCounts = groupEntryCounts,
                currentOrder = currentOrder,
            )
            if (!mutableState.value.isRunning) {
                pingJob?.cancel()
                pingJob = null
            }
        }
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter(PatchProtocol.ACTION_STATUS)
            filter.addAction(PatchProtocol.ACTION_LOG)
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
        }

        val installed = isTargetInstalled()
        if (!installed) {
            mutableState.value = PatchUiState(
                targetInstalled = false,
                phase = PatchPhase.ERROR,
                message = "未安装搜狗输入法小米版",
            )
            return
        }

        if (mutableState.value.phase == PatchPhase.IDLE) {
            mutableState.value = PatchUiState(
                targetInstalled = true,
                phase = PatchPhase.WAITING_FOR_HOOK,
                message = "等待搜狗输入法中的 LSPosed Hook…",
            )
        } else if (!mutableState.value.isRunning) {
            mutableState.value = mutableState.value.copy(
                targetInstalled = true,
                hookReady = false,
                phase = PatchPhase.WAITING_FOR_HOOK,
                current = 0,
                total = 0,
                message = "正在刷新搜狗输入法中的 Hook 状态…",
            )
        }
        // Always request one fresh status when the app returns to the foreground.
        // The Sogou process may have restarted since the previous response, and
        // installed group counts are produced by the Hook inside that process.
        startPinging()
    }

    fun stop() {
        pingJob?.cancel()
        pingJob = null
        if (registered) {
            appContext.unregisterReceiver(receiver)
            registered = false
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    fun install(configuration: PatchConfiguration, archiveUri: Uri) {
        if (!mutableState.value.canInstall) return
        sessionId = UUID.randomUUID().toString()
        mutableState.value = mutableState.value.copy(
            phase = PatchPhase.FETCHING_MANIFEST,
            current = 0,
            total = 0,
            message = "已发送安装请求…",
        )
        sendCommand(PatchProtocol.COMMAND_INSTALL, configuration, archiveUri)
    }

    fun applyOrder(configuration: PatchConfiguration) {
        if (!mutableState.value.canInstall) return
        sessionId = UUID.randomUUID().toString()
        mutableState.value = mutableState.value.copy(
            phase = PatchPhase.ORDERING,
            current = 0,
            total = configuration.groupOrder.size,
            message = "已发送分组排序请求…",
        )
        sendCommand(PatchProtocol.COMMAND_APPLY_ORDER, configuration)
    }

    fun refreshOrder() {
        if (mutableState.value.targetInstalled) {
            sendCommand(PatchProtocol.COMMAND_PING)
        }
    }

    fun clearLogs() {
        logStore.clear()
        mutableLogs.value = emptyList()
    }

    private fun sendCommand(
        command: String,
        configuration: PatchConfiguration? = null,
        archiveUri: Uri? = null,
    ) {
        val intent = Intent(PatchProtocol.ACTION_COMMAND)
            .setPackage(PatchProtocol.TARGET_PACKAGE)
            .putExtra(PatchProtocol.EXTRA_COMMAND, command)
            .putExtra(PatchProtocol.EXTRA_SESSION_ID, sessionId)
        configuration?.putInto(intent)
        archiveUri?.let { uri ->
            intent.putExtra(PatchProtocol.EXTRA_ARCHIVE_URI, uri)
            intent.clipData = ClipData.newUri(appContext.contentResolver, "Noto ZIP", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.sendBroadcast(intent)
    }

    private fun startPinging() {
        pingJob?.cancel()
        pingJob = scope.launch {
            do {
                sendCommand(PatchProtocol.COMMAND_PING)
                delay(PING_INTERVAL_MILLIS.milliseconds)
            } while (registered && (!mutableState.value.hookReady || mutableState.value.isRunning))
        }
    }

    private fun isTargetInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                PatchProtocol.TARGET_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(PatchProtocol.TARGET_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val PING_INTERVAL_MILLIS = 1_000L
        val ORDERABLE_IDS = EmojiCatalog.orderGroups.map { it.packageId }.toSet()
    }
}
