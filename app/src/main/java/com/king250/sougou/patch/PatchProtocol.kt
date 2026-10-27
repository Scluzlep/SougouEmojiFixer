package com.king250.sougou.patch

object PatchProtocol {
    const val MODULE_PACKAGE = "com.scluzlep.misougoemoji"
    const val TARGET_PACKAGE = "com.sohu.inputmethod.sogou.xiaomi"

    const val ACTION_COMMAND = "$MODULE_PACKAGE.action.EMOJI_PATCH_COMMAND"
    const val ACTION_STATUS = "$MODULE_PACKAGE.action.EMOJI_PATCH_STATUS"
    const val ACTION_LOG = "$MODULE_PACKAGE.action.EMOJI_PATCH_LOG"

    const val EXTRA_ARCHIVE_URI = "noto_archive_uri"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_PHASE = "phase"
    const val EXTRA_CURRENT = "current"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_LOG_LEVEL = "log_level"
    const val EXTRA_LOG_TIME = "log_time"
    const val EXTRA_UNICODE_FOLDER = "unicode_folder"
    const val EXTRA_EMOJI_VERSION = "emoji_version"
    const val EXTRA_INCLUDE_NON_FULLY_QUALIFIED = "include_non_fully_qualified"
    const val EXTRA_INCLUDE_UNSUPPORTED_SYSTEM_EMOJI = "include_unsupported_system_emoji"
    const val EXTRA_NOTO_SOURCE = "noto_source"
    const val EXTRA_GROUP_ORDER = "group_order"
    const val EXTRA_GROUP_ENTRY_COUNTS = "group_entry_counts"

    const val COMMAND_PING = "ping"
    const val COMMAND_INSTALL = "install"
    const val COMMAND_APPLY_ORDER = "apply_order"
}

enum class PatchPhase {
    IDLE,
    WAITING_FOR_HOOK,
    READY,
    FETCHING_MANIFEST,
    PREPARING,
    DOWNLOADING,
    INSTALLING,
    ORDERING,
    COMPLETE,
    ERROR,
}

data class PatchUiState(
    val targetInstalled: Boolean = false,
    val hookReady: Boolean = false,
    val phase: PatchPhase = PatchPhase.IDLE,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "正在检查环境…",
    val groupEntryCounts: Map<Int, Int> = emptyMap(),
    val currentOrder: List<Int>? = null,
) {
    val isRunning: Boolean
        get() = phase in setOf(
            PatchPhase.FETCHING_MANIFEST,
            PatchPhase.PREPARING,
            PatchPhase.DOWNLOADING,
            PatchPhase.INSTALLING,
            PatchPhase.ORDERING,
        )

    val canInstall: Boolean
        get() = targetInstalled && hookReady && !isRunning

    val progress: Float
        get() = if (total > 0) {
            (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
