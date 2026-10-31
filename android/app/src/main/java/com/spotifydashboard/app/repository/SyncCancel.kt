package com.spotifydashboard.app.repository

/**
 * Shared mutable cancellation flag, mirroring the TS `CancellationToken`
 * (`{ cancelled: boolean }`) passed through the entire sync chain.
 *
 * Checkpoints live at: start of each album page fetch, each artist genre
 * batch, and each DB save chunk. On cancel the sync stops gracefully and
 * keeps partial progress already written to the DB.
 */
class SyncCancelSignal {
    @Volatile
    var cancelled: Boolean = false
}
