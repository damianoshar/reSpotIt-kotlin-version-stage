package com.spotifydashboard.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.spotifydashboard.app.ui.theme.SpotifyDashboardTheme
import com.spotifydashboard.app.data.AppDatabase
import com.spotifydashboard.app.auth.SpotifyAuthManager
import com.spotifydashboard.app.repository.SpotifyRepository
import com.spotifydashboard.app.repository.PlaylistRepository
import com.spotifydashboard.app.repository.AuthProbeResult
import com.spotifydashboard.app.repository.DashboardRepository
import com.spotifydashboard.app.playback.LocalPlaybackController
import com.spotifydashboard.app.ui.SpotifyDashboardApp
import com.spotifydashboard.app.ui.SpotifyCredentialsScreen
import com.spotifydashboard.app.ui.MainViewModel
import com.spotifydashboard.app.ui.ServerLoginScreen
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spotifydashboard.app.workers.RetentionWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var authManager: SpotifyAuthManager
    private lateinit var dashboardRepository: DashboardRepository
    private lateinit var viewModel: MainViewModel
    private val isAuthenticated = mutableStateOf(false)
    private val isCheckingAuth = mutableStateOf(true)
    private val showServerLogin = mutableStateOf(false)
    private val showCredentials = mutableStateOf(false)
    private val sharedSiteUrl = mutableStateOf<String?>(null)
    private var lastProcessedAuthUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scheduleRetentionSweep()

        // Spotify chrome: black status bar with light icons.
        window.statusBarColor = android.graphics.Color.BLACK
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val db = AppDatabase.getDatabase(this)
        authManager = SpotifyAuthManager(this)
        dashboardRepository = DashboardRepository(this)

        val spotifyRepository = SpotifyRepository(this, db.albumDao(), db.playlistDao(), authManager)
        val playlistRepository = PlaylistRepository(this, db.playlistDao(), authManager)
        val localPlayback = LocalPlaybackController.get(this)

        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: java.lang.Class<T>): T {
                    return MainViewModel(
                        spotifyRepository,
                        playlistRepository,
                        dashboardRepository,
                        localPlayback,
                    ) as T
                }
            }
        )[MainViewModel::class.java]

        // Observe server-driven browser opens (Spotify account linking).
        lifecycleScope.launch {
            viewModel.pendingBrowserUrl.collect { url ->
                if (url != null) {
                    viewModel.consumeBrowserUrl()
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Could not open browser", e)
                    }
                }
            }
        }

        // Check for existing valid token FIRST (sync fast-path)
        val hasValidToken = authManager.getAccessToken() != null
        Log.d("MainActivity", "Initial token check: ${if (hasValidToken) "HAS TOKEN" else "NO TOKEN"}")

        // Remote mode uses the media-server JWT, not the on-device Spotify
        // token. /spotify/status is public on some backends (Python returns
        // 200 even without a session), so the gate must be /auth/status.
        if (dashboardRepository.getMode() == "remote") {
            lifecycleScope.launch {
                val probe = try {
                    dashboardRepository.probeAuth()
                } catch (e: Exception) {
                    Log.w("MainActivity", "Remote auth probe failed", e)
                    AuthProbeResult(reachable = false, authenticated = false)
                }
                when {
                    probe.authenticated -> {
                        viewModel.refreshSpotifyLink()
                        isAuthenticated.value = true
                    }
                    probe.reachable -> {
                        viewModel.clearServerAuthError()
                        showServerLogin.value = true
                    }
                    dashboardRepository.hasSession() -> {
                        // Offline start with a saved session: keep the shell
                        // usable instead of forcing a login that cannot work.
                        viewModel.refreshSpotifyLink()
                        isAuthenticated.value = true
                    }
                    else -> {
                        viewModel.setServerAuthError(
                            "Could not reach the media server at " +
                                dashboardRepository.getServerUrl()
                        )
                        showServerLogin.value = true
                    }
                }
                isCheckingAuth.value = false
            }
        } else {
            // Local mode: check the on-device Spotify token.
            // Check if this activity was started by a deep link (auth callback)
            val isAuthCallback = intent?.data?.scheme == "com.spotifydashboard.app"
            Log.d("MainActivity", "Is auth callback intent: $isAuthCallback")

            if (hasValidToken) {
                isAuthenticated.value = true
                isCheckingAuth.value = false
            } else if (isAuthCallback) {
                // This is a fresh auth callback - process it before showing UI
                handleAuthCallbackIntent(intent)
            } else {
                // No valid access token — try a silent refresh before showing login
                // (covers the case where the access token expired but refresh_token is stored)
                lifecycleScope.launch {
                    val refreshed = try {
                        authManager.getValidAccessToken()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Silent refresh failed: ${e.message}")
                        null
                    }
                    if (refreshed != null) {
                        Log.d("MainActivity", "Silent refresh succeeded")
                        isAuthenticated.value = true
                    }
                    isCheckingAuth.value = false
                }
            }
        }

        handleSharedIntent(intent)

        setContent {
            SpotifyDashboardTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (isCheckingAuth.value) {
                        // Show loading state while processing auth
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(androidx.compose.ui.graphics.Color(0xFF121212)),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else if (showServerLogin.value) {
                        val serverUrl = dashboardRepository.getServerUrl()
                        val serverBusy by viewModel.serverBusy.collectAsState()
                        val serverAuthError by viewModel.serverAuthError.collectAsState()
                        ServerLoginScreen(
                            serverUrl = serverUrl,
                            busy = serverBusy,
                            error = serverAuthError,
                            onLogin = { email, pass ->
                                viewModel.serverLogin(email, pass) { ok ->
                                    if (ok) {
                                        showServerLogin.value = false
                                        isAuthenticated.value = true
                                    }
                                }
                            },
                            onRegister = { email, pass ->
                                viewModel.serverRegister(email, pass) { ok ->
                                    if (ok) {
                                        showServerLogin.value = false
                                        isAuthenticated.value = true
                                    }
                                }
                            },
                            onBackToDevice = {
                                dashboardRepository.setMode("local")
                                showServerLogin.value = false
                                isCheckingAuth.value = true
                                lifecycleScope.launch {
                                    val refreshed = try {
                                        authManager.getValidAccessToken()
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (refreshed != null) isAuthenticated.value = true
                                    isCheckingAuth.value = false
                                }
                            }
                        )
                    } else if (showCredentials.value) {
                        SpotifyCredentialsScreen(
                            authManager = authManager,
                            onBack = { showCredentials.value = false }
                        )
                    } else if (!isAuthenticated.value && !authManager.hasCredentials()) {
                        SpotifyCredentialsScreen(
                            authManager = authManager,
                            onBack = { },
                            onSaved = { showCredentials.value = false }
                        )
                    } else {
                        SpotifyDashboardApp(
                            viewModel = viewModel,
                            onLogin = ::handleLogin,
                            onLogout = ::handleLogout,
                            onServerLogin = {
                                viewModel.clearServerAuthError()
                                showServerLogin.value = true
                            },
                            onConfigureCredentials = { showCredentials.value = true },
                            isAuthenticated = isAuthenticated.value,
                            sharedSiteUrl = sharedSiteUrl.value
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d("MainActivity", "onNewIntent received: ${intent.data}")
        handleAuthCallbackIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (shared.isBlank()) return
        sharedSiteUrl.value = shared.trim()
    }

    private fun handleAuthCallbackIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "com.spotifydashboard.app") return

        // onNewIntent + a recreated activity can both see the same callback.
        // The auth code is single-use, so exchange it only once.
        val key = uri.toString()
        if (key == lastProcessedAuthUri) {
            Log.d("MainActivity", "Ignoring already-processed auth callback")
            return
        }
        lastProcessedAuthUri = key
        processAuthCallback(intent)
    }

    private fun processAuthCallback(intent: Intent?) {
        Log.d("MainActivity", "Processing auth callback intent")
        authManager.handleAuthorizationResponse(intent) { token, error ->
            lifecycleScope.launch {
                if (token != null) {
                    isAuthenticated.value = true
                    Log.d("MainActivity", "Auth successful! Token received. Setting isAuthenticated = true")
                } else {
                    isAuthenticated.value = false
                    Log.e("MainActivity", "Auth failed: ${error?.message}")
                }
                isCheckingAuth.value = false
            }
        }
    }

    private fun handleLogin() {
        Log.d("MainActivity", "handleLogin called - launching auth intent")
        try {
            startActivity(authManager.createAuthIntent())
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not open Spotify authorization page", e)
        }
    }

    /**
     * Local-mode expiry sweep. Remote mode is swept by the backend worker, so
     * the worker finds an empty local store and exits immediately.
     */
    private fun scheduleRetentionSweep() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<RetentionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "playlist-retention-sweep",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
        // Catch anything that expired while the app was dead.
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<RetentionWorker>()
                .setConstraints(constraints)
                .build()
        )
    }

    private fun handleLogout() {
        // Remote sessions live server-side; local tokens live on-device.
        if (dashboardRepository.getMode() == "remote") {
            viewModel.serverLogout()
            isAuthenticated.value = false
            showServerLogin.value = true
            Log.d("MainActivity", "Signed out of media server")
            return
        }
        authManager.clearTokens()
        isAuthenticated.value = false
        Log.d("MainActivity", "User logged out")
    }
}
