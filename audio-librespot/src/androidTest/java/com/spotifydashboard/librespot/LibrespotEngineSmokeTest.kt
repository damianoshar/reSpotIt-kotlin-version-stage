package com.spotifydashboard.librespot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M2 device verification: loads `libspotify_librespot.so` on real hardware,
 * creates the engine (tokio runtime + librespot player + event loop), exercises
 * the transport surface that needs no Spotify account, and tears down cleanly.
 */
@RunWith(AndroidJUnit4::class)
class LibrespotEngineSmokeTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun engineCreatesExercisesAndCloses() {
        val engine = LibrespotEngine.create(cacheDir)
        try {
            assertEquals(LibrespotEngine.STATE_IDLE, engine.state)
            assertNull(engine.lastError)

            // Safe transport calls without a session.
            engine.setVolume(0.5f)
            engine.play()
            engine.pause()
            engine.stop()

            // Malformed URI must be rejected and reported, not crash.
            assertFalse(engine.load("not-a-spotify-uri"))
            assertNotNull(engine.lastError)
            assertEquals(LibrespotEngine.STATE_IDLE, engine.state)
        } finally {
            engine.close()
        }
    }

    @Test
    fun closedEngineIsInert() {
        val engine = LibrespotEngine.create(cacheDir)
        engine.close()
        engine.close() // idempotent
        assertFalse(engine.isOpen)
        assertEquals(LibrespotEngine.STATE_IDLE, engine.state)
        assertEquals(0, engine.readPcm(ShortArray(LibrespotBridge.PCM_CHUNK_SAMPLES)))
        assertFalse(engine.load("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(0, engine.bufferedSamples)
    }

    @Test
    fun pcmDrainOnIdleEngineReturnsZero() {
        val engine = LibrespotEngine.create(cacheDir)
        try {
            assertEquals(0, engine.readPcm(ShortArray(LibrespotBridge.PCM_CHUNK_SAMPLES)))
            assertEquals(0, engine.positionMs)
            assertEquals(0, engine.bufferedSamples)
        } finally {
            engine.close()
        }
    }

    @Test
    fun createWithNonExistentTmpDirFailsCleanly() {
        val bogus = File(cacheDir, "no/such/dir/${System.nanoTime()}")
        try {
            LibrespotEngine.create(bogus)
            throw AssertionError("create() should have thrown for a missing tmp dir")
        } catch (expected: IllegalStateException) {
            // expected: nativeCreate returns 0 for an unusable tmp dir
        }
    }

    /**
     * Lenient on purpose: with no/limited connectivity `login` must still fail
     * cleanly with an error message instead of crashing. A real Premium token
     * test arrives with M3.
     */
    @Test(timeout = 120_000)
    fun garbageTokenLoginFailsCleanly() {
        val engine = LibrespotEngine.create(cacheDir)
        try {
            val ok = engine.login("garbage-token-for-smoke-test")
            val error = engine.lastError
            android.util.Log.i("LibrespotSmokeTest", "garbage-token login ok=$ok error=$error")
            assertFalse(ok)
            assertNotNull(error)
        } finally {
            engine.close()
        }
    }
}
