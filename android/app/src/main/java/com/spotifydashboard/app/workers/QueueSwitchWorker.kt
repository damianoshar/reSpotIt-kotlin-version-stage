package com.spotifydashboard.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spotifydashboard.app.api.SpotifyApiClient
import com.spotifydashboard.app.auth.SpotifyAuthManager
import com.spotifydashboard.app.utils.QueuePlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backstop for the "queue to end" handover.
 *
 * The app itself switches playback the moment the current track is over (see
 * `MainViewModel.queueToEnd`). If the phone is locked, the app is killed or
 * the screen is off, this worker takes over: it waits for the track to end
 * and then moves playback over to the generated queue playlist.
 *
 * The switch only happens when the playback context is still the one the
 * request was made in - if the user started something else meanwhile, their
 * choice wins and the worker does nothing.
 */
class QueueSwitchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val pending = QueuePlaylistStore.pending(context) ?: return@withContext Result.success()

        val auth = SpotifyAuthManager(context)
        val token = auth.getValidAccessToken() ?: return@withContext Result.retry()
        val header = "Bearer $token"

        // Already handed over? Nothing to do.
        val current = runCatching {
            SpotifyApiClient.service.getCurrentlyPlaying(header)
        }.getOrNull()
        if (current?.isSuccessful == true) {
            val contextUri = current.body()?.context?.uri.orEmpty()
            if (contextUri == "spotify:playlist:${pending.playlistId}") {
                QueuePlaylistStore.setPending(context, null)
                return@withContext Result.success()
            }
            if (pending.expectedContextUri.isNotBlank() && contextUri != pending.expectedContextUri) {
                // The user moved on - do not hijack their playback.
                QueuePlaylistStore.setPending(context, null)
                return@withContext Result.success()
            }
        }

        // Prefer the device that is active right now - it can change while a
        // track is playing. The device from the request is the fallback.
        val deviceId = runCatching {
            val devices = SpotifyApiClient.service.getDevices(header).body()?.devices.orEmpty()
            devices.firstOrNull { it.is_active && !it.is_restricted && !it.id.isNullOrBlank() }
                ?.id
                ?: devices.firstOrNull { !it.is_restricted && !it.id.isNullOrBlank() }?.id
        }.getOrNull() ?: pending.deviceId

        val response = runCatching {
            SpotifyApiClient.service.play(
                header,
                deviceId,
                com.spotifydashboard.app.api.PlayRequest(
                    context_uri = "spotify:playlist:${pending.playlistId}",
                    offset = com.spotifydashboard.app.api.PlayOffset(position = 0),
                ),
            )
        }.getOrNull()

        if (response != null && response.isSuccessful) {
            QueuePlaylistStore.setPending(context, null)
            // The playlist this one replaces can go now.
            pending.previousPlaylistId?.let { previous ->
                runCatching {
                    SpotifyApiClient.playlistService.unfollowPlaylist(header, previous)
                }
            }
            Result.success()
        } else {
            Result.retry()
        }
    }
}
