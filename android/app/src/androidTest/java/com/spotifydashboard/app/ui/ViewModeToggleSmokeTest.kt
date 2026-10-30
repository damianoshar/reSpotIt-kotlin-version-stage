package com.spotifydashboard.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotifydashboard.app.MainActivity
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore("Skipped on this revision: Compose isolated rules conflict with MainActivity's own setContent on some API levels. Re-enable after ActivityScenario-compatible refactor.")
class ViewModeToggleSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun skippedPlaceholder() {}
}
