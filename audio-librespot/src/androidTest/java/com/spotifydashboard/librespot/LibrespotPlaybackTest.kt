package com.spotifydashboard.librespot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.fail

/**
 * M3: end-to-end playback against the real Spotify service.
 *
 * Requires a **fresh Premium access token minted for librespot's playback
 * client id** (`PLAYBACK_CLIENT_ID` on the Rust side; scope `streaming`) —
 * tokens issued to the dashboard's own OAuth app are refused by Spotify's
 * `login5` endpoint since 2026-08 (see PROGRESS.md). Pass it as an
 * instrumentation argument — the tests are skipped when it is absent:
 *
 * ```
 * ./gradlew :audio-librespot:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.spotifyToken=<token>
 * ```
 *
 * Paths covered:
 *  - [premiumTokenPlaysAndProducesPcm]: token login → PLAYING → position
 *    advances → non-silent PCM leaves the native ring buffer.
 *  - [storedCredentialsReloginPlays]: token login → extract the reusable
 *    credentials the AP derives → a *fresh* engine connects with only those
 *    (no token) → PLAYING. This is the steady-state path after the one-time
 *    interactive OAuth.
 */
@RunWith(AndroidJUnit4::class)
class LibrespotPlaybackTest {

    private companion object {
        const val TAG = "LibrespotPlaybackTest"
        const val TEST_TRACK = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    }

    @Test(timeout = 240_000)
    fun premiumTokenPlaysAndProducesPcm() {
        val token = InstrumentationRegistry.getArguments().getString("spotifyToken").orEmpty()
        assumeTrue("spotifyToken instrumentation argument not provided — skipping", token.isNotBlank())

        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val engine = LibrespotEngine.create(cacheDir)
        try {
            // NB: capture the result first — building the assertion message
            // before login() would read lastError in its pre-login (null) state.
            val loginOk = engine.login(token)
            Log.i(TAG, "login ok=$loginOk lastError=${engine.lastError}")
            assertTrue("login failed: ${engine.lastError}", loginOk)

            val loadOk = engine.load(TEST_TRACK)
            Log.i(TAG, "load ok=$loadOk lastError=${engine.lastError}")
            assertTrue("load rejected: ${engine.lastError}", loadOk)

            // Wait for the pipeline to reach PLAYING (AP connect, audio fetch, keys, decode).
            waitForPlaying(engine)

            // Position must advance and decoded PCM must flow while we drain.
            assertPlaybackProgress(engine)

            engine.stop()
        } finally {
            engine.close()
        }
    }

    /**
     * Steady-state path: log in once with a token, extract the reusable
     * credentials the AP derives, then connect a *fresh* engine using only
     * those credentials — no browser, no token refresh.
     */
    @Test(timeout = 240_000)
    fun storedCredentialsReloginPlays() {
        val token = InstrumentationRegistry.getArguments().getString("spotifyToken").orEmpty()
        assumeTrue("spotifyToken instrumentation argument not provided — skipping", token.isNotBlank())

        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

        // Step 1 — token login; extract the reusable credentials.
        val credentials: Pair<String, String> =
            LibrespotEngine.create(cacheDir).use { first ->
                val loginOk = first.login(token)
                Log.i(TAG, "stored-creds: token login ok=$loginOk lastError=${first.lastError}")
                assertTrue("login failed: ${first.lastError}", loginOk)
                val extracted = first.storedCredentials
                Log.i(TAG, "stored-creds: extracted=${extracted != null}")
                assertNotNull("token login produced no stored credentials", extracted)
                extracted!!
            }

        // Step 2 — fresh engine, stored-credentials login, real playback.
        LibrespotEngine.create(cacheDir).use { second ->
            val loginOk = second.loginStored(credentials.first, credentials.second)
            Log.i(TAG, "stored-creds: relogin ok=$loginOk lastError=${second.lastError}")
            assertTrue("stored-credential login failed: ${second.lastError}", loginOk)

            assertTrue("load rejected: ${second.lastError}", second.load(TEST_TRACK))
            waitForPlaying(second)
            assertPlaybackProgress(second)
            second.stop()
        }
    }

    /**
     * Verifies that playback is actually running, not just that librespot
     * reported PLAYING: the decoded PCM has to be drained continuously
     * (librespot paces decoding on `Sink::write`, so it stalls while the ring
     * buffer is full) and the reported position must advance in the meantime.
     */
    private fun assertPlaybackProgress(engine: LibrespotEngine) {
        val chunk = ShortArray(LibrespotBridge.PCM_CHUNK_SAMPLES)
        val minSamples = LibrespotEngine.SAMPLE_RATE_HZ.toLong() // 0.5 s of stereo audio
        var totalSamples = 0L
        var sawNonZero = false
        var position = 0
        var state = engine.state
        val deadline = System.currentTimeMillis() + 30_000
        var nextLog = 0L

        while (System.currentTimeMillis() < deadline) {
            state = engine.state
            position = engine.positionMs
            val n = engine.readPcm(chunk)
            if (n > 0) {
                totalSamples += n
                if (!sawNonZero && chunk.any { it != 0.toShort() }) sawNonZero = true
            }
            if (state == LibrespotEngine.STATE_UNAVAILABLE) {
                fail("track unavailable: ${engine.lastError}")
            }
            if (position > 0 && totalSamples >= minSamples && sawNonZero) break
            if (state == LibrespotEngine.STATE_IDLE && totalSamples > 0) break

            val now = System.currentTimeMillis()
            if (now >= nextLog) {
                Log.i(
                    TAG,
                    "progress: state=$state positionMs=$position " +
                        "pcmSamples=$totalSamples nonZero=$sawNonZero",
                )
                nextLog = now + 2_000
            }
            Thread.sleep(20)
        }

        Log.i(
            TAG,
            "final: state=$state positionMs=$position pcmSamples=$totalSamples " +
                "nonZero=$sawNonZero lastError=${engine.lastError}",
        )
        assertTrue(
            "position did not advance (state=$state, positionMs=$position, " +
                "pcmSamples=$totalSamples, lastError=${engine.lastError})",
            position > 0,
        )
        assertTrue(
            "no PCM received (state=$state, pcmSamples=$totalSamples, " +
                "lastError=${engine.lastError})",
            totalSamples >= minSamples,
        )
        assertTrue("PCM received but all samples are zero (sawNonZero=$sawNonZero)", sawNonZero)
    }

    /** Waits for PLAYING (AP connect, audio fetch, keys, decode) or fails. */
    private fun waitForPlaying(engine: LibrespotEngine) {
        val playDeadline = System.currentTimeMillis() + 90_000
        while (engine.state != LibrespotEngine.STATE_PLAYING &&
            System.currentTimeMillis() < playDeadline
        ) {
            if (engine.state == LibrespotEngine.STATE_UNAVAILABLE) {
                fail("track unavailable: ${engine.lastError}")
            }
            Thread.sleep(250)
        }
        assertEquals(
            "never reached PLAYING (state=${engine.state}, lastError=${engine.lastError})",
            LibrespotEngine.STATE_PLAYING,
            engine.state,
        )
    }
}
