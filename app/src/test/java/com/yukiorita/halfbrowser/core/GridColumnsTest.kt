package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GridColumnsTest {

    @Test
    fun `auto thresholds follow the window width`() {
        assertEquals(3, GridColumns.resolve(320f, 0))
        assertEquals(4, GridColumns.resolve(400f, 0))
        assertEquals(5, GridColumns.resolve(500f, 0))
        assertEquals(6, GridColumns.resolve(700f, 0))
        assertEquals(8, GridColumns.resolve(1000f, 0))
    }

    @Test
    fun `an explicit choice wins over the width`() {
        assertEquals(3, GridColumns.resolve(1200f, 3))
        assertEquals(8, GridColumns.resolve(320f, 8))
        assertEquals(5, GridColumns.resolve(320f, 5))
    }

    @Test
    fun `invalid fixed values fall back to auto`() {
        assertEquals(4, GridColumns.resolve(400f, 7))
        assertEquals(4, GridColumns.resolve(400f, -1))
    }

    @Test
    fun `boundaries are inclusive on the larger layout`() {
        assertEquals(3, GridColumns.resolve(359.9f, 0))
        assertEquals(4, GridColumns.resolve(360f, 0))
        assertEquals(5, GridColumns.resolve(480f, 0))
        assertEquals(6, GridColumns.resolve(600f, 0))
        assertEquals(8, GridColumns.resolve(840f, 0))
    }
}
