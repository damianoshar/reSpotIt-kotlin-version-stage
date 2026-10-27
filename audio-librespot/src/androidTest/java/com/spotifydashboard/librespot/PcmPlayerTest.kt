package com.spotifydashboard.librespot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * M3: verifies the AudioTrack drain path on real devices without needing a
 * Spotify account (a synthetic PCM source stands in for the native engine).
 */
@RunWith(AndroidJUnit4::class)
class PcmPlayerTest {

    /** Quiet 440 Hz stereo sine for [totalSamples] interleaved samples, then silence. */
    private class SineSource(private val totalSamples: Long) : PcmPlayer.PcmSource {
        private val generated = AtomicLong(0)
        val produced: Long get() = generated.get()

        override fun read(out: ShortArray): Int {
            val done = generated.get()
            val remaining = totalSamples - done
            if (remaining <= 0) return 0
            val n = min(out.size.toLong(), remaining).toInt()
            for (i in 0 until n) {
                val t = (done + i).toDouble()
                out[i] = (sin(2.0 * PI * 440.0 * t / LibrespotEngine.SAMPLE_RATE_HZ) * 8000).toInt().toShort()
            }
            generated.addAndGet(n.toLong())
            return n
        }
    }

    @Test(timeout = 30_000)
    fun drainsSyntheticPcmPlayPauseRestartStop() {
        val source = SineSource(LibrespotBridge.PCM_CHUNK_SAMPLES * 4L)
        val player = PcmPlayer(source)
        try {
            player.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (source.produced < LibrespotBridge.PCM_CHUNK_SAMPLES * 4L &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            assertEquals(
                "all generated samples must be handed to the AudioTrack",
                LibrespotBridge.PCM_CHUNK_SAMPLES * 4L,
                source.produced,
            )
            assertTrue("AudioTrack should be playing", player.isPlaying)

            player.pause()
            assertFalse("AudioTrack should be paused", player.isPlaying)

            player.start() // resume
            assertTrue("AudioTrack should resume", player.isPlaying)

            player.stop()
            assertFalse("AudioTrack should be stopped", player.isPlaying)
        } finally {
            player.close()
        }
        Log.i("PcmPlayerTest", "synthetic drain OK (${source.produced} samples)")
    }

    @Test(timeout = 30_000)
    fun idleSourceStaysQuietAndStopsCleanly() {
        val player = PcmPlayer(source = { _ -> 0 })
        player.start()
        Thread.sleep(150)
        assertEquals(0L, player.totalSamplesWritten)
        player.stop()
        player.stop() // idempotent
        player.close() // idempotent
    }

    /**
     * Seek path: [PcmPlayer.flush] must drop the queued AudioTrack audio, keep
     * the drain running, and preserve the play/pause state.
     */
    @Test(timeout = 30_000)
    fun flushDropsQueuedAudioAndPreservesPlayState() {
        val source = SineSource(LibrespotBridge.PCM_CHUNK_SAMPLES * 16L)
        val player = PcmPlayer(source)
        try {
            player.start()
            val deadline = System.currentTimeMillis() + 10_000
            while (player.totalSamplesWritten < LibrespotBridge.PCM_CHUNK_SAMPLES * 4L &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            val before = player.totalSamplesWritten
            assertTrue("expected audio written before flush", before > 0)
            assertTrue("AudioTrack should be playing", player.isPlaying)

            player.flush()
            assertTrue("flush must keep playing", player.isPlaying)
            assertTrue(
                "flush must reset the AudioTrack queue ($before samples before)",
                player.totalSamplesWritten < before,
            )

            player.pause()
            assertFalse("AudioTrack should be paused", player.isPlaying)
            player.flush()
            assertFalse("flush must preserve pause", player.isPlaying)

            player.stop()
            assertFalse("AudioTrack should be stopped", player.isPlaying)
            assertEquals(0L, player.bufferedSamples)
        } finally {
            player.close()
        }
    }

    /** Real wiring: the engine as a source while idle (no account needed). */
    @Test(timeout = 60_000)
    fun engineAsSourceWorksWhileIdle() {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val engine = LibrespotEngine.create(cacheDir)
        val player = PcmPlayer(engine::readPcm)
        try {
            player.start()
            Thread.sleep(200)
            assertEquals(0L, player.totalSamplesWritten)
            player.stop()
        } finally {
            player.close()
            engine.close() // PcmPlayer must always be closed first
        }
    }
}
