import React, { useState } from 'react';
import { Header }               from './components/Header';
import { StatsBar }             from './components/StatsBar';
import { FilterBar }            from './components/FilterBar';
import { AlbumGrid }            from './components/AlbumGrid';
import { EmptyState }           from './components/EmptyState';
import { SyncModal }            from './components/SyncModal';
import { LoginScreen }          from './components/LoginScreen';
import { SelectionToolbar }     from './components/SelectionToolbar';
import { PlaylistModal }        from './components/PlaylistModal';
import { PlaylistPicker }       from './components/PlaylistPicker';
import { PlaylistDashboardView } from './components/PlaylistDashboardView';
import { useLibrary, SpotifySyncState } from './hooks/useLibrary';
import { useSpotifyAuth }       from './hooks/useSpotifyAuth';
import { useSelection }         from './hooks/useSelection';
import { useToast }             from './hooks/useToast';
import { useBackupSettings }    from './hooks/useBackupSettings';
import { usePlaylistDashboard } from './hooks/usePlaylistDashboard';
import { addAlbumsToQueue }     from './services/SpotifyPlaylistService';
import { exportBackup }         from './services/ExportService';
import { Album }                from './types';
import { PlaylistAlbum }        from './services/SpotifyPlaylistFeedService';

type ActiveModal = 'sync' | 'playlist' | 'pl-playlist' | null;
type ActiveTab   = 'library' | 'playlists';

// ---------------------------------------------------------------------------
// Sync progress bar
// ---------------------------------------------------------------------------

const STAGE_LABELS: Record<SpotifySyncState['stage'], string> = {
  albums: 'Fetching albums',
  genres: 'Enriching genres',
  saving: 'Saving to library',
};

