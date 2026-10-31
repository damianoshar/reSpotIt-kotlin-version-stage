package com.spotifydashboard.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.spotifydashboard.librespot.LibrespotEngine
import com.spotifydashboard.librespot.LibrespotPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Audio focus preference for the local player. */
enum class AudioFocusMode(val value: String) {
    Pause("pause"),
    Duck("duck"),
    Never("never"),
}

/** One playable item in the local (librespot) queue. */
data class LocalPlaybackTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Int,
    val artUrl: String?,
)

/** UI-facing snapshot of the experimental on-device playback session. */
data class LocalPlaybackState(
    val enabled: Boolean = false,
    val keepConnected: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val isPlaying: Boolean = false,
    val remote: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artUrl: String? = null,
    val durationMs: Int = 0,
    /** Audible position (decoded position minus both audio buffer depths). */
    val positionMs: Int = 0,
    val error: String? = null,
    /** True when no stored credentials exist and the browser login is needed. */
    val needsAuth: Boolean = false,
    /** Tracks currently queued for on-device playback. */
    val queue: List<LocalPlaybackTrack> = emptyList(),
    /** Index of the track currently playing in [queue]. */
    val queueIndex: Int = 0,
)

/**
 * App-level controller for the experimental librespot playback: owns the
 * [LibrespotPlayback] session, the album queue, audio focus and the foreground
 * service.
 *
 * The librespot login is independent from the dashboard's Spotify login: the
 * first play opens the Spotify consent page (keymaster client, scope
 * `streaming`) and the resulting reusable credentials are stored app-privately,
 * so later starts are silent.
 *
 * All blocking native/network work runs on [Dispatchers.IO]; state is published
 * on [Dispatchers.Main]. Never call it from the main thread directly.
 */
