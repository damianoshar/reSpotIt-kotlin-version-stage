package com.spotifydashboard.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The view mode is stored per board as a key; an unknown key falls back to the grid. */
class ViewModeTest {
    @Test
    fun everyModeRoundTripsThroughItsKey() {
        ViewMode.values().forEach { mode ->
            assertEquals(mode, ViewMode.fromKey(mode.key))
        }
    }

    @Test
    fun unknownKeysFallBackToTheGrid() {
        assertEquals(ViewMode.Grid, ViewMode.fromKey(null))
        assertEquals(ViewMode.Grid, ViewMode.fromKey(""))
        assertEquals(ViewMode.Grid, ViewMode.fromKey("nonsense"))
    }

    @Test
    fun theThreeModesAreTheWebFrontendsOnes() {
        assertEquals(
            listOf("grid", "list", "compact"),
            ViewMode.values().map { it.key },
        )
    }
}
