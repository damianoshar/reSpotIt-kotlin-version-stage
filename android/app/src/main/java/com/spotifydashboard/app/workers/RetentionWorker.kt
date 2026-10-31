package com.spotifydashboard.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spotifydashboard.app.api.SpotifyApiClient
import com.spotifydashboard.app.auth.SpotifyAuthManager
import com.spotifydashboard.app.data.AppDatabase
import com.spotifydashboard.app.utils.PlaylistRetentionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-mode expiry sweep. Remote mode is swept by the backend worker; this
 * worker only looks at playlists the phone itself scheduled.
 *
 * WorkManager's minimum periodic interval is 15 minutes, so a playlist can
 * outlive its expiry by up to one interval on-device. The backend worker is
 * the source of truth in remote mode.
 */
class RetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val due = PlaylistRetentionStore.due(context)
        if (due.isEmpty()) return@withContext Result.success()

        val auth = SpotifyAuthManager(context)
        val token = auth.getValidAccessToken() ?: return@withContext Result.retry()
        val header = "Bearer $token"
        val dao = AppDatabase.getDatabase(context).playlistDao()
        var hadFailure = false

        for (entry in due) {
            try {
                val response = SpotifyApiClient.playlistService.unfollowPlaylist(header, entry.playlistId)
                if (response.isSuccessful || response.code() == 404) {
                    dao.clearPlaylistAlbums(entry.playlistId)
                    dao.deletePlaylist(entry.playlistId)
                    PlaylistRetentionStore.clear(context, entry.playlistId)
                } else {
                    hadFailure = true
                    PlaylistRetentionStore.markError(context, entry.playlistId, "HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                hadFailure = true
                PlaylistRetentionStore.markError(
                    context, entry.playlistId, e.message ?: "delete failed",
                )
            }
        }
        if (hadFailure) Result.retry() else Result.success()
    }
}
