package com.king250.sougou

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.king250.sougou.patch.EmojiCatalog
import com.king250.sougou.patch.EmojiInstaller
import com.king250.sougou.patch.EmojiGroupOrderInstaller
import com.king250.sougou.patch.InstalledEmojiGroupCounts
import com.king250.sougou.patch.PatchConfiguration
import com.king250.sougou.patch.PatchPhase
import com.king250.sougou.patch.PatchProtocol
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class HookEntry : XposedModule() {
    private var processName: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != PatchProtocol.TARGET_PACKAGE) return
        if (processName != PatchProtocol.TARGET_PACKAGE) return
        if (!param.isFirstPackage) return

        val logger: (String, Throwable?) -> Unit = { message, error ->
            if (error == null) {
                log(Log.INFO, TAG, message)
            } else {
                log(Log.ERROR, TAG, message, error)
            }
        }
        DualSkinPopupHook.install(this, param.classLoader, logger)

        val attach = Application::class.java.getDeclaredMethod(
            "attach",
            Context::class.java,
        )
        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // The base Context belongs to Sogou and is available even before
                // Application.applicationContext is initialized.
                val context = chain.getArg(0) as Context
                val result = chain.proceed()
                RuntimeBridge.attach(context, logger)
                result
            }
        log(Log.INFO, TAG, "Waiting for Sogou Application.attach")
    }

    private companion object {
        const val TAG = "SogouEmojiMvp"
    }
}

private object RuntimeBridge {
    private val attached = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operationJob: Job? = null
    private var lastPhase = PatchPhase.READY
    private var lastCurrent = 0
    private var lastTotal = 0
    private var lastMessage = "Hook 已连接，可以安装 Unicode Emoji"

