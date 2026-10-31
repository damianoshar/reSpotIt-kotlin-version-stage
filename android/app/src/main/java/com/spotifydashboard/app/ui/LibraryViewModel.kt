package com.spotifydashboard.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.spotifydashboard.app.repository.SpotifyRepository
import com.spotifydashboard.app.repository.SyncState
import com.spotifydashboard.app.data.AlbumEntity
import com.spotifydashboard.app.data.FilterState
import com.spotifydashboard.app.data.LibraryStats

class LibraryViewModel(private val repository: SpotifyRepository) : ViewModel() {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    private val _filters = MutableStateFlow(FilterState())
    val filters: StateFlow<FilterState> = _filters.asStateFlow()
    
    private val _albums = MutableStateFlow<List<AlbumEntity>>(emptyList())
    val albums: StateFlow<List<AlbumEntity>> = _albums.asStateFlow()
    
    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()
    
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    
    private val _genres = MutableStateFlow<List<String>>(emptyList())
    val genres: StateFlow<List<String>> = _genres.asStateFlow()
    
    private val _artists = MutableStateFlow<List<String>>(emptyList())
    val artists: StateFlow<List<String>> = _artists.asStateFlow()
    
    private val _years = MutableStateFlow<List<Int>>(emptyList())
    val years: StateFlow<List<Int>> = _years.asStateFlow()
    
    private val _stats = MutableStateFlow<LibraryStats?>(null)
    val stats: StateFlow<LibraryStats?> = _stats.asStateFlow()
    
    private var currentOffset = 0
    private val PAGE_SIZE = 48

    init {
        loadFilterOptions()
        runQuery()
    }

    private fun loadFilterOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            _genres.value = repository.getDistinctGenres()
            _artists.value = repository.getDistinctArtists()
            _years.value = repository.getDistinctYears()
            _stats.value = repository.getStats()
        }
    }

    fun runQuery() {
        viewModelScope.launch {
            _loading.value = true
            currentOffset = 0
            
            val f = _filters.value
            val result = repository.getAlbumsFlow(
                f.search, f.genre, f.artist, f.albumType, f.yearFrom, f.yearTo,
                f.sortField, f.sortOrder, PAGE_SIZE, 0
            ).first()
            
            val count = withContext(Dispatchers.IO) {
                repository.getAlbumsFlow(f.search, f.genre, f.artist, f.albumType, f.yearFrom, f.yearTo, f.sortField, f.sortOrder, 10000, 0).first().size
            }
            
            _albums.value = result
            _total.value = count
            _hasMore.value = result.size < count
            currentOffset = result.size
            _loading.value = false
        }
    }

    fun loadMore() {
        if (_hasMore.value) {
            viewModelScope.launch {
                val f = _filters.value
                val more = repository.getAlbumsFlow(
                    f.search, f.genre, f.artist, f.albumType, f.yearFrom, f.yearTo,
                    f.sortField, f.sortOrder, PAGE_SIZE, currentOffset
                ).first()
                
                _albums.value = _albums.value + more
                currentOffset += more.size
                _hasMore.value = more.size == PAGE_SIZE
            }
        }
    }

    fun updateFilter(update: FilterState.() -> FilterState) {
        _filters.value = _filters.value.update()
        runQuery()
    }

    private var syncSignal: com.spotifydashboard.app.repository.SyncCancelSignal? = null

    fun startSync(clean: Boolean = false) {
        val signal = com.spotifydashboard.app.repository.SyncCancelSignal()
        syncSignal = signal
        viewModelScope.launch {
            repository.syncLibrary(signal, clean) { state ->
                _syncState.value = state
                if (state is com.spotifydashboard.app.repository.SyncState.Completed ||
                    state is com.spotifydashboard.app.repository.SyncState.Cancelled
                ) {
                    loadFilterOptions()
                    runQuery()
                }
            }
        }
    }

    fun cancelSync() {
        val signal = syncSignal
        if (signal != null) signal.cancelled = true
        else _syncState.value = SyncState.Idle
    }
}
