package com.spotifydashboard.app.ui

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSpecSnapshotTest {
    @Test fun logDeviceSpec() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dm = ctx.resources.displayMetrics
        Log.d("AppDeviceSpec", "manufacturer=${android.os.Build.MANUFACTURER} model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} release=${android.os.Build.VERSION.RELEASE}")
        Log.d("AppDeviceSpec", "size=${dm.widthPixels}x${dm.heightPixels} density=${dm.density} dpi=${dm.densityDpi} scaledDensity=${dm.scaledDensity}")
    }
}
