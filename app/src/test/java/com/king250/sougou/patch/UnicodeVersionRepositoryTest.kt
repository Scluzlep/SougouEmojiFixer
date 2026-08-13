package com.king250.sougou.patch

import org.junit.Assert.assertEquals
import org.junit.Test

class UnicodeVersionRepositoryTest {
    @Test
    fun excludesDraftFolderNewerThanLatestStableManifest() {
        val index = """
            <a href="15.1.0/">15.1.0/</a>
            <a href="16.0.0/">16.0.0/</a>
            <a href="17.0.0/">17.0.0/</a>
            <a href="18.0.0/">18.0.0/</a>
            <a href="draft/">draft/</a>
        """.trimIndent()

        val versions = UnicodeVersionRepository.parseStableVersions(index, "17.0")

        assertEquals(
            listOf("17.0.0", "16.0.0", "15.1.0"),
            versions.map(UnicodeVersionOption::unicodeFolder),
        )
    }
}
