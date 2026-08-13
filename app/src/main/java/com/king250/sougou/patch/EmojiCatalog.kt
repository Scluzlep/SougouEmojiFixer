package com.king250.sougou.patch

import java.util.Locale

data class EmojiGroup(
    val packageId: Int,
    val unicodeName: String,
    val type: String,
    val chineseName: String,
)

data class EmojiEntry(
    val imageId: Int,
    val group: EmojiGroup,
    val codePoints: List<Int>,
    val unified: String,
    val englishName: String,
    val sourceUrl: String,
    val skinVariants: List<EmojiSkinVariant> = emptyList(),
    val isDualSkinParent: Boolean = false,
) {
    val text: String
        get() = buildString {
            codePoints.forEach(::appendCodePoint)
        }

    val supportsSkin: Boolean
        get() = skinVariants.size == SKIN_MODIFIER_COUNT

    val imageCount: Int
        get() = 1 + skinVariants.size

    val startsWithSelectedSkin: Boolean
        get() = isDualSkinParent

    /**
     * Sogou's FE0F field is only an insertion hint for entries exposed through
     * its skin selector. It tells Sogou that the modifier belongs before the
     * FE0F immediately following the first code point. It is not a flag saying
     * that FE0F occurs anywhere in the sequence.
     */
    val insertsSkinBeforeFe0f: Boolean
        get() = supportsSkin && codePoints.getOrNull(1) == EMOJI_VARIATION_SELECTOR

    private companion object {
        const val SKIN_MODIFIER_COUNT = 5
        const val EMOJI_VARIATION_SELECTOR = 0xFE0F
    }
}

data class EmojiSkinVariant(
    val suffix: Int,
    val unified: String,
    val sourceUrl: String,
)

data class PreparedEmojiCatalog(
    val entries: List<EmojiEntry>,
    val sourceSequenceCount: Int,
    val supportedSequenceCount: Int,
    val skippedSequenceCount: Int,
    val mergedSkinSets: Int,
    val mergedDualSkinMatrices: Int,
) {
    val imageCount: Int
        get() = entries.sumOf(EmojiEntry::imageCount)
}

object EmojiCatalog {
    const val FIRST_IMAGE_ID = 20_000
    const val NOTO_VERSION = "v2.051"
    const val UNICODE_PUBLIC_URL = "https://www.unicode.org/Public/"
    const val UNICODE_LATEST_EMOJI_TEST_URL =
        "https://www.unicode.org/Public/emoji/latest/emoji-test.txt"

    val groups = listOf(
        EmojiGroup(3, "Smileys & Emotion", "smileys_emotion", "表情与情感"),
        EmojiGroup(4, "People & Body", "people_body", "人物与身体"),
        EmojiGroup(5, "Component", "component", "Emoji 组件"),
        EmojiGroup(6, "Animals & Nature", "animals_nature", "动物与自然"),
        EmojiGroup(7, "Food & Drink", "food_drink", "食物与饮品"),
        EmojiGroup(8, "Travel & Places", "travel_places", "旅行与地点"),
        EmojiGroup(9, "Activities", "activities", "活动"),
        EmojiGroup(10, "Objects", "objects", "物品"),
        EmojiGroup(11, "Symbols", "symbols", "符号"),
        EmojiGroup(12, "Flags", "flags", "旗帜"),
    )

    /**
     * Groups that participate in ordering. In addition to the installable
     * [groups], this includes Sogou's own generated packages such as the
     * "组合" combination package (id 2). Those are never downloaded or
     * patched, only reordered.
     */
    val orderGroups = groups + EmojiGroup(2, "Combination", "combination", "组合")

    private val groupsByUnicodeName = groups.associateBy { it.unicodeName }

    fun parseEmojiTest(
        text: String,
        expectedCount: Int? = null,
        includeNonFullyQualified: Boolean = false,
        notoDownloadSource: NotoDownloadSource = NotoDownloadSource.GITHUB,
    ): List<EmojiEntry> {
        val result = ArrayList<EmojiEntry>(expectedCount ?: 4_000)
        var currentGroup: EmojiGroup? = null

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("# group: ")) {
                currentGroup = groupsByUnicodeName[line.removePrefix("# group: ").trim()]
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val semicolon = line.indexOf(';')
            val hash = line.indexOf('#')
            if (semicolon <= 0 || hash <= semicolon) return@forEach

            val status = line.substring(semicolon + 1, hash).trim()
            val accepted = status == "fully-qualified" || status == "component" ||
                includeNonFullyQualified && status in NON_FULLY_QUALIFIED_STATUSES
            if (!accepted) return@forEach

            val group = currentGroup ?: return@forEach
            val codePoints = line.substring(0, semicolon)
                .trim()
                .split(Regex("\\s+"))
            val comment = line.substring(hash + 1).trim()
                .split(Regex("\\s+"), limit = 3)
            require(comment.size == 3) { "Invalid emoji-test comment: $line" }

            val unified = codePoints.joinToString("_") { it.uppercase(Locale.ROOT) }
            result += EmojiEntry(
                imageId = FIRST_IMAGE_ID + result.size,
                group = group,
                codePoints = codePoints.map { it.toInt(16) },
                unified = unified,
                englishName = comment[2],
                sourceUrl = sourceUrl(codePoints, notoDownloadSource),
            )
        }

