/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTimeTest {
    @Test
    fun `elapsed and remaining times`() {
        assertEquals("0:00" to "-3:07", widgetTimes(187_000L, 0L))
        assertEquals("1:05" to "-2:01", widgetTimes(187_000L, 65_400L))
        assertEquals("0:01" to "-1:02:06", widgetTimes(3_727_000L, 1_000L))
        assertEquals("" to "", widgetTimes(0L, 5_000L))
        assertEquals("0:10" to "-0:00", widgetTimes(10_000L, 20_000L))
    }
}
