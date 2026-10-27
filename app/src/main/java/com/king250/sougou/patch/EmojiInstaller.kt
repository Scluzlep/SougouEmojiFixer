package com.king250.sougou.patch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import android.os.ParcelFileDescriptor
import java.util.zip.ZipInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.createBitmap

data class PatchUpdate(
    val phase: PatchPhase,
    val current: Int,
    val total: Int,
    val message: String,
)

class EmojiInstaller(
    private val context: Context,
    private val configuration: PatchConfiguration,
) {
    private val client = HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 45_000
        }
    }

    suspend fun install(archive: ParcelFileDescriptor, onProgress: (PatchUpdate) -> Unit) {
        try {
            onProgress(
                PatchUpdate(
                    PatchPhase.FETCHING_MANIFEST,
                    0,
                    0,
                    "正在下载 Unicode Emoji ${configuration.emojiVersion} 数据…",
                ),
            )
            val emojiTest = downloadText(
                EmojiCatalog.emojiTestUrl(configuration.unicodeFolder),
            )
            val manifestVersion = EmojiCatalog.emojiVersion(emojiTest)
            require(manifestVersion == configuration.emojiVersion) {
                "所选目录返回 Emoji $manifestVersion，预期 ${configuration.emojiVersion}"
            }
            val sourceEntries = EmojiCatalog.parseEmojiTest(
                text = emojiTest,
                includeNonFullyQualified = configuration.includeNonFullyQualified,
                notoDownloadSource = configuration.notoDownloadSource,
            )
            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT
                textSize = IMAGE_SIZE.toFloat()
            }
            val catalog = EmojiCatalog.prepareForSystem(
                sourceEntries = sourceEntries,
                hasGlyph = if (configuration.includeUnsupportedSystemEmoji) {
                    { true }
                } else {
                    glyphPaint::hasGlyph
                },
            )
            val entries = catalog.entries
            require(entries.isNotEmpty()) { "当前系统没有可安装的 Unicode Emoji" }

            onProgress(
                PatchUpdate(
                    PatchPhase.PREPARING,
                    0,
                    catalog.imageCount,
                    "系统支持 ${catalog.supportedSequenceCount} / " +
                        "${catalog.sourceSequenceCount} 个序列，" +
                        "合并 ${catalog.mergedSkinSets} 组肤色，" +
                        "其中 ${catalog.mergedDualSkinMatrices} 个双肤色矩阵，" +
                        "跳过 ${catalog.skippedSequenceCount} 个不可用序列…",
                ),
            )

            val layout = prepareStaging(entries)
            installImagesFromZip(entries, layout, archive, onProgress)
            writePackageMetadata(entries, layout)
            validateStaging(entries, layout)

            onProgress(
                PatchUpdate(
                    PatchPhase.INSTALLING,
                    catalog.imageCount,
                    catalog.imageCount,
                    "下载完成，正在替换搜狗 Emoji 分组…",
                ),
            )
            commit(layout, catalog)

            onProgress(
                PatchUpdate(
                    PatchPhase.COMPLETE,
                    catalog.imageCount,
                    catalog.imageCount,
                        "Unicode Emoji ${configuration.emojiVersion} 已安装：" +
                        "${entries.size} 个入口，" +
                        "${catalog.mergedSkinSets} 组肤色，" +
                        "${catalog.mergedDualSkinMatrices} 个双肤色矩阵；搜狗即将重载",
                ),
            )
        } finally {
            client.close()
        }
    }

    private suspend fun downloadText(url: String): String = retrying(url) {
        client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.body<String>()
    }

    private suspend fun installImagesFromZip(
        entries: List<EmojiEntry>,
        layout: InstallLayout,
        archive: ParcelFileDescriptor,
        onProgress: (PatchUpdate) -> Unit,
    ) {
        val needed = HashMap<String, MutableList<Pair<Int, String>>>()
        fun add(packageId: Int, fileName: String, source: String) {
            val path = when {
                source.contains("/third_party/region-flags/png/") ->
                    "third_party/region-flags/png/" +
                        source.substringAfter("/third_party/region-flags/png/")
                source.contains("/png/128/") ->
                    "png/128/" + source.substringAfter("/png/128/")
                else -> error("未知 Noto 图片路径: $source")
            }
            needed.getOrPut(path) { mutableListOf() }.add(packageId to fileName)
        }
        entries.forEach { item ->
            add(item.group.packageId, "${item.imageId}.png", item.sourceUrl)
            item.skinVariants.forEach { variant ->
                add(item.group.packageId,
                    "${item.imageId}_${variant.suffix}.png", variant.sourceUrl)
            }
        }
        val total = needed.values.sumOf { it.size }
        var done = 0
        ZipInputStream(FileInputStream(archive.fileDescriptor).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                val key = when {
                    name.startsWith("png/128/") -> name
                    name.contains("/png/128/") -> "png/128/" + name.substringAfter("/png/128/")
                    name.startsWith("third_party/region-flags/png/") -> name
                    name.contains("/third_party/region-flags/png/") ->
                        "third_party/region-flags/png/" +
                        name.substringAfter("/third_party/region-flags/png/")
                    else -> null
                }
                val targets = if (key == null) null else needed.remove(key)
                if (targets != null) {
                    val bytes = readZipPng(zip)
                    targets.forEach { (packageId, fileName) ->
                        val output = File(
                            layout.packageDirectories.getValue(packageId),
                            "emoji/$fileName",
                        )
                        writeNormalizedPng(bytes, output)
                        done++
                    }
                    if (done % PROGRESS_STEP == 0 || done == total) {
                        onProgress(PatchUpdate(PatchPhase.DOWNLOADING, done, total,
                            "正在处理 ZIP 内的 Noto Emoji：$done / $total"))
                    }
                }
                zip.closeEntry()
            }
        }
        require(done == total) {
            val missing = needed.keys.take(5).joinToString()
            "ZIP 缺少 ${total - done} 张图片，请检查素材版本。示例：$missing"
        }
    }

    private fun readZipPng(zip: ZipInputStream): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            require(result.size() + count <= 2 * 1024 * 1024) {
                "ZIP 中图片超过 2 MiB，已拒绝读取"
            }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private suspend fun downloadBytes(url: String): ByteArray = retrying(url) {
        client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.body<ByteArray>()
    }

    private suspend fun <T> retrying(url: String, block: suspend () -> T): T {
        var lastFailure: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (attempt + 1 < DOWNLOAD_ATTEMPTS) {
                    delay((500L * (attempt + 1)).milliseconds)
                }
            }
        }
        val suffix = if (lastFailure is HttpRequestTimeoutException) " (timeout)" else ""
        throw IllegalStateException("Download failed$suffix: $url", lastFailure)
    }

    private fun prepareStaging(entries: List<EmojiEntry>): InstallLayout {
        val sogouRoot = File(context.filesDir, "sogou")
        val packageRoot = File(sogouRoot, "emojipackage")
        require(packageRoot.isDirectory) {
            "搜狗 Emoji 目录不存在，请先在输入法中打开一次 Emoji 面板"
        }

        val stagingRoot = File(sogouRoot, ".unicode-staging")
        val backupRoot = File(sogouRoot, ".unicode-backup")
        deleteManagedDirectory(stagingRoot, sogouRoot)
        require(stagingRoot.mkdirs()) { "Unable to create staging directory" }

        val packageDirectories = EmojiCatalog.groups.associate { group ->
            val directoryName =
                "${group.packageId}_${configuration.orderValue(group.packageId)}"
            val directory = File(stagingRoot, directoryName)
            require(File(directory, "emoji").mkdirs()) {
                "Unable to create staging package ${group.packageId}"
            }
            group.packageId to directory
        }

        require(entries.all { it.group.packageId in packageDirectories }) {
            "Some Unicode groups do not have a target package directory"
        }
        return InstallLayout(packageRoot, stagingRoot, backupRoot, packageDirectories)
    }

    private fun writePackageMetadata(
        entries: List<EmojiEntry>,
        layout: InstallLayout,
    ) {
        EmojiCatalog.groups.forEach { group ->
            val groupEntries = entries.filter { it.group == group }
            require(groupEntries.isNotEmpty()) { "Empty group: ${group.unicodeName}" }
            val directory = layout.packageDirectories.getValue(group.packageId)

            File(directory, "info.xml").writeText(
                buildInfoXml(group, groupEntries),
                StandardCharsets.UTF_8,
            )
            File(directory, "version.json").writeText(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" +
                "<Version text=\"${xmlEscape(configuration.emojiVersion)}\" />",
                StandardCharsets.UTF_8,
            )

            val firstImage = File(directory, "emoji/${groupEntries.first().imageId}.png")
            firstImage.inputStream().use { input ->
                File(directory, "${group.type}.png").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun buildInfoXml(
        group: EmojiGroup,
        entries: List<EmojiEntry>,
    ): String = buildString(entries.size * 180) {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<expList version=\"1.0\">\n")
        append("  <exppkg type=\"")
        append(xmlEscape(group.type))
        append("\" name=\"")
        append(xmlEscape(group.type))
        append("\" chineseName=\"")
        append(xmlEscape(group.chineseName))
        append("\" id=\"")
        append(group.packageId)
        append("\">\n")

        entries.forEach { entry ->
            append("    <exp name=\"")
            append(entry.imageId)
            append("\" other=\"[")
            append(xmlEscape(entry.englishName))
            append("]\" pinyin=\"\" unified=\"")
            append(xmlEscape(entry.unified))
            append("\" softbank=\"\" supportSkin=\"")
            append(if (entry.supportsSkin) 1 else 0)
            append("\" FE0F=\"")
            append(if (entry.insertsSkinBeforeFe0f) 1 else 0)
            append("\"")
            append(" currentColor=\"0\" hasSelectedColor=\"")
            append(if (entry.startsWithSelectedSkin) 1 else 0)
            append("\"/>\n")
        }

        append("  </exppkg>\n")
        append("</expList>\n")
    }

    private fun validateStaging(
        entries: List<EmojiEntry>,
        layout: InstallLayout,
    ) {
        EmojiCatalog.groups.forEach { group ->
            val expected = entries
                .filter { it.group == group }
                .sumOf(EmojiEntry::imageCount)
            val directory = layout.packageDirectories.getValue(group.packageId)
            val images = File(directory, "emoji")
                .listFiles { file -> file.isFile && file.extension == "png" }
                ?.size
                ?: 0
            require(images == expected) {
                "Package ${group.packageId} expected $expected images, found $images"
            }
            require(File(directory, "info.xml").length() > 0L)
            require(File(directory, "version.json").length() > 0L)
            require(File(directory, "${group.type}.png").length() > 0L)
        }
    }

    private fun commit(
        layout: InstallLayout,
        catalog: PreparedEmojiCatalog,
    ) {
        val sogouRoot = layout.packageRoot.parentFile
            ?: error("Invalid Sogou package root")
        deleteManagedDirectory(layout.backupRoot, sogouRoot)
        require(layout.backupRoot.mkdirs()) { "Unable to create backup directory" }

        val oldDirectories = activePackageDirectories(layout.packageRoot)
        val movedOld = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<Pair<File, File>>()
        try {
            oldDirectories.forEach { source ->
                val backup = File(layout.backupRoot, source.name)
                require(source.renameTo(backup)) { "Unable to back up ${source.name}" }
                movedOld += source to backup
            }

            layout.packageDirectories.values.forEach { staged ->
                val target = File(layout.packageRoot, staged.name)
                require(staged.renameTo(target)) { "Unable to install ${staged.name}" }
                installed += staged to target
            }
        } catch (error: Throwable) {
            installed.asReversed().forEach { (staged, target) ->
                if (target.exists()) target.renameTo(staged)
            }
            movedOld.asReversed().forEach { (original, backup) ->
                if (backup.exists()) backup.renameTo(original)
            }
            throw error
        }

        deleteManagedDirectory(layout.stagingRoot, sogouRoot)
        File(sogouRoot, ".unicode-installed").writeText(
            "unicode=${configuration.emojiVersion}\n" +
                "unicodeFolder=${configuration.unicodeFolder}\n" +
                "sourceSequences=${catalog.sourceSequenceCount}\n" +
                "supportedSequences=${catalog.supportedSequenceCount}\n" +
                "skippedSequences=${catalog.skippedSequenceCount}\n" +
                "entries=${catalog.entries.size}\n" +
                "images=${catalog.imageCount}\n" +
                "skinSets=${catalog.mergedSkinSets}\n" +
                "dualSkinMatrices=${catalog.mergedDualSkinMatrices}\n" +
                "includeNonFullyQualified=${configuration.includeNonFullyQualified}\n" +
                "includeUnsupportedSystemEmoji=${configuration.includeUnsupportedSystemEmoji}\n" +
                "notoSource=${configuration.notoDownloadSource.name}\n" +
                "groupOrder=${configuration.groupOrder.joinToString(",")}\n" +
                "installedAt=${System.currentTimeMillis()}\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun activePackageDirectories(packageRoot: File): List<File> =
        packageRoot.listFiles()
            ?.filter { file ->
                file.isDirectory && packageId(file) in REPLACED_PACKAGE_IDS
            }
            .orEmpty()

    private fun packageId(directory: File): Int =
        PACKAGE_DIRECTORY.matchEntire(directory.name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: -1

    private fun deleteManagedDirectory(directory: File, parent: File) {
        val parentPath = parent.canonicalFile.path + File.separator
        require(directory.canonicalFile.path.startsWith(parentPath)) {
            "Refusing to delete unmanaged path: $directory"
        }
        if (directory.exists()) {
            require(directory.deleteRecursively()) { "Unable to clear $directory" }
        }
    }

    private fun writeNormalizedPng(bytes: ByteArray, target: File) {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Downloaded file is not a PNG: ${target.name}")
        val output = createBitmap(IMAGE_SIZE, IMAGE_SIZE)
        val scale = min(
            IMAGE_SIZE.toFloat() / source.width.toFloat(),
            IMAGE_SIZE.toFloat() / source.height.toFloat(),
        )
        val width = source.width * scale
        val height = source.height * scale
        val left = (IMAGE_SIZE - width) / 2f
        val top = (IMAGE_SIZE - height) / 2f
        Canvas(output).drawBitmap(
            source,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )

        FileOutputStream(target).use { stream ->
            require(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Unable to encode ${target.name}"
            }
            stream.flush()
            stream.fd.sync()
        }
        source.recycle()
        output.recycle()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private data class InstallLayout(
        val packageRoot: File,
        val stagingRoot: File,
        val backupRoot: File,
        val packageDirectories: Map<Int, File>,
    )

    private data class ImageDownload(
        val packageId: Int,
        val fileName: String,
        val sourceUrl: String,
    )

    private companion object {
        const val USER_AGENT = "SogouInputEmojiFixer/1.0"
        const val IMAGE_SIZE = 64
        const val DOWNLOAD_CONCURRENCY = 6
        const val DOWNLOAD_ATTEMPTS = 3
        const val PROGRESS_STEP = 4
        // Sogou's built-in packages use negative ordering values (for example
        // 13_-13001), while downloaded/generated packages can use timestamps.
        val PACKAGE_DIRECTORY = Regex("^(\\d+)_(-?\\d+)$")
        val REPLACED_PACKAGE_IDS = 3..23
    }
}

class EmojiGroupOrderInstaller(
    private val context: Context,
    private val configuration: PatchConfiguration,
) {
    fun apply() {
        val packageRoot = File(context.filesDir, "sogou/emojipackage")
        require(packageRoot.isDirectory) {
            "搜狗 Emoji 目录不存在，请先安装一次 Emoji"
        }
        val active = packageRoot.listFiles()
            ?.filter { directory ->
                directory.isDirectory && PACKAGE_DIRECTORY.matches(directory.name)
            }
            ?.mapNotNull { directory ->
                val id = PACKAGE_DIRECTORY.matchEntire(directory.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                if (id in configuration.groupOrder) id!! to directory else null
            }
            .orEmpty()
        val activeIds = active.map(Pair<Int, File>::first).toSet()
        val installableIds = EmojiCatalog.groups
            .map(EmojiGroup::packageId)
            .toSet()
        require(installableIds.all { it in activeIds }) {
            "活动 Unicode 分组不完整，请先执行一次完整安装"
        }
        require(activeIds.size == active.size) {
            "检测到重复的活动 Unicode 分组，请先执行一次完整安装"
        }

        val moves = active.map { (id, original) ->
            OrderMove(
                original = original,
                staged = File(packageRoot, ".order-$id-${System.nanoTime()}"),
                target = File(packageRoot, "${id}_${configuration.orderValue(id)}"),
            )
        }
        try {
            moves.forEach { move ->
                require(move.original.renameTo(move.staged)) {
                    "无法暂存分组 ${move.original.name}"
                }
            }
            moves.forEach { move ->
                require(move.staged.renameTo(move.target)) {
                    "无法应用分组 ${move.target.name} 的排序"
                }
            }
        } catch (error: Throwable) {
            moves.asReversed().forEach { move ->
                when {
                    move.target.exists() -> move.target.renameTo(move.original)
                    move.staged.exists() -> move.staged.renameTo(move.original)
                }
            }
            throw error
        }
    }

    private companion object {
        val PACKAGE_DIRECTORY = Regex("^(\\d+)_(-?\\d+)$")
    }

    private data class OrderMove(
        val original: File,
        val staged: File,
        val target: File,
    )
}
