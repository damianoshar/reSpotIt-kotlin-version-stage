package com.spotifydashboard.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotifydashboard.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomTabsRender() {
        Thread.sleep(8000)
        rule.onNodeWithText("Library").assertIsDisplayed()
        rule.onNodeWithText("Playlists").assertIsDisplayed()
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun navigateToPlaylistsAndSortRowRenders() {
        Thread.sleep(8000)
        rule.onNodeWithText("Playlists").performClick()
        Thread.sleep(4000)
        rule.onNodeWithText("Search playlists...").assertIsDisplayed()
        rule.onNodeWithText("Name", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Tracks", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Date Added", substring = true).assertIsDisplayed()
    }

    @Test
    fun viewModeTogglePresentOnLibrary() {
        Thread.sleep(6000)
        rule.onNodeWithContentDescription("Grid view").assertIsDisplayed()
        rule.onNodeWithContentDescription("List view").assertIsDisplayed()
        rule.onNodeWithContentDescription("Compact grid").assertIsDisplayed()
    }
}
