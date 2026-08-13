package com.king250.sougou.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    @Test
    fun readsEmojiVersionFromManifestHeader() {
        assertEquals("18.0", EmojiCatalog.emojiVersion("# emoji-test.txt\n# Version: 18.0\n"))
    }

    @Test
    fun optionallyIncludesNonFullyQualifiedSequences() {
        val source = """
            # Version: 17.0
            # group: Smileys & Emotion
            263A FE0F ; fully-qualified # ☺️ E0.6 smiling face
            263A ; unqualified # ☺ E0.6 smiling face
            1F636 200D 1F32B ; minimally-qualified # x E13.1 face in clouds
        """.trimIndent()

        assertEquals(
            1,
            EmojiCatalog.parseEmojiTest(source).size,
        )
        assertEquals(
            3,
            EmojiCatalog.parseEmojiTest(
                source,
                includeNonFullyQualified = true,
            ).size,
        )
    }

    @Test
    fun switchesNotoImageHostWithoutChangingPath() {
        val source = """
            # group: Smileys & Emotion
            1F600 ; fully-qualified # 😀 E1.0 grinning face
        """.trimIndent()

        val github = EmojiCatalog.parseEmojiTest(source).single().sourceUrl
        val mirror = EmojiCatalog.parseEmojiTest(
            source,
            notoDownloadSource = NotoDownloadSource.JSDELIVR,
        ).single().sourceUrl

        assertTrue(github.startsWith("https://raw.githubusercontent.com/"))
        assertTrue(mirror.startsWith("https://cdn.jsdelivr.net/"))
        assertEquals(
            github.substringAfter("/noto-emoji/${EmojiCatalog.NOTO_VERSION}"),
            mirror.substringAfter("/noto-emoji@${EmojiCatalog.NOTO_VERSION}"),
        )
    }

    @Test
    fun countsAdjacentDelimitedSkinModifierTokensWithoutRegexOverlap() {
        assertEquals(
            2,
            "1F469_1F3FB_200D_2764_FE0F_200D_1F469_1F3FC".skinModifierCount(),
        )
        assertEquals(1, "1F44B_1F3FE".skinModifierCount())
        assertEquals(0, "1F469_200D_2764_FE0F_200D_1F469".skinModifierCount())
    }

    @Test
    fun parsesOnlyRgiEntriesAndBuildsPinnedNotoUrls() {
        val source = """
            # group: Smileys & Emotion
            1F600 ; fully-qualified # 😀 E1.0 grinning face
            263A ; unqualified # ☺ E0.6 smiling face
            # group: Component
            1F3FB ; component # 🏻 E1.0 light skin tone
            # group: Flags
            1F1F9 1F1FC ; fully-qualified # 🇹🇼 E2.0 flag: Taiwan
            1F3F4 E0067 E0062 E0065 E006E E0067 E007F ; fully-qualified # 🏴 E5.0 flag: England
        """.trimIndent()

        val entries = EmojiCatalog.parseEmojiTest(source, expectedCount = 4)

        assertEquals(listOf(20_000, 20_001, 20_002, 20_003), entries.map { it.imageId })
        assertEquals("1F600", entries[0].unified)
        assertTrue(entries[0].sourceUrl.endsWith("/png/128/emoji_u1f600.png"))
        assertTrue(entries[1].sourceUrl.endsWith("/png/128/emoji_u1f3fb.png"))
        assertTrue(entries[2].sourceUrl.endsWith("/third_party/region-flags/png/TW.png"))
        assertTrue(entries[3].sourceUrl.endsWith("/third_party/region-flags/png/GB-ENG.png"))
    }

    @Test
    fun resolvesNotoRegionFlagSymlinksToActualPngFiles() {
        val source = """
            # group: Flags
            1F1E7 1F1FB ; fully-qualified # flag: Bouvet Island
            1F1F8 1F1EF ; fully-qualified # flag: Svalbard & Jan Mayen
            1F1E8 1F1F5 ; fully-qualified # flag: Clipperton Island
            1F1F2 1F1EB ; fully-qualified # flag: St. Martin
            1F1E9 1F1EC ; fully-qualified # flag: Diego Garcia
            1F1EA 1F1E6 ; fully-qualified # flag: Ceuta & Melilla
            1F1ED 1F1F2 ; fully-qualified # flag: Heard & McDonald Islands
            1F1FA 1F1F2 ; fully-qualified # flag: U.S. Outlying Islands
        """.trimIndent()

        val entries = EmojiCatalog.parseEmojiTest(source, expectedCount = 8)

        assertEquals(
            listOf("NO.png", "NO.png", "FR.png", "FR.png", "IO.png", "ES.png", "AU.png", "US.png"),
            entries.map { it.sourceUrl.substringAfterLast('/') },
        )
    }

    @Test
    fun filtersUnsupportedSequencesAndMergesCompleteSkinSets() {
        val source = """
            # group: People & Body
            1F44B ; fully-qualified # 👋 E0.6 waving hand
            1F44B 1F3FB ; fully-qualified # 👋🏻 E1.0 waving hand: light skin tone
            1F44B 1F3FC ; fully-qualified # 👋🏼 E1.0 waving hand: medium-light skin tone
            1F44B 1F3FD ; fully-qualified # 👋🏽 E1.0 waving hand: medium skin tone
            1F44B 1F3FE ; fully-qualified # 👋🏾 E1.0 waving hand: medium-dark skin tone
            1F44B 1F3FF ; fully-qualified # 👋🏿 E1.0 waving hand: dark skin tone
            # group: Flags
            1F1F9 1F1FC ; fully-qualified # 🇹🇼 E2.0 flag: Taiwan
        """.trimIndent()
        val sourceEntries = EmojiCatalog.parseEmojiTest(source, expectedCount = 7)
        val taiwanFlag = "\uD83C\uDDF9\uD83C\uDDFC"

        val prepared = EmojiCatalog.prepareForSystem(sourceEntries) { text ->
            text != taiwanFlag
        }

        assertEquals(7, prepared.sourceSequenceCount)
        assertEquals(6, prepared.supportedSequenceCount)
        assertEquals(1, prepared.skippedSequenceCount)
        assertEquals(1, prepared.mergedSkinSets)
        assertEquals(6, prepared.imageCount)
        assertEquals(1, prepared.entries.size)
        assertEquals(20_000, prepared.entries.single().imageId)
        assertEquals("1F44B", prepared.entries.single().unified)
        assertTrue(prepared.entries.single().supportsSkin)
        assertEquals(listOf(1, 2, 3, 4, 5), prepared.entries.single().skinVariants.map { it.suffix })
    }

    @Test
    fun mergesSkinVariantsWhenFullyQualifiedBaseContainsFe0f() {
        val source = """
            # group: People & Body
            26F9 FE0F ; fully-qualified # ⛹️ E0.7 person bouncing ball
            26F9 1F3FB ; fully-qualified # ⛹🏻 E2.0 person bouncing ball: light skin tone
            26F9 1F3FC ; fully-qualified # ⛹🏼 E2.0 person bouncing ball: medium-light skin tone
            26F9 1F3FD ; fully-qualified # ⛹🏽 E2.0 person bouncing ball: medium skin tone
            26F9 1F3FE ; fully-qualified # ⛹🏾 E2.0 person bouncing ball: medium-dark skin tone
            26F9 1F3FF ; fully-qualified # ⛹🏿 E2.0 person bouncing ball: dark skin tone
        """.trimIndent()

        val prepared = EmojiCatalog.prepareForSystem(
            EmojiCatalog.parseEmojiTest(source, expectedCount = 6),
            hasGlyph = { true },
        )

        assertEquals(1, prepared.entries.size)
        assertEquals("26F9_FE0F", prepared.entries.single().unified)
        assertTrue(prepared.entries.single().supportsSkin)
        assertTrue(prepared.entries.single().insertsSkinBeforeFe0f)
    }

    @Test
    fun checksEverySourceSequenceBeforeGrouping() {
        val source = """
            # group: People & Body
            1F44B ; fully-qualified # 👋 E0.6 waving hand
            1F44B 1F3FB ; fully-qualified # 👋🏻 E1.0 waving hand: light skin tone
            # group: Flags
            1F1F9 1F1FC ; fully-qualified # 🇹🇼 E2.0 flag: Taiwan
        """.trimIndent()
        val sourceEntries = EmojiCatalog.parseEmojiTest(source, expectedCount = 3)
        val checked = mutableListOf<String>()

        EmojiCatalog.prepareForSystem(sourceEntries) { emoji ->
            checked += emoji
            true
        }

        assertEquals(sourceEntries.map(EmojiEntry::text), checked)
    }

    @Test
    fun fe0fHintIsNotSetForPlainZwjSequences() {
        val source = """
            # group: People & Body
            1F469 1F3FE 200D 2764 FE0F 200D 1F469 1F3FE ; fully-qualified # 👩🏾‍❤️‍👩🏾 E13.1 couple with heart: woman, woman, medium-dark skin tone
        """.trimIndent()

        val entry = EmojiCatalog.prepareForSystem(
            EmojiCatalog.parseEmojiTest(source, expectedCount = 1),
            hasGlyph = { true },
        ).entries.single()

        assertTrue(!entry.supportsSkin)
        assertTrue(!entry.insertsSkinBeforeFe0f)
    }

    @Test
    fun groupsCompleteDualSkinMatrixIntoFiveFixedSecondToneParents() {
        val sourceEntries = EmojiCatalog.parseEmojiTest(
            dualSkinMatrixSource(),
            expectedCount = 26,
        )

        val prepared = EmojiCatalog.prepareForSystem(sourceEntries) { true }

        assertEquals(26, prepared.supportedSequenceCount)
        assertEquals(1, prepared.mergedDualSkinMatrices)
        assertEquals(5, prepared.mergedSkinSets)
        assertEquals(6, prepared.entries.size)
        assertEquals(31, prepared.imageCount)
        assertEquals((20_000..20_005).toList(), prepared.entries.map { it.imageId })

        val neutral = prepared.entries.first()
        assertEquals("1F469_200D_2764_FE0F_200D_1F469", neutral.unified)
        assertTrue(!neutral.supportsSkin)

        prepared.entries.drop(1).forEachIndexed { fixedSecondIndex, parent ->
            val fixedSecond = 0x1F3FB + fixedSecondIndex
            assertTrue(parent.supportsSkin)
            assertTrue(parent.isDualSkinParent)
            assertTrue(parent.startsWithSelectedSkin)
            assertEquals(
                listOf(0x1F3FB, fixedSecond),
                parent.codePoints.filter { it in 0x1F3FB..0x1F3FF },
            )
            assertEquals(parent.sourceUrl, parent.skinVariants.first().sourceUrl)
            assertEquals(
                (0x1F3FB..0x1F3FF).map { first -> listOf(first, fixedSecond) },
                parent.skinVariants.map { variant ->
                    variant.unified.split('_')
                        .map { it.toInt(16) }
                        .filter { it in 0x1F3FB..0x1F3FF }
                },
            )
        }
    }

    @Test
    fun leavesIncompleteDualSkinMatrixFlat() {
        val incomplete = dualSkinMatrixSource()
            .lineSequence()
            .toList()
            .dropLast(1)
            .joinToString("\n")
        val sourceEntries = EmojiCatalog.parseEmojiTest(incomplete, expectedCount = 25)

        val prepared = EmojiCatalog.prepareForSystem(sourceEntries) { true }

        assertEquals(0, prepared.mergedDualSkinMatrices)
        assertEquals(25, prepared.entries.size)
        assertEquals(25, prepared.imageCount)
        assertTrue(prepared.entries.none(EmojiEntry::isDualSkinParent))
    }

    @Test
    fun doesNotExposePartialSkinSets() {
        val source = """
            # group: People & Body
            1F44B ; fully-qualified # 👋 E0.6 waving hand
            1F44B 1F3FB ; fully-qualified # 👋🏻 E1.0 waving hand: light skin tone
            1F44B 1F3FC ; fully-qualified # 👋🏼 E1.0 waving hand: medium-light skin tone
            1F44B 1F3FD ; fully-qualified # 👋🏽 E1.0 waving hand: medium skin tone
            1F44B 1F3FE ; fully-qualified # 👋🏾 E1.0 waving hand: medium-dark skin tone
            1F44B 1F3FF ; fully-qualified # 👋🏿 E1.0 waving hand: dark skin tone
        """.trimIndent()
        val unsupportedDarkTone = "\uD83D\uDC4B\uD83C\uDFFF"

        val prepared = EmojiCatalog.prepareForSystem(
            EmojiCatalog.parseEmojiTest(source, expectedCount = 6),
            hasGlyph = { it != unsupportedDarkTone },
        )

        assertEquals(5, prepared.entries.size)
        assertEquals(0, prepared.mergedSkinSets)
        assertTrue(prepared.entries.none(EmojiEntry::supportsSkin))
        assertEquals((20_000..20_004).toList(), prepared.entries.map { it.imageId })
    }

    private fun dualSkinMatrixSource(): String = buildString {
        appendLine("# group: People & Body")
        appendLine(
            "1F469 200D 2764 FE0F 200D 1F469 ; fully-qualified " +
                "# 👩‍❤️‍👩 E13.1 couple with heart: woman, woman",
        )
        (0x1F3FB..0x1F3FF).forEach { first ->
            (0x1F3FB..0x1F3FF).forEach { second ->
                appendLine(
                    "1F469 ${first.toString(16)} 200D 2764 FE0F 200D " +
                        "1F469 ${second.toString(16)} ; fully-qualified " +
                        "# x E13.1 dual skin variant",
                )
            }
        }
    }.trimEnd()
}