    fun attach(
        context: Context,
        logger: (String, Throwable?) -> Unit,
    ) {
        if (!attached.compareAndSet(false, true)) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != PatchProtocol.ACTION_COMMAND) return
                val sessionId = intent.getStringExtra(PatchProtocol.EXTRA_SESSION_ID) ?: return
                when (intent.getStringExtra(PatchProtocol.EXTRA_COMMAND)) {
                    PatchProtocol.COMMAND_PING -> {
                        sendStatus(
                            context = receiverContext,
                            sessionId = sessionId,
                            phase = if (operationJob?.isActive == true) lastPhase else PatchPhase.READY,
                            current = lastCurrent,
                            total = lastTotal,
                            message = if (operationJob?.isActive == true) {
                                lastMessage
                            } else {
                                "Hook 已连接，可以安装 Unicode Emoji"
                            },
                        )
                    }

                    PatchProtocol.COMMAND_INSTALL -> {
                        if (operationJob?.isActive == true) {
                            sendStatus(
                                receiverContext,
                                sessionId,
                                lastPhase,
                                lastCurrent,
                                lastTotal,
                                lastMessage,
                            )
                            return
                        }

                        val configuration = PatchConfiguration.fromIntent(intent)
                        @Suppress("DEPRECATION")
                        val archiveUri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(PatchProtocol.EXTRA_ARCHIVE_URI, Uri::class.java)
                        } else {
                            intent.getParcelableExtra(PatchProtocol.EXTRA_ARCHIVE_URI)
                        }
                        val archiveFd = runCatching {
                            requireNotNull(archiveUri) { "没有提供 Noto ZIP URI" }
                            requireNotNull(receiverContext.contentResolver.openFileDescriptor(archiveUri, "r")) {
                                "无法读取 Noto ZIP"
                            }
                        }.getOrElse { error ->
                            sendStatus(receiverContext, sessionId, PatchPhase.ERROR, 0, 0,
                                "读取 ZIP 失败：${error.message}")
                            return
                        }
                        operationJob = scope.launch {
                            sendLog(
                                receiverContext,
                                "INFO",
                                "开始安装 Unicode Emoji ${configuration.emojiVersion}",
                            )
                            var loggedPhase: PatchPhase? = null
                            val result = runCatching {
                                archiveFd.use { fd ->
                                    EmojiInstaller(
                                        receiverContext.applicationContext,
                                        configuration,
                                    ).install(fd) { update ->
                                    lastPhase = update.phase
                                    lastCurrent = update.current
                                    lastTotal = update.total
                                    lastMessage = update.message
                                    sendStatus(
                                        receiverContext,
                                        sessionId,
                                        update.phase,
                                        update.current,
                                        update.total,
                                        update.message,
                                    )
                                    if (loggedPhase != update.phase) {
                                        loggedPhase = update.phase
                                        sendLog(receiverContext, "INFO", update.message)
                                    }
                                }
                                }
                            }
                            result.onFailure { error ->
                                logger("Install failed", error)
                                sendLog(
                                    receiverContext,
                                    "ERROR",
                                    error.message ?: error.javaClass.simpleName,
                                )
                                sendStatus(
                                    receiverContext,
                                    sessionId,
                                    PatchPhase.ERROR,
                                    lastCurrent,
                                    lastTotal,
                                    error.message ?: error.javaClass.simpleName,
                                )
                            }
                            if (result.isSuccess && lastPhase == PatchPhase.COMPLETE) {
                                logger("Install complete; syncing order", null)
                                runCatching {
                                    EmojiGroupOrderInstaller(
                                        receiverContext.applicationContext,
                                        configuration,
                                    ).apply()
                                }.onSuccess {
                                    sendLog(
                                        receiverContext,
                                        "INFO",
                                        "分组排序已同步",
                                    )
                                }.onFailure { error ->
                                    logger("Order sync after install failed", error)
                                    sendLog(
                                        receiverContext,
                                        "ERROR",
                                        "排序同步失败：${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                                delay(PROCESS_EXIT_DELAY_MILLIS.milliseconds)
                                Process.killProcess(Process.myPid())
                            }
                        }
                    }

                    PatchProtocol.COMMAND_APPLY_ORDER -> {
                        if (operationJob?.isActive == true) return
                        val configuration = PatchConfiguration.fromIntent(intent)
                        operationJob = scope.launch {
                            lastPhase = PatchPhase.ORDERING
                            lastMessage = "正在应用 Emoji 分组排序…"
                            sendStatus(
                                receiverContext,
                                sessionId,
                                lastPhase,
                                0,
                                configuration.groupOrder.size,
                                lastMessage,
                            )
                            sendLog(receiverContext, "INFO", lastMessage)
                            val result = runCatching {
                                EmojiGroupOrderInstaller(
                                    receiverContext.applicationContext,
                                    configuration,
                                ).apply()
                            }
                            result.onSuccess {
                                lastPhase = PatchPhase.COMPLETE
                                lastCurrent = configuration.groupOrder.size
                                lastTotal = configuration.groupOrder.size
                                lastMessage = "分组排序已应用；搜狗即将重载"
                                sendStatus(
                                    receiverContext,
                                    sessionId,
                                    lastPhase,
                                    lastCurrent,
                                    lastTotal,
                                    lastMessage,
                                )
                                sendLog(receiverContext, "INFO", lastMessage)
                                delay(PROCESS_EXIT_DELAY_MILLIS.milliseconds)
                                Process.killProcess(Process.myPid())
                            }.onFailure { error ->
                                lastPhase = PatchPhase.ERROR
                                lastMessage = error.message ?: error.javaClass.simpleName
                                sendStatus(
                                    receiverContext,
                                    sessionId,
                                    lastPhase,
                                    0,
                                    configuration.groupOrder.size,
                                    lastMessage,
                                )
                                sendLog(receiverContext, "ERROR", lastMessage)
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(PatchProtocol.ACTION_COMMAND)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        logger("Command receiver registered", null)
        sendLog(context, "INFO", "搜狗进程中的 Hook 已连接")
    }

    private fun sendStatus(
        context: Context,
        sessionId: String,
        phase: PatchPhase,
        current: Int,
        total: Int,
        message: String,
    ) {
        val intent = Intent(PatchProtocol.ACTION_STATUS)
            .setPackage(PatchProtocol.MODULE_PACKAGE)
            .putExtra(PatchProtocol.EXTRA_SESSION_ID, sessionId)
            .putExtra(PatchProtocol.EXTRA_PHASE, phase.name)
            .putExtra(PatchProtocol.EXTRA_CURRENT, current)
            .putExtra(PatchProtocol.EXTRA_TOTAL, total)
            .putExtra(PatchProtocol.EXTRA_MESSAGE, message)
        groupEntryCounts(context, phase)?.let { counts ->
            intent.putExtra(PatchProtocol.EXTRA_GROUP_ENTRY_COUNTS, counts)
        }
        currentOrder(context, phase)?.let { order ->
            intent.putExtra(PatchProtocol.EXTRA_GROUP_ORDER, order)
        }
        context.sendBroadcast(intent)
    }

    private fun groupEntryCounts(
        context: Context,
        phase: PatchPhase,
    ): IntArray? = when (phase) {
        PatchPhase.READY,
        PatchPhase.COMPLETE,
        PatchPhase.ERROR -> InstalledEmojiGroupCounts.read(context)

        else -> null
    }

    /**
     * Derives the live order from the actual package directories, so the app
     * reflects the real on-disk state rather than a cached one.
     */
    private fun currentOrder(
        context: Context,
        phase: PatchPhase,
    ): IntArray? = when (phase) {
        PatchPhase.READY,
        PatchPhase.COMPLETE,
        PatchPhase.ERROR -> readCurrentOrder(context)

        else -> null
    }

    private fun readCurrentOrder(context: Context): IntArray? {
        val packageRoot = File(context.filesDir, "sogou/emojipackage")
        if (!packageRoot.isDirectory) return null
        val orderableIds = EmojiCatalog.orderGroups.map { it.packageId }.toSet()
        val ids = packageRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.mapNotNull { directory ->
                val match = PACKAGE_DIRECTORY.matchEntire(directory.name)
                    ?: return@mapNotNull null
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val order = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (id !in orderableIds) return@mapNotNull null
                id to order
            }
            ?.sortedByDescending { it.second }
            ?.map { it.first }
            ?.toList()
            .orEmpty()
        if (ids.size != orderableIds.size) return null
        return ids.toIntArray()
    }

    private fun sendLog(
        context: Context,
        level: String,
        message: String,
    ) {
        context.sendBroadcast(
            Intent(PatchProtocol.ACTION_LOG)
                .setPackage(PatchProtocol.MODULE_PACKAGE)
                .putExtra(PatchProtocol.EXTRA_LOG_LEVEL, level)
                .putExtra(PatchProtocol.EXTRA_LOG_TIME, System.currentTimeMillis())
                .putExtra(PatchProtocol.EXTRA_MESSAGE, message),
        )
    }

    private const val PROCESS_EXIT_DELAY_MILLIS = 750L
    private val PACKAGE_DIRECTORY = Regex("^(\\d+)_(-?\\d+)$")
}
