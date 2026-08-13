package com.king250.sougou.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchConfigurationTest {
    @Test
    fun assignsLargerOrderValueToEarlierGroup() {
        val reversed = EmojiCatalog.orderGroups.map(EmojiGroup::packageId).reversed()
        val configuration = PatchConfiguration(groupOrder = reversed)

        assertEquals(11_000L, configuration.orderValue(reversed.first()))
        assertEquals(1_000L, configuration.orderValue(reversed.last()))
        assertTrue(
            configuration.orderValue(reversed[2]) > configuration.orderValue(reversed[3]),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteGroupOrder() {
        PatchConfiguration(groupOrder = listOf(3, 4))
    }
}
