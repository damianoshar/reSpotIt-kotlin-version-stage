package com.spotifydashboard.app.repository

import com.spotifydashboard.app.api.SpotifyDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which Spotify Connect device receives a playback command. */
class PlaybackDeviceTest {
    private fun device(
        id: String,
        active: Boolean = false,
        restricted: Boolean = false,
    ) = SpotifyDevice(id = id, name = id, type = "Smartphone", is_active = active, is_restricted = restricted)

    @Test
    fun prefersTheActiveDevice() {
        val chosen = pickPlaybackDevice(
            listOf(
                device("tv", active = false),
                device("phone", active = true),
            ),
        )

        assertEquals("phone", chosen?.id)
    }

    @Test
    fun fallsBackToTheFirstControllableDevice() {
        val chosen = pickPlaybackDevice(listOf(device("tv"), device("phone")))

        assertEquals("tv", chosen?.id)
    }

    @Test
    fun ignoresRestrictedDevices() {
        val chosen = pickPlaybackDevice(
            listOf(
                device("private", active = true, restricted = true),
                device("phone"),
            ),
        )

        assertEquals("phone", chosen?.id)
    }

    @Test
    fun returnsNullWhenNoDeviceCanPlay() {
        assertNull(pickPlaybackDevice(emptyList()))
        assertNull(pickPlaybackDevice(listOf(device("private", restricted = true))))
        assertNull(pickPlaybackDevice(listOf(SpotifyDevice(id = null, name = "no id"))))
    }
}
