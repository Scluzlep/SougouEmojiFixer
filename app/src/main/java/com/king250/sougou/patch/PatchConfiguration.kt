package com.king250.sougou.patch

import android.content.Intent

enum class NotoDownloadSource(
    val displayName: String,
) {
    GITHUB("GitHub 源站"),
    JSDELIVR("jsDelivr 镜像"),
}

data class UnicodeVersionOption(
    val unicodeFolder: String,
    val emojiVersion: String,
) {
    val displayName: String
        get() = "Emoji $emojiVersion"
}

data class PatchConfiguration(
    val unicodeFolder: String = DEFAULT_UNICODE_FOLDER,
    val emojiVersion: String = DEFAULT_EMOJI_VERSION,
    val includeNonFullyQualified: Boolean = false,
    val includeUnsupportedSystemEmoji: Boolean = false,
    val notoDownloadSource: NotoDownloadSource = NotoDownloadSource.GITHUB,
    val groupOrder: List<Int> = EmojiCatalog.orderGroups.map(EmojiGroup::packageId),
) {
    init {
        require(UNICODE_FOLDER_PATTERN.matches(unicodeFolder)) {
            "Invalid Unicode folder: $unicodeFolder"
        }
        require(EMOJI_VERSION_PATTERN.matches(emojiVersion)) {
            "Invalid Emoji version: $emojiVersion"
        }
        require(groupOrder.toSet() == VALID_GROUP_IDS) {
            "Group order must contain every Unicode group exactly once"
        }
    }

    fun putInto(intent: Intent): Intent = intent
        .putExtra(PatchProtocol.EXTRA_UNICODE_FOLDER, unicodeFolder)
        .putExtra(PatchProtocol.EXTRA_EMOJI_VERSION, emojiVersion)
        .putExtra(
            PatchProtocol.EXTRA_INCLUDE_NON_FULLY_QUALIFIED,
            includeNonFullyQualified,
        )
        .putExtra(
            PatchProtocol.EXTRA_INCLUDE_UNSUPPORTED_SYSTEM_EMOJI,
            includeUnsupportedSystemEmoji,
        )
        .putExtra(PatchProtocol.EXTRA_NOTO_SOURCE, notoDownloadSource.name)
        .putExtra(PatchProtocol.EXTRA_GROUP_ORDER, groupOrder.toIntArray())

    fun orderValue(packageId: Int): Long {
        val index = groupOrder.indexOf(packageId)
        require(index >= 0) { "Unknown group $packageId" }
        return (groupOrder.size - index) * ORDER_STEP
    }

    companion object {
        const val DEFAULT_UNICODE_FOLDER = "17.0.0"
        const val DEFAULT_EMOJI_VERSION = "17.0"
        private const val ORDER_STEP = 1_000L
        private val UNICODE_FOLDER_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+$")
        private val EMOJI_VERSION_PATTERN = Regex("^\\d+\\.\\d+$")
        private val VALID_GROUP_IDS = EmojiCatalog.orderGroups
            .map(EmojiGroup::packageId)
            .toSet()

        fun fromIntent(intent: Intent): PatchConfiguration {
            val defaults = PatchConfiguration()
            val folder = intent.getStringExtra(PatchProtocol.EXTRA_UNICODE_FOLDER)
                ?.takeIf(UNICODE_FOLDER_PATTERN::matches)
                ?: defaults.unicodeFolder
            val version = intent.getStringExtra(PatchProtocol.EXTRA_EMOJI_VERSION)
                ?.takeIf(EMOJI_VERSION_PATTERN::matches)
                ?: defaults.emojiVersion
            val source = intent.getStringExtra(PatchProtocol.EXTRA_NOTO_SOURCE)
                ?.let { value ->
                    NotoDownloadSource.entries.firstOrNull { it.name == value }
                }
                ?: defaults.notoDownloadSource
            val order = intent.getIntArrayExtra(PatchProtocol.EXTRA_GROUP_ORDER)
                ?.toList()
                ?.takeIf { it.toSet() == VALID_GROUP_IDS && it.size == VALID_GROUP_IDS.size }
                ?: defaults.groupOrder

            return PatchConfiguration(
                unicodeFolder = folder,
                emojiVersion = version,
                includeNonFullyQualified = intent.getBooleanExtra(
                    PatchProtocol.EXTRA_INCLUDE_NON_FULLY_QUALIFIED,
                    defaults.includeNonFullyQualified,
                ),
                includeUnsupportedSystemEmoji = intent.getBooleanExtra(
                    PatchProtocol.EXTRA_INCLUDE_UNSUPPORTED_SYSTEM_EMOJI,
                    defaults.includeUnsupportedSystemEmoji,
                ),
                notoDownloadSource = source,
                groupOrder = order,
            )
        }
    }
}