        require(expectedCount == null || result.size == expectedCount) {
            "Unicode Emoji expected $expectedCount entries, " +
                "but received ${result.size}"
        }
        require(result.map { it.imageId }.toSet().size == result.size) {
            "Generated image IDs are not globally unique"
        }
        require(result.map { it.unified }.toSet().size == result.size) {
            "emoji-test contains duplicate fully-qualified/component sequences"
        }
        return result
    }

    fun emojiVersion(text: String): String = VERSION_LINE.find(text)
        ?.groupValues
        ?.get(1)
        ?: error("emoji-test.txt does not contain a Version header")

    fun emojiTestUrl(unicodeFolder: String): String =
        "$UNICODE_PUBLIC_URL$unicodeFolder/emoji/emoji-test.txt"

    fun prepareForSystem(
        sourceEntries: List<EmojiEntry>,
        hasGlyph: (String) -> Boolean,
    ): PreparedEmojiCatalog {
        val supported = sourceEntries.filter { hasGlyph(it.text) }
        val singleSkinVariants = supported
            .mapNotNull { entry ->
                val modifiers = entry.codePoints.filter(::isSkinModifier)
                if (modifiers.size != 1) return@mapNotNull null
                SkinCandidate(
                    baseKey = skinBaseKey(entry.codePoints),
                    modifier = modifiers.single(),
                    entry = entry,
                )
            }
            .groupBy(SkinCandidate::baseKey)
            .mapValues { (_, candidates) -> candidates.associateBy(SkinCandidate::modifier) }
        val completeDualSkinMatrices = supported
            .mapNotNull { entry ->
                val modifiers = entry.codePoints.filter(::isSkinModifier)
                if (modifiers.size != 2) return@mapNotNull null
                DualSkinCandidate(
                    baseKey = skinBaseKey(entry.codePoints),
                    firstModifier = modifiers[0],
                    secondModifier = modifiers[1],
                    entry = entry,
                )
            }
            .groupBy(DualSkinCandidate::baseKey)
            .mapNotNull { (baseKey, candidates) ->
                val byPair = candidates.associateBy { candidate ->
                    SkinPair(candidate.firstModifier, candidate.secondModifier)
                }
                val isComplete = candidates.size == DUAL_SKIN_COMBINATION_COUNT &&
                    SKIN_MODIFIERS.all { first ->
                        SKIN_MODIFIERS.all { second ->
                            SkinPair(first, second) in byPair
                        }
                    }
                if (isComplete) baseKey to byPair else null
            }
            .toMap()

        val consumed = HashSet<String>()
        val prepared = ArrayList<EmojiEntry>(supported.size)
        var mergedSkinSets = 0
        var mergedDualSkinMatrices = 0

        fun addPrepared(entry: EmojiEntry) {
            prepared += entry.copy(imageId = FIRST_IMAGE_ID + prepared.size)
        }

        supported.forEach { entry ->
            if (!consumed.add(entry.unified)) return@forEach

            val hasNoSkinModifier = entry.codePoints.none(::isSkinModifier)
            val dualMatrix = if (hasNoSkinModifier) {
                completeDualSkinMatrices[skinBaseKey(entry.codePoints)]
            } else {
                null
            }
            if (dualMatrix != null) {
                // Keep the neutral Unicode sequence as its own main-grid entry.
                addPrepared(entry)

                // Add five parents. Each parent fixes the second participant's
                // tone while Sogou's existing selector changes the first one.
                SKIN_MODIFIERS.forEach { fixedSecondModifier ->
                    val candidates = SKIN_MODIFIERS.map { firstModifier ->
                        dualMatrix.getValue(
                            SkinPair(firstModifier, fixedSecondModifier),
                        )
                    }
                    candidates.forEach { candidate -> consumed += candidate.entry.unified }

                    val initiallySelected = candidates.first().entry
                    val skinVariants = candidates.mapIndexed { index, candidate ->
                        EmojiSkinVariant(
                            suffix = index + 1,
                            unified = candidate.entry.unified,
                            sourceUrl = candidate.entry.sourceUrl,
                        )
                    }
                    addPrepared(
                        initiallySelected.copy(
                            skinVariants = skinVariants,
                            isDualSkinParent = true,
                        ),
                    )
                    mergedSkinSets++
                }
                mergedDualSkinMatrices++
                return@forEach
            }

            val variantsByModifier = if (hasNoSkinModifier) {
                singleSkinVariants[skinBaseKey(entry.codePoints)]
            } else {
                null
            }
            val completeSkinSet = SKIN_MODIFIERS.mapNotNull { modifier ->
                variantsByModifier?.get(modifier)
            }.takeIf { it.size == SKIN_MODIFIER_COUNT }

            val skinVariants = completeSkinSet.orEmpty().mapIndexed { index, candidate ->
                consumed += candidate.entry.unified
                EmojiSkinVariant(
                    suffix = index + 1,
                    unified = candidate.entry.unified,
                    sourceUrl = candidate.entry.sourceUrl,
                )
            }
            if (skinVariants.isNotEmpty()) mergedSkinSets++

            addPrepared(
                entry.copy(skinVariants = skinVariants),
            )
        }

        require(prepared.map { it.imageId }.toSet().size == prepared.size) {
            "Prepared image IDs are not globally unique"
        }
        require(prepared.all { it.skinVariants.isEmpty() || it.supportsSkin }) {
            "A partial skin set must not be exposed through Sogou's skin selector"
        }

        return PreparedEmojiCatalog(
            entries = prepared,
            sourceSequenceCount = sourceEntries.size,
            supportedSequenceCount = supported.size,
            skippedSequenceCount = sourceEntries.size - supported.size,
            mergedSkinSets = mergedSkinSets,
            mergedDualSkinMatrices = mergedDualSkinMatrices,
        )
    }

    private fun sourceUrl(
        codePoints: List<String>,
        downloadSource: NotoDownloadSource,
    ): String {
        val notoRoot = when (downloadSource) {
            NotoDownloadSource.GITHUB ->
                "https://raw.githubusercontent.com/googlefonts/noto-emoji/$NOTO_VERSION"
            NotoDownloadSource.JSDELIVR ->
                "https://cdn.jsdelivr.net/gh/googlefonts/noto-emoji@$NOTO_VERSION"
        }
        regionalFlagCode(codePoints)?.let { countryCode ->
            val resolvedCode = REGION_FLAG_ALIASES[countryCode] ?: countryCode
            return "$notoRoot/third_party/region-flags/png/$resolvedCode.png"
        }
        subdivisionFlagCode(codePoints)?.let { subdivisionCode ->
            return "$notoRoot/third_party/region-flags/png/$subdivisionCode.png"
        }

        val notoName = codePoints
            .filterNot { it.equals("FE0F", ignoreCase = true) }
            .joinToString("_") { it.lowercase(Locale.ROOT) }
        return "$notoRoot/png/128/emoji_u$notoName.png"
    }

    private fun regionalFlagCode(codePoints: List<String>): String? {
        if (codePoints.size != 2) return null
        val values = codePoints.map { it.toInt(16) }
        if (values.any { it !in 0x1F1E6..0x1F1FF }) return null
        return values.joinToString("") { value ->
            ('A'.code + value - 0x1F1E6).toChar().toString()
        }
    }

    private fun subdivisionFlagCode(codePoints: List<String>): String? {
        if (codePoints.size < 4 || !codePoints.first().equals("1F3F4", true)) return null
        val tag = codePoints.drop(1).dropLast(1).map { point ->
            val value = point.toInt(16)
            if (value !in 0xE0061..0xE007A) return null
            ('a'.code + value - 0xE0061).toChar()
        }.joinToString("")
        if (tag.length < 3) return null
        return "${tag.take(2)}-${tag.drop(2)}".uppercase(Locale.ROOT)
    }

    private fun isSkinModifier(codePoint: Int): Boolean = codePoint in SKIN_MODIFIERS

    private fun skinBaseKey(codePoints: List<Int>): String = codePoints
        .filterNot { it == EMOJI_VARIATION_SELECTOR || isSkinModifier(it) }
        .joinToString("_") { it.toString(16).uppercase(Locale.ROOT) }

    private data class SkinCandidate(
        val baseKey: String,
        val modifier: Int,
        val entry: EmojiEntry,
    )

    private data class SkinPair(
        val first: Int,
        val second: Int,
    )

    private data class DualSkinCandidate(
        val baseKey: String,
        val firstModifier: Int,
        val secondModifier: Int,
        val entry: EmojiEntry,
    )

    private val REGION_FLAG_ALIASES = mapOf(
        "BV" to "NO",
        "SJ" to "NO",
        "CP" to "FR",
        "MF" to "FR",
        "DG" to "IO",
        "EA" to "ES",
        "HM" to "AU",
        "UM" to "US",
    )

    private const val EMOJI_VARIATION_SELECTOR = 0xFE0F
    private const val SKIN_MODIFIER_COUNT = 5
    private const val DUAL_SKIN_COMBINATION_COUNT =
        SKIN_MODIFIER_COUNT * SKIN_MODIFIER_COUNT
    private val SKIN_MODIFIERS = 0x1F3FB..0x1F3FF
    private val VERSION_LINE = Regex("(?m)^# Version:\\s*([0-9]+\\.[0-9]+)\\s*$")
    private val NON_FULLY_QUALIFIED_STATUSES = setOf(
        "minimally-qualified",
        "unqualified",
    )
}
