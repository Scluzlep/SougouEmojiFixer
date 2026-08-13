package com.king250.sougou.patch

internal fun String.skinModifierCount(): Int =
    split('_').count { token ->
        token.length == 5 &&
            token.startsWith("1F3F") &&
            token.last() in 'B'..'F'
    }
