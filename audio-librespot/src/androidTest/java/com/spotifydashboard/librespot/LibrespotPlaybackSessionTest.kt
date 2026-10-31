package com.spotifydashboard.librespot

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4 credential persistence + silent-start verification (no Spotify account).
 */
@RunWith(AndroidJUnit4::class)
class LibrespotPlaybackSessionTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun credentialsRoundTripAndClear() {
        val store = LibrespotCredentialsStore(context)
        store.clear()
        assertNull(store.load())

        store.save("31337user", "AAECAwQFBgc=")
        assertEquals(LibrespotStoredCredentials("31337user", "AAECAwQFBgc="), store.load())

        store.save("other-user", "Zm9vYmFy")
        assertEquals(LibrespotStoredCredentials("other-user", "Zm9vYmFy"), store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun incompleteStoredEntryIsIgnored() {
        val store = LibrespotCredentialsStore(context)
        store.clear()
        // Simulate a half-written entry (username without the auth blob).
        context.getSharedPreferences("librespot_credentials", Context.MODE_PRIVATE)
            .edit()
            .putString("username", "user-only")
            .commit()
        assertNull(store.load())
        store.clear()
    }

    @Test
    fun silentConnectWithoutCredentialsFailsCleanly() {
        val playback = LibrespotPlayback(context)
        try {
            playback.forgetAccount()
            assertFalse(playback.hasStoredCredentials())
            assertFalse(playback.connect(allowInteractive = false))
            assertFalse(playback.isConnected)
            assertNotNull(playback.lastConnectError)
            assertEquals(LibrespotEngine.STATE_IDLE, playback.state)
            assertEquals(0, playback.positionMs)
        } finally {
            playback.close()
        }
    }

    @Test
    fun closedPlaybackIsInert() {
        val playback = LibrespotPlayback(context)
        playback.close()
        playback.close() // idempotent

        assertFalse(playback.isConnected)
        assertFalse(playback.load("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        playback.play()
        playback.pause()
        playback.stop()
        playback.seekTo(1_000)
        playback.setVolume(0.5f)
        assertEquals(0, playback.positionMs)
    }
}
