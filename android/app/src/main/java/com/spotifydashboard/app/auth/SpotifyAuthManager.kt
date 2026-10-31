package com.spotifydashboard.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import java.util.*
import kotlin.coroutines.resume

class SpotifyAuthManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("spotify_prefs", Context.MODE_PRIVATE)

    // The PKCE verifier must survive process death: on low-RAM devices the OS can
    // kill this process while the browser is in the foreground, and the OAuth
    // callback then arrives in a brand-new process with no in-memory state.
    private val KEY_PENDING_VERIFIER = "pending_code_verifier"
    private val KEY_PENDING_STATE = "pending_auth_state"
    private val KEY_PENDING_TIME = "pending_auth_time"
    private val KEY_GRANTED_SCOPES = "granted_scopes"
    private val PENDING_AUTH_TTL_MS = 30 * 60 * 1000L
    
    private val KEY_USE_BUILTIN_CREDS = "use_builtin_creds"
    private val KEY_CLIENT_ID = "client_id"
    private val KEY_CLIENT_SECRET = "client_secret"

    fun getClientId(): String {
        val saved = prefs.getString(KEY_CLIENT_ID, null)?.trim()
        if (!saved.isNullOrBlank()) return saved
        // Dev-only: allow quick login using a built-in client_id (never shipped to end users).
        return if (com.spotifydashboard.app.BuildConfig.DEBUG) "386e37553a3b492f9381ad44084d9251" else ""
    }

    fun hasCredentials(): Boolean = getClientId().isNotBlank()

    fun saveCredentials(clientId: String, clientSecret: String) {
        prefs.edit().apply {
            if (clientId.isNotBlank()) putString(KEY_CLIENT_ID, clientId.trim()) else remove(KEY_CLIENT_ID)
            if (clientSecret.isNotBlank()) putString(KEY_CLIENT_SECRET, clientSecret.trim()) else remove(KEY_CLIENT_SECRET)
            apply()
        }
    }
    private val redirectUri = Uri.parse("com.spotifydashboard.app://callback")
    // `streaming` is required by the experimental librespot player (audio-librespot/):
    // playback-capable access tokens need it. `user-library-modify` lets the
    // Tools catalogue flows save releases to the Spotify library. Existing
    // sessions must re-link (logout/login) to pick up new scopes — refresh
    // tokens keep the scopes from the original authorization. See
    // audio-librespot/PROGRESS.md.
    private val scopes = "user-library-read user-library-modify user-read-private user-read-email playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-modify-playback-state user-read-playback-state user-read-currently-playing streaming user-follow-read user-follow-modify"
    
    private val serviceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.spotify.com/authorize"),
        Uri.parse("https://accounts.spotify.com/api/token")
    )

    // Created lazily: the AuthorizationService constructor binds to the
    // browser's Custom Tabs service, which is slow (and leak-prone) on this
    // class of device — and unnecessary when the user is already logged in.
    private val authService by lazy { AuthorizationService(context) }
    private var pendingRequest: AuthorizationRequest? = null

    fun createAuthIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfiguration,
            getClientId(),
            "code",
            redirectUri
        ).setScope(scopes).build()
        
        pendingRequest = request
        // Persist before launching the browser. commit() (not apply()) so the
        // verifier is on disk even if the process is killed right after this call.
        prefs.edit()
            .putString(KEY_PENDING_VERIFIER, request.codeVerifier)
            .putString(KEY_PENDING_STATE, request.state)
            .putLong(KEY_PENDING_TIME, System.currentTimeMillis())
            .commit()

        // Launch the system browser directly instead of using AppAuth's
        // authorization intent. AppAuth would register a second handler for
        // com.spotifydashboard.app://callback, which triggers the device's
        // broken app chooser. The browser redirects back to MainActivity,
        // which exchanges the code on-device; no server is involved.
        return Intent(Intent.ACTION_VIEW, request.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    fun handleAuthorizationResponse(
        data: Intent?,
        callback: (String?, Exception?) -> Unit
    ) {
        if (data == null) {
            callback(null, Exception("No auth data"))
            clearPendingAuth()
            return
        }

        val uri = data.data
        if (uri == null) {
            callback(null, Exception("No URI in intent"))
            clearPendingAuth()
            return
        }

        Log.d("SpotifyAuth", "Received auth callback URI: $uri")

        val error = uri.getQueryParameter("error")
        if (error != null) {
            val errorDesc = uri.getQueryParameter("error_description") ?: "Unknown OAuth error"
            Log.e("SpotifyAuth", "OAuth error: $error - $errorDesc")
            callback(null, Exception("OAuth error: $error - $errorDesc"))
            clearPendingAuth()
            return
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrEmpty()) {
            Log.e("SpotifyAuth", "No auth code in URI. URI: $uri")
            callback(null, Exception("No auth code in callback URI"))
            clearPendingAuth()
            return
        }

        // Validate state when we have a stored value to compare against.
        val returnedState = uri.getQueryParameter("state")
        val storedState = prefs.getString(KEY_PENDING_STATE, null)
        if (!storedState.isNullOrEmpty() && !returnedState.isNullOrEmpty() && storedState != returnedState) {
            Log.e("SpotifyAuth", "OAuth state mismatch")
            callback(null, Exception("Login state mismatch — please try again"))
            clearPendingAuth()
            return
        }

        // Prefer the in-memory request; fall back to the persisted verifier when
        // the process was restarted while the browser was open.
        val verifier = pendingRequest?.codeVerifier ?: readPendingVerifier()
        if (verifier.isNullOrEmpty()) {
            Log.e("SpotifyAuth", "No PKCE verifier available (process restart before persistence?)")
            callback(null, Exception("Login session expired — tap Connect with Spotify again"))
            clearPendingAuth()
            return
        }

        Log.d("SpotifyAuth", "Exchanging auth code for tokens")
        val tokenRequest = TokenRequest.Builder(serviceConfiguration, getClientId())
            .setRedirectUri(redirectUri)
            .setAuthorizationCode(code)
            .setCodeVerifier(verifier)
            .build()

        performTokenExchange(tokenRequest, callback)
    }

    private fun readPendingVerifier(): String? {
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null) ?: return null
        val createdAt = prefs.getLong(KEY_PENDING_TIME, 0L)
        if (createdAt > 0 && System.currentTimeMillis() - createdAt > PENDING_AUTH_TTL_MS) {
            Log.w("SpotifyAuth", "Persisted auth request expired")
            clearPendingAuth()
            return null
        }
        return verifier
    }

    private fun clearPendingAuth() {
        prefs.edit()
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .remove(KEY_PENDING_TIME)
            .apply()
    }

    private fun performTokenExchange(tokenRequest: TokenRequest, callback: (String?, Exception?) -> Unit) {
        authService.performTokenRequest(tokenRequest) { tokenResponse, authEx ->
            if (authEx != null) {
                Log.e("SpotifyAuth", "Token exchange error: ${authEx.message}", authEx)
                callback(null, authEx)
                return@performTokenRequest
            }

            if (tokenResponse != null) {
                Log.d("SpotifyAuth", "Token exchange successful")
                saveToken(tokenResponse)
                clearPendingAuth()
                callback(tokenResponse.accessToken, null)
            } else {
                Log.e("SpotifyAuth", "No token response received")
                callback(null, Exception("No token response"))
            }
        }
    }

    /**
     * Whether the current authorization included [scope]. Tokens from before
     * scopes were persisted report false, which prompts a re-link instead of
     * letting a write fail with an opaque HTTP 403.
     */
    fun hasGrantedScope(scope: String): Boolean =
        prefs.getStringSet(KEY_GRANTED_SCOPES, emptySet())?.contains(scope) == true

    fun getAccessToken(): String? {
        val accessToken = prefs.getString("access_token", null) ?: return null
        val expiresAt = prefs.getLong("expires_at", 0L)

        // 60s clock-skew buffer, mirrors TS getValidAccessToken()
        if (System.currentTimeMillis() > expiresAt - 60_000) {
            Log.d("SpotifyAuth", "Token expired or near expiry")
            return null
        }
        Log.d("SpotifyAuth", "Valid token found")
        return accessToken
    }

    /**
     * Suspend equivalent of TS getValidAccessToken().
     * Returns cached token if still valid, otherwise attempts a refresh
     * via the stored refresh_token. Returns null if not authenticated.
     */
    suspend fun getValidAccessToken(): String? {
        val cached = getAccessToken()
        if (cached != null) return cached

        val refreshToken = prefs.getString("refresh_token", null) ?: run {
            Log.w("SpotifyAuth", "No refresh token — login required")
            return null
        }
        return try {
            refreshAccessToken(refreshToken)
        } catch (e: Exception) {
            Log.e("SpotifyAuth", "Token refresh failed: ${e.message}", e)
            null
        }
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? {
        val request = TokenRequest.Builder(serviceConfiguration, getClientId())
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .setRedirectUri(redirectUri)
            .build()

        val tokenResponse = suspendCancellableCoroutine<TokenResponse?> { cont ->
            authService.performTokenRequest(request) { response, ex ->
                if (ex != null) {
                    Log.e("SpotifyAuth", "Refresh error: ${ex.message}", ex)
                    cont.resume(null)
                } else {
                    cont.resume(response)
                }
            }
        } ?: throw Exception("Token refresh failed — please log in again")

        // Spotify may omit a new refresh token; keep the old one (mirrors TS)
        saveToken(tokenResponse, fallbackRefreshToken = refreshToken)
        Log.d("SpotifyAuth", "Token refreshed successfully")
        return tokenResponse.accessToken
    }

    private fun saveToken(token: TokenResponse, fallbackRefreshToken: String? = null) {
        prefs.edit().apply {
            putString("access_token", token.accessToken)
            // Keep old refresh token if the response omits a new one
            putString("refresh_token", token.refreshToken ?: fallbackRefreshToken)
            // Prefer server-provided expiry; fall back to 1h (mirrors TS normalise)
            val expiresAt = token.accessTokenExpirationTime
                ?: (System.currentTimeMillis() + 3600 * 1000L)
            putLong("expires_at", expiresAt)
            val grantedScopes = token.scope
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
            if (grantedScopes != null) {
                putStringSet(KEY_GRANTED_SCOPES, grantedScopes)
            }
            apply()
        }
        Log.d("SpotifyAuth", "Token saved successfully")
    }

    fun clearTokens() {
        prefs.edit().clear().apply()
        Log.d("SpotifyAuth", "Tokens cleared")
    }
}
