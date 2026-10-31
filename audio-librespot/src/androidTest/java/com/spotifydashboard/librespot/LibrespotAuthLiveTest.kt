package com.spotifydashboard.librespot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual live check of the on-device browser OAuth — **skipped unless**
 * `-e liveAuth true` is passed. It starts the loopback listener and logs the
 * authorize URL; the host opens that URL in the device browser and approves the
 * consent page. The access token and the stored auth blob are never logged or
 * written to disk.
 *
 * ```
 * adb shell am instrument -w \
 *   -e class com.spotifydashboard.librespot.LibrespotAuthLiveTest \
 *   -e liveAuth true \
 *   com.spotifydashboard.librespot.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LibrespotAuthLiveTest {

    @Test(timeout = 360_000)
    fun browserAuthorizationProducesStoredCredentials() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "liveAuth instrumentation argument not provided — skipping",
            args.getString("liveAuth") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val token = LibrespotAuth.authorize(
            port = LibrespotAuth.DEFAULT_PORT,
            timeoutMs = 300_000,
            launchBrowser = { url ->
                // The instrumentation process has no foreground activity, so a
                // VIEW intent from it opens the OAuth tab behind the current
                // app, and holding UiAutomation blocks the host's uiautomator.
                // Log the URL and let the host open it instead:
                //
                //   adb shell am start -a android.intent.action.VIEW -d '<url>'
                //
                // then approve the consent page. The real app calls
                // startActivity() from a foreground screen and needs neither
                // workaround.
                Log.i(TAG, "AUTHORIZE_URL=$url")
            },
        )
        assertTrue("empty access token", token.isNotBlank())

        val engine = LibrespotEngine.create(context.cacheDir)
        try {
            assertTrue("token login failed: ${engine.lastError}", engine.login(token))
            val stored = engine.storedCredentials
            assertNotNull("session did not expose stored credentials", stored)
            assertTrue("empty username", stored!!.first.isNotBlank())
            assertTrue("empty auth blob", stored.second.isNotBlank())
            Log.i(TAG, "authorized account: ${stored.first}")
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val TAG = "LibrespotAuthLiveTest"
    }
}
