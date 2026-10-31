package com.yukiorita.halfbrowser.core

/**
 * Pure bookmark grid sizing. Kept out of the composable so the "auto" thresholds
 * can be unit-tested; the UI passes its available width in dp.
 */
object GridColumns {

    fun resolve(widthDp: Float, fixed: Int): Int {
        if (fixed in setOf(3, 4, 5, 6, 8)) return fixed
        return when {
            widthDp < 360f -> 3
            widthDp < 480f -> 4
            widthDp < 600f -> 5
            widthDp < 840f -> 6
            else -> 8
        }
    }
}
