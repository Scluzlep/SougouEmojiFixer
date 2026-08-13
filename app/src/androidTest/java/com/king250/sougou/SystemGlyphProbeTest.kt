package com.king250.sougou

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemGlyphProbeTest {
    @Test
    fun probesEmojiUsingTheSystemFallbackChain() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = 64f
        }
        // These are human-readable log samples for device diagnostics only.
        // Production filtering happens in EmojiCatalog.prepareForSystem(),
        // which invokes Paint.hasGlyph() for every parsed Unicode sequence.
        val diagnosticSamples = linkedMapOf(
            "wave" to "👋",
            "wave_light" to "👋🏻",
            "wave_medium_light" to "👋🏼",
            "wave_medium" to "👋🏽",
            "wave_medium_dark" to "👋🏾",
            "wave_dark" to "👋🏿",
            "taiwan_flag" to "🇹🇼",
        )

        diagnosticSamples.forEach { (name, emoji) ->
            Log.i(TAG, "$name=${paint.hasGlyph(emoji)}")
        }
        assertTrue("The base waving hand should be available", paint.hasGlyph("👋"))
    }

    private companion object {
        const val TAG = "SogouEmojiGlyphProbe"
    }
}
