package com.king250.sougou.patch

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

object InstalledEmojiGroupCounts {
    fun read(context: Context): IntArray = read(
        File(context.filesDir, "sogou/emojipackage"),
    )

    internal fun read(packageRoot: File): IntArray {
        val counts = IntArray(EmojiCatalog.groups.size) { MISSING_GROUP }
        if (!packageRoot.isDirectory) return counts

        val groupIndexes = EmojiCatalog.groups
            .mapIndexed { index, group -> group.packageId to index }
            .toMap()
        packageRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.forEach { directory ->
                val packageId = PACKAGE_DIRECTORY.matchEntire(directory.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?: return@forEach
                val index = groupIndexes[packageId] ?: return@forEach
                val entryCount = countEntries(File(directory, "info.xml"))
                if (entryCount >= 0) {
                    counts[index] = counts[index].coerceAtLeast(0) + entryCount
                }
            }
        return counts
    }

    internal fun countEntries(infoFile: File): Int {
        if (!infoFile.isFile) return MISSING_GROUP
        return runCatching {
            EXP_TAG.findAll(infoFile.readText(StandardCharsets.UTF_8)).count()
        }.getOrDefault(MISSING_GROUP)
    }

    const val MISSING_GROUP = -1

    private val PACKAGE_DIRECTORY = Regex("^(\\d+)_(-?\\d+)$")
    private val EXP_TAG = Regex("<exp(?=\\s|/?>)")
}