class LocalPlaybackController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var playback = LibrespotPlayback(appContext, deviceName = deviceName(), bufferSeconds = bufferSeconds())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Persisted Connect device name used when a new librespot session is created. */
    fun deviceName(): String =
        prefs.getString(KEY_DEVICE_NAME, "reSpotIt (${Build.MODEL})")
            ?.trim()
            ?.ifBlank { "reSpotIt (${Build.MODEL})" }
            ?: "reSpotIt (${Build.MODEL})"

    fun setDeviceName(name: String) {
        val cleaned = name.trim().ifBlank { "reSpotIt (${Build.MODEL})" }
        prefs.edit().putString(KEY_DEVICE_NAME, cleaned).apply()
        if (_state.value.isPlaying) {
            _state.update {
                it.copy(error = "Device name saved as \"$cleaned\"; applies after playback stops.")
            }
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { playback.stop(disconnect = true) }
            }
            LocalPlaybackService.stop(appContext)
            _state.update {
                it.copy(connected = false, error = "Device name saved as \"$cleaned\". Reconnect to apply.")
            }
        }
    }

    fun bufferSeconds(): Int =
        prefs.getInt(KEY_BUFFER_SECONDS, 8).coerceIn(2, 30)

    fun setBufferSeconds(seconds: Int) {
        val cleaned = seconds.coerceIn(2, 30)
        prefs.edit().putInt(KEY_BUFFER_SECONDS, cleaned).apply()
        if (_state.value.isPlaying) {
            _state.update {
                it.copy(error = "Buffer changed to ${cleaned}s; applies after playback stops.")
            }
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { playback.stop(disconnect = true) }
            }
            LocalPlaybackService.stop(appContext)
            _state.update {
                it.copy(connected = false, error = "Buffer changed to ${cleaned}s. Reconnect to apply.")
            }
        }
    }

    fun audioFocusMode(): AudioFocusMode {
        val stored = prefs.getString(KEY_AUDIO_FOCUS_MODE, AudioFocusMode.Pause.value)
        return AudioFocusMode.entries.firstOrNull { it.value == stored } ?: AudioFocusMode.Pause
    }

    fun setAudioFocusMode(mode: AudioFocusMode) {
        prefs.edit().putString(KEY_AUDIO_FOCUS_MODE, mode.value).apply()
        _state.update { it.copy(error = "Audio-focus behavior set to ${mode.value}.") }
    }

    private val _state = MutableStateFlow(
        LocalPlaybackState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            keepConnected = prefs.getBoolean(KEY_KEEP_CONNECTED, false),
        )
    )
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

    private var queue: List<LocalPlaybackTrack> = emptyList()
    private var index = 0
    private var playJob: Job? = null
    private var connectJob: Job? = null
    private var ticker: Job? = null
    private var lastEngineState = LibrespotEngine.STATE_IDLE
    private var userStopped = true
    private var resumeOnFocusGain = false

    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val mode = audioFocusMode()
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (mode != AudioFocusMode.Never) {
                    resumeOnFocusGain = false
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                when (mode) {
                    AudioFocusMode.Pause -> {
                        resumeOnFocusGain = _state.value.isPlaying
                        pause()
                    }
                    AudioFocusMode.Duck -> playback.setVolume(0.3f)
                    AudioFocusMode.Never -> Unit
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (mode != AudioFocusMode.Never) playback.setVolume(0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                playback.setVolume(1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    /** Persists the opt-in flag; disabling stops playback and tears the service down. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled, error = if (enabled) it.error else null) }
        if (!enabled) {
            _state.update { it.copy(keepConnected = false) }
            prefs.edit().putBoolean(KEY_KEEP_CONNECTED, false).apply()
            ticker?.cancel()
            ticker = null
            stop()
        } else if (prefs.getBoolean(KEY_KEEP_CONNECTED, false)) {
            // Keep the idle Connect service ready once the feature is on again.
            connectIdle()
        }
    }

    fun setKeepConnected(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_CONNECTED, enabled).apply()
        _state.update { it.copy(keepConnected = enabled) }
        if (enabled) {
            if (!_state.value.enabled) setEnabled(true)
            connectIdle()
        } else {
            // If idle now, drop the persistent connection; a playing session
            // keeps going and closes when it stops.
            if (!_state.value.isPlaying && playback.isConnected) {
                scope.launch(Dispatchers.IO) { runCatching { playback.stop(disconnect = true) } }
                LocalPlaybackService.stop(appContext)
                _state.update { it.copy(connected = false) }
            }
        }
    }

    private suspend fun connectOnce(allowInteractive: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { playback.connect(allowInteractive = allowInteractive) }.getOrDefault(false)
        }

    private fun connectIdle() {
        if (!_state.value.enabled) return
        if (playback.isConnected) {
            LocalPlaybackService.start(appContext)
            startTicker()
            return
        }
        if (connectJob?.isActive == true) return
        _state.update { it.copy(connecting = true, error = null, needsAuth = false) }
        connectJob = scope.launch {
            // The librespot Connect attempt sometimes fails with a transient
            // "channel closed" error. Retry silently with the stored credentials
            // before giving up — toggling local playback off/on already recovers,
            // and this keeps the session alive automatically.
            var connected = connectOnce(allowInteractive = true)
            if (!connected) {
                delay(750)
                connected = connectOnce(allowInteractive = false)
            }
            if (!connected) {
                delay(1500)
                connected = connectOnce(allowInteractive = false)
            }
            _state.update {
                it.copy(
                    connecting = false,
                    connected = connected,
                    needsAuth = !connected && !playback.hasStoredCredentials(),
                    error = if (connected) null else {
                        playback.lastConnectError ?: "Could not connect to Spotify."
                    },
                )
            }
            if (connected) {
                LocalPlaybackService.start(appContext)
                startTicker()
            } else {
                LocalPlaybackService.stop(appContext)
            }
        }
    }

    /**
     * Plays [tracks] starting at [startIndex]. Connects (and, on first use,
     * opens the Spotify consent page) before the first track starts.
     */
    fun play(tracks: List<LocalPlaybackTrack>, startIndex: Int = 0) {
        if (!_state.value.enabled) {
            _state.update { it.copy(error = "Enable experimental local playback first.") }
            return
        }
        val playable = tracks.filter { it.uri.isNotBlank() }
        if (playable.isEmpty()) {
            _state.update { it.copy(error = "No playable tracks here.") }
            return
        }
        queue = playable
        index = startIndex.coerceIn(0, playable.lastIndex)
        userStopped = false
        _state.update { it.copy(queue = playable, queueIndex = index) }
        // Start the foreground service while the app is still in the foreground:
        // the first play continues after the browser OAuth, when the app is
        // backgrounded, and Android 12+ rejects a background FGS start.
        LocalPlaybackService.start(appContext)
        connectAndStart()
    }

    /** Play/pause toggle for the mini player and notification. */
    fun toggle() {
        when (playback.state) {
            LibrespotEngine.STATE_PLAYING -> pause()
            LibrespotEngine.STATE_PAUSED -> resume()
            else -> {
                if (queue.isNotEmpty()) {
                    userStopped = false
                    if (playback.isConnected) startCurrent() else connectAndStart()
                }
            }
        }
    }

    fun pause() {
        if (playback.state != LibrespotEngine.STATE_PLAYING) return
        scope.launch(Dispatchers.IO) { runCatching { playback.pause() } }
        abandonFocus()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        if (!playback.isConnected) {
            if (queue.isEmpty()) return
            connectAndStart()
            return
        }
        when (playback.state) {
            LibrespotEngine.STATE_PAUSED -> scope.launch {
                if (requestFocus()) {
                    withContext(Dispatchers.IO) { runCatching { playback.play() } }
                    LocalPlaybackService.start(appContext)
                    _state.update { it.copy(isPlaying = true) }
                    startTicker()
                }
            }
            LibrespotEngine.STATE_IDLE, LibrespotEngine.STATE_UNAVAILABLE -> {
                if (isRemotePlayback()) {
                    scope.launch {
                        if (requestFocus()) {
                            withContext(Dispatchers.IO) { runCatching { playback.play() } }
                            LocalPlaybackService.start(appContext)
                            _state.update { it.copy(isPlaying = true) }
                            startTicker()
                        }
                    }
                } else if (queue.isNotEmpty()) {
                    userStopped = false
                    startCurrent()
                }
            }
            else -> Unit
        }
    }

    fun stop() {
        userStopped = true
        playJob?.cancel()
        playJob = null
        scope.launch(Dispatchers.IO) {
            runCatching { playback.stop(disconnect = !_state.value.keepConnected) }
        }
        abandonFocus()
        if (!_state.value.keepConnected) {
            LocalPlaybackService.stop(appContext)
        }
        _state.update { it.copy(remote = playback.currentTrack != null, isPlaying = false, positionMs = 0) }
    }

    fun next() {
        if (isRemotePlayback()) {
            scope.launch(Dispatchers.IO) { runCatching { playback.next() } }
            return
        }
        if (queue.isEmpty()) return
        if (index + 1 < queue.size) {
            index++
            userStopped = false
            startCurrent()
        }
    }

    /** Append a track to the end of the currently active local queue. */
    fun enqueue(track: LocalPlaybackTrack) {
        queue += track
        publishQueue()
    }

    /** Insert a track immediately after the track that is currently playing. */
    fun insertNext(track: LocalPlaybackTrack) {
        val insertion = if (queue.isEmpty()) 0 else (index + 1).coerceIn(0, queue.size)
        queue = queue.toMutableList().apply { add(insertion, track) }
        publishQueue()
    }

    /** Insert a track at an exact queue position, clamped to the current queue size. */
    fun insertAt(index: Int, track: LocalPlaybackTrack) {
        val target = index.coerceIn(0, queue.size)
        queue = queue.toMutableList().apply { add(target, track) }
        if (target <= this.index) this.index++
        publishQueue()
    }

    /** Remove the track at [index]. Playing continues when the current index survives. */
    fun removeAt(index: Int) {
        if (index !in queue.indices) return
        val removingCurrent = index == this.index
        queue = queue.toMutableList().apply { removeAt(index) }
        if (queue.isEmpty()) {
            this.index = 0
        } else if (removingCurrent) {
            this.index = this.index.coerceIn(0, queue.lastIndex)
        } else if (index < this.index) {
            this.index--
        }
        publishQueue()
        if (removingCurrent && queue.isNotEmpty()) startCurrent()
    }

    /** Move a queued track from one position to another. */
    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        val movingCurrent = fromIndex == index
        val list = queue.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        queue = list
        index = when {
            movingCurrent -> toIndex
            fromIndex < index && toIndex >= index -> index - 1
            fromIndex > index && toIndex <= index -> index + 1
            else -> index
        }
        publishQueue()
    }

    /** Clear the entire queue and stop further playback. */
    fun clearQueue() {
        queue = emptyList()
        index = 0
        publishQueue()
        stop()
    }

    private fun publishQueue() {
        _state.update { it.copy(queue = queue, queueIndex = index) }
    }

    /** Jump to a specific index in the current local queue. */
    fun playIndex(targetIndex: Int) {
        if (!_state.value.enabled) {
            _state.update { it.copy(error = "Enable experimental local playback first.") }
            return
        }
        if (queue.isEmpty()) return
        val nextIndex = targetIndex.coerceIn(0, queue.lastIndex)
        if (nextIndex == index && playback.state == LibrespotEngine.STATE_PLAYING) return
        index = nextIndex
        userStopped = false
        if (playback.isConnected) startCurrent() else connectAndStart()
    }

    fun previous() {
        if (isRemotePlayback()) {
            scope.launch(Dispatchers.IO) { runCatching { playback.previous() } }
            return
        }
        if (queue.isEmpty()) return
        if (playback.positionMs > RESTART_THRESHOLD_MS && playback.isConnected) {
            seekTo(0)
            return
        }
        if (index > 0) index--
        userStopped = false
        startCurrent()
    }

    fun seekTo(positionMs: Int) {
        if (!playback.isConnected) return
        val target = positionMs.coerceAtLeast(0)
        _state.update { it.copy(positionMs = target) }
        scope.launch(Dispatchers.IO) {
            runCatching { playback.seekTo(target) }
        }
    }

    /**
     * Deletes the stored librespot credentials. The live session keeps its
     * token until the process restarts; the next connect will need the browser.
     */
    fun forgetAccount() {
        userStopped = true
        playJob?.cancel()
        playJob = null
        ticker?.cancel()
        ticker = null
        prefs.edit().putBoolean(KEY_KEEP_CONNECTED, false).apply()
        abandonFocus()
        LocalPlaybackService.stop(appContext)
        _state.update {
            it.copy(
                isPlaying = false,
                connected = false,
                connecting = false,
                needsAuth = true,
                title = "",
                artist = "",
                artUrl = null,
                durationMs = 0,
                positionMs = 0,
                error = null,
                keepConnected = false,
            )
        }
        scope.launch(Dispatchers.IO) {
            val old = playback
            runCatching { old.stop() }
            runCatching { old.close() }
            playback = LibrespotPlayback(appContext, deviceName = deviceName(), bufferSeconds = bufferSeconds())
            playback.forgetAccount()
        }
    }

    private fun connectAndStart() {
        if (playback.isConnected) {
            startCurrent()
            return
        }
        if (connectJob?.isActive == true) return
        _state.update { it.copy(connecting = true, error = null, needsAuth = false) }
        connectJob = scope.launch {
            var connected = connectOnce(allowInteractive = true)
            if (!connected) {
                delay(750)
                connected = connectOnce(allowInteractive = false)
            }
            if (!connected) {
                delay(1500)
                connected = connectOnce(allowInteractive = false)
            }
            _state.update {
                it.copy(
                    connecting = false,
                    connected = connected,
                    needsAuth = !connected && !playback.hasStoredCredentials(),
                    error = if (connected) null else {
                        playback.lastConnectError ?: "Could not connect to Spotify."
                    },
                )
            }
            if (connected) {
                if (queue.isNotEmpty()) startCurrent() else {
                    _state.update { it.copy(connected = true, error = null) }
                    startTicker()
                }
            } else {
                LocalPlaybackService.stop(appContext)
            }
        }
    }

    private fun isRemotePlayback(): Boolean {
        val native = playback.currentTrack ?: return false
        if (native.uri.isBlank()) return false
        val local = queue.getOrNull(index)?.uri
        return local.isNullOrBlank() || local != native.uri
    }

    private fun startCurrent() {
        val track = queue.getOrNull(index) ?: return
        playJob?.cancel()
        _state.update {
            it.copy(
                title = track.title,
                artist = track.artist,
                artUrl = track.artUrl,
                durationMs = track.durationMs,
                positionMs = 0,
                isPlaying = false,
                error = null,
                connected = true,
                queueIndex = index,
            )
        }
        playJob = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!playback.load(track.uri)) {
                    false
                } else {
                    runCatching { playback.play() }.isSuccess
                }
            }
            if (!ok) {
                _state.update {
                    it.copy(
                        isPlaying = false,
                        error = playback.lastError ?: "Could not play this track.",
                    )
                }
                return@launch
            }
            if (!requestFocus()) {
                _state.update { it.copy(error = "Another app is using the audio output.") }
            }
            LocalPlaybackService.start(appContext)
            _state.update { it.copy(isPlaying = true) }
            startTicker()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val engineState = playback.state
                val playing = engineState == LibrespotEngine.STATE_PLAYING
                val bufferedSamples = playback.bufferedSamples
                val native = playback.currentTrack
                val remote = native != null && native.uri.isNotBlank() &&
                    (queue.getOrNull(index)?.uri.isNullOrBlank() || queue.getOrNull(index)?.uri != native.uri)
                _state.update {
                    it.copy(
                        connected = playback.isConnected,
                        isPlaying = playing,
                        remote = remote && playback.isConnected,
                        positionMs = playback.audiblePositionMs,
                        title = if (native != null && native.title.isNotBlank()) native.title else it.title,
                        artist = if (native != null && native.artist.isNotBlank()) native.artist else it.artist,
                        artUrl = if (native != null && !native.artUrl.isNullOrBlank()) native.artUrl else it.artUrl,
                        durationMs = if (native != null && native.durationMs > 0) native.durationMs else it.durationMs,
                    )
                }
                if (playing) {
                    lastEngineState = engineState
                } else if (!remote && lastEngineState == LibrespotEngine.STATE_PLAYING &&
                    engineState == LibrespotEngine.STATE_IDLE &&
                    bufferedSamples == 0L &&
                    !userStopped
                ) {
                    // EndOfTrack fires when the decoder finishes, but the sink
                    // may still hold up to ~8 s of audio. Only advance once the
                    // native and AudioTrack buffers are fully drained.
                    lastEngineState = engineState
                    if (index + 1 < queue.size) {
                        index++
                        startCurrent()
                    } else {
                        finishQueue()
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun finishQueue() {
        userStopped = true
        abandonFocus()
        LocalPlaybackService.stop(appContext)
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    init {
        // Auto-register as a Spotify Connect device on app start so another
        // client can find this device without having to start a local track.
        // Only when the user explicitly opted into keep-connected.
        if (_state.value.enabled && _state.value.keepConnected) {
            Log.i(TAG, "controller created with keep-connected on; starting idle Connect")
            connectIdle()
        }
    }

    companion object {
    private const val TAG = "LocalPlayback"
        private const val PREFS = "local_playback"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_KEEP_CONNECTED = "keep_connected"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AUDIO_FOCUS_MODE = "audio_focus_mode"
        private const val KEY_BUFFER_SECONDS = "buffer_seconds"
        private const val TICK_MS = 500L
        private const val RESTART_THRESHOLD_MS = 3_000

        @Volatile
        private var instance: LocalPlaybackController? = null

        fun get(context: Context): LocalPlaybackController =
            instance ?: synchronized(this) {
                instance ?: LocalPlaybackController(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "controller created (enabled=${it._state.value.enabled})")
                }
            }
    }
}
