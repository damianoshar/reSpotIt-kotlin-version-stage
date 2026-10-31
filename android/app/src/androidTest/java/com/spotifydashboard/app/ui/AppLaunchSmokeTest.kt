package com.spotifydashboard.app.ui

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotifydashboard.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @get:Rule val scenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun appLaunches() {
        scenarioRule.scenario.onActivity { activity ->
            assertTrue(activity.hasWindowFocus() || activity.window != null)
        }
    }
}