const SyncProgress: React.FC<{ sync: SpotifySyncState; onCancel: () => void }> = ({ sync, onCancel }) => {
  const pct = sync.total > 0 ? Math.round((sync.fetched / sync.total) * 100) : 0;
  return (
    <div className="spotify-progress">
      <div className="sync-progress-top">
        <span className="spinner" aria-hidden="true" />
        <span className="sync-stage-label">{STAGE_LABELS[sync.stage]}&hellip;</span>
        {sync.total > 0 && (
          <span className="sync-counts">
            <strong>{sync.fetched.toLocaleString()}</strong>{' / '}{sync.total.toLocaleString()}
          </span>
        )}
        <button className="sync-stop-btn" onClick={onCancel} title="Stop sync — saves progress made so far">
          &#x25A0; Stop
        </button>
      </div>
      {sync.total > 0 && (
        <div className="sync-bar-wrap" role="progressbar"
             aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
          <div className="sync-bar-fill" style={{ width: pct + '%' }} />
        </div>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------

export default function App() {
  const auth   = useSpotifyAuth();
  const lib    = useLibrary();
  const sel    = useSelection<Album>(lib.albums);
  const { toast, show: showToast } = useToast();
  const backup = useBackupSettings();
  const pld    = usePlaylistDashboard();

  // Separate selection state for the playlist dashboard
  const plSel = useSelection<PlaylistAlbum>(pld.albums);

  const [modal,     setModal]     = useState<ActiveModal>(null);
  const [activeTab, setActiveTab] = useState<ActiveTab>('library');
  const [queueBusy, setQueueBusy] = useState(false);
  const [plQueueBusy, setPlQueueBusy] = useState(false);
  const [gridSize,  setGridSize]  = useState(160);

  // Switch tabs — always reset scroll position so tabs don't share it
  const switchTab = (tab: ActiveTab) => {
    setActiveTab(tab);
    window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
  };

  // Switch to playlists tab — lazy-load the list
  const handleTabPlaylists = () => {
    switchTab('playlists');
    if (pld.playlists.length === 0 && !pld.playlistsLoading) {
      pld.loadPlaylists();
    }
  };

  // ── Library queue ────────────────────────────────────────────────────────
  const handleQueue = async () => {
    setQueueBusy(true);
    try {
      const { success, failed, error } = await addAlbumsToQueue(sel.selectedAlbums);
      if (success === 0) showToast(error ?? 'Open Spotify on a device first', false);
      else { showToast('Added ' + success + ' tracks to queue' + (failed ? ' (' + failed + ' failed)' : '')); sel.clear(); }
    } catch (e: any) {
      showToast(e?.message ?? 'Queue failed', false);
    } finally { setQueueBusy(false); }
  };

  // ── Playlist-dashboard queue ─────────────────────────────────────────────
  const handlePlQueue = async () => {
    setPlQueueBusy(true);
    try {
      // PlaylistAlbum is structurally compatible with the Album id field addAlbumsToQueue needs
      const { success, failed, error } = await addAlbumsToQueue(plSel.selectedAlbums as unknown as Album[]);
      if (success === 0) showToast(error ?? 'Open Spotify on a device first', false);
      else { showToast('Added ' + success + ' tracks to queue' + (failed ? ' (' + failed + ' failed)' : '')); plSel.clear(); }
    } catch (e: any) {
      showToast(e?.message ?? 'Queue failed', false);
    } finally { setPlQueueBusy(false); }
  };

  const handleSyncSpotify = async (clean: boolean) => {
    const result = await lib.syncFromSpotify(clean);
    if (backup.settings.enabled && (result.imported > 0 || clean)) {
      try {
        const br = await exportBackup(lib.albums, backup.settings.subdirName);
        if (br) {
          backup.recordBackup(br.path);
          showToast('Backup saved' + (br.bytes > 0 ? ' (' + Math.round(br.bytes / 1024) + ' KB)' : ''));
        }
      } catch (e: any) { showToast('Backup failed: ' + (e?.message ?? e), false); }
    }
    return result;
  };

  const handleExportNow = async () => {
    const br = await exportBackup(lib.albums, backup.settings.subdirName);
    if (br) {
      backup.recordBackup(br.path);
      showToast('Backup saved' + (br.bytes > 0 ? ' (' + Math.round(br.bytes / 1024) + ' KB)' : ''));
    }
  };

  const handlePlaylistExport = async () => {
    try {
      const result = await pld.exportCSV();
      if (result) {
        showToast(result.path === '(browser download)'
          ? 'CSV downloaded'
          : 'Saved: ' + result.path.split('/').pop());
      }
    } catch (e: any) { showToast(e?.message ?? 'Export failed', false); }
  };

  if (auth.authState === 'loading') return <Splash />;
  if (auth.authState === 'unauthenticated') {
    return <LoginScreen onLogin={auth.login} error={auth.error} />;
  }

  const isBusy          = lib.syncing || lib.spotifySync.active || queueBusy;
  const hasActiveFilter = !!(lib.filters.search || lib.filters.genre || lib.filters.artist ||
                              lib.filters.yearFrom !== '' || lib.filters.yearTo !== '');
  const showLibFilters  = lib.dbReady && (lib.albums.length > 0 || hasActiveFilter || lib.genres.length > 0);

  return (
    <div className={'app ' + (sel.selected.size > 0 ? 'app--has-selection' : '')}>
      <Header
        stats={lib.stats}
        onSyncClick={() => setModal('sync')}
        onLogout={auth.logout}
        syncing={isBusy}
        activeTab={activeTab}
      />

      <main className="main-content">
        {/* ── Library tab ─────────────────────────────────────── */}
        {activeTab === 'library' && (
          <>
            {lib.error && <div className="error-banner" role="alert">&#x26A0; {lib.error}</div>}
            {lib.spotifySync.active && <SyncProgress sync={lib.spotifySync} onCancel={lib.cancelSync} />}
            {lib.stats && <StatsBar stats={lib.stats} />}
            {showLibFilters && (
              <FilterBar
                filters={lib.filters}
                genres={lib.genres}
                artists={lib.artists}
                years={lib.years}
                total={lib.total}
                gridSize={gridSize}
                onFilterChange={lib.updateFilter}
                onReset={lib.resetFilters}
                onGridSizeChange={setGridSize}
              />
            )}
            {!lib.loading && lib.albums.length === 0
              ? <EmptyState hasSearch={hasActiveFilter} onImport={() => setModal('sync')} />
              : <AlbumGrid
                  albums={lib.albums} loading={lib.loading}
                  loadingMore={lib.loadingMore} hasMore={lib.hasMore}
                  selected={sel.selected} onSelect={sel.toggle}
                  allSelected={sel.allSelected} onToggleAll={sel.toggleAll}
                  onLoadMore={lib.loadMore}
                  gridSize={gridSize} />
            }
          </>
        )}

        {/* ── Playlists tab ────────────────────────────────────── */}
        {activeTab === 'playlists' && (
          <>
            {pld.selectedPlaylist ? (
              <PlaylistDashboardView
                playlist={pld.selectedPlaylist}
                meta={pld.playlistMeta}
                albums={pld.albums}
                total={pld.total}
                genres={pld.genres}
                artists={pld.artists}
                years={pld.years}
                syncState={pld.syncState}
                loading={pld.loading}
                loadingMore={pld.loadingMore}
                hasMore={pld.hasMore}
                filters={pld.filters}
                gridSize={gridSize}
                selected={plSel.selected}
                selectedAlbums={plSel.selectedAlbums}
                queueBusy={plQueueBusy}
                onBack={() => { pld.clearPlaylist(); plSel.clear(); }}
                onCancel={pld.cancelFetch}
                onResync={() => pld.syncPlaylist(pld.selectedPlaylist!)}
                onExport={handlePlaylistExport}
                onFilter={pld.updateFilter}
                onReset={pld.resetFilters}
                onLoadMore={pld.loadMore}
                onGridSizeChange={setGridSize}
                onSelect={plSel.toggle}
                onClearSelection={plSel.clear}
                onQueue={handlePlQueue}
                onAddToPlaylist={() => setModal('pl-playlist')}
              />
            ) : (
              <PlaylistPicker
                playlists={pld.playlists}
                loading={pld.playlistsLoading}
                error={pld.playlistsError}
                cachedIds={pld.cachedIds}
                onSelect={pld.selectPlaylist}
                onRefresh={pld.loadPlaylists}
              />
            )}
          </>
        )}
      </main>

      {/* ── Bottom tab bar ─────────────────────────────────────── */}
      <nav className="bottom-tabs" aria-label="Main navigation">
        <button
          className={'bottom-tab ' + (activeTab === 'library' ? 'active' : '')}
          onClick={() => switchTab('library')}
          aria-current={activeTab === 'library' ? 'page' : undefined}
        >
          <svg viewBox="0 0 24 24" fill="none" width="22" height="22" aria-hidden="true">
            <rect x="3" y="3" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8"/>
            <rect x="14" y="3" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8"/>
            <rect x="3" y="14" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8"/>
            <rect x="14" y="14" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8"/>
          </svg>
          <span>Library</span>
        </button>

        <button
          className={'bottom-tab ' + (activeTab === 'playlists' ? 'active' : '')}
          onClick={handleTabPlaylists}
          aria-current={activeTab === 'playlists' ? 'page' : undefined}
        >
          <svg viewBox="0 0 24 24" fill="none" width="22" height="22" aria-hidden="true">
            <path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"
                  stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
          </svg>
          <span>Playlists</span>
        </button>
      </nav>

      {/* Library selection toolbar */}
      <SelectionToolbar selectedAlbums={sel.selectedAlbums} onQueue={handleQueue}
                        onPlaylist={() => setModal('playlist')} onClear={sel.clear}
                        busy={queueBusy} />

      {toast && (
        <div className={'toast ' + (toast.ok ? 'toast--ok' : 'toast--err')} role="status">
          {toast.msg}
        </div>
      )}

      {modal === 'sync' && (
        <SyncModal
          onSyncCSV={lib.syncCSV}
          onSyncSpotify={handleSyncSpotify}
          onExportNow={handleExportNow}
          onClose={() => setModal(null)}
          syncing={isBusy}
          backup={backup.settings}
          onToggleBackup={backup.toggle}
          onSetSubdir={backup.setSubdir}
        />
      )}

      {modal === 'playlist' && (
        <PlaylistModal selectedAlbums={sel.selectedAlbums}
                       onClose={() => { setModal(null); sel.clear(); }}
                       onDone={showToast} />
      )}

      {/* Playlist-dashboard → add selected albums to an existing/new playlist */}
      {modal === 'pl-playlist' && (
        <PlaylistModal selectedAlbums={plSel.selectedAlbums as unknown as Album[]}
                       onClose={() => { setModal(null); plSel.clear(); }}
                       onDone={showToast} />
      )}
    </div>
  );
}

const Splash: React.FC = () => (
  <div className="splash">
    <svg viewBox="0 0 64 64" fill="none" width="64" height="64" aria-hidden="true">
      <circle cx="32" cy="32" r="32" fill="#1DB954" />
    </svg>
  </div>
);
