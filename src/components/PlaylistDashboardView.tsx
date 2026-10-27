import React, { useEffect, useRef, useCallback } from 'react';
import { PlaylistAlbum } from '../services/SpotifyPlaylistFeedService';
import { SpotifyPlaylist } from '../services/SpotifyPlaylistService';
import { StoredPlaylistMeta } from '../services/PlaylistDatabaseService';
import { PlaylistSyncState } from '../hooks/usePlaylistDashboard';
import { FilterBar } from './FilterBar';
import { AlbumCard } from './AlbumCard';
import { SelectionToolbar } from './SelectionToolbar';
import { FilterState } from '../types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function timeAgo(iso: string): string {
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60)    return 'just now';
  if (diff < 3600)  return Math.floor(diff / 60) + 'm ago';
  if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
  return Math.floor(diff / 86400) + 'd ago';
}

// ---------------------------------------------------------------------------
// Progress banner
// ---------------------------------------------------------------------------

const STAGE_LABELS = { tracks: 'Fetching tracks', genres: 'Enriching genres', idle: '' };

const FetchProgress: React.FC<{ state: PlaylistSyncState; onCancel: () => void }> = ({ state, onCancel }) => {
  const pct = state.total > 0 ? Math.round((state.fetched / state.total) * 100) : 0;
  return (
    <div className="spotify-progress">
      <div className="sync-progress-top">
        <span className="spinner" aria-hidden="true" />
        <span className="sync-stage-label">{STAGE_LABELS[state.stage]}&hellip;</span>
        {state.total > 0 && (
          <span className="sync-counts">
            <strong>{state.fetched.toLocaleString()}</strong>{' / '}{state.total.toLocaleString()}
          </span>
        )}
        <button className="sync-stop-btn" onClick={onCancel}>&#x25A0; Stop</button>
      </div>
      {state.total > 0 && (
        <div className="sync-bar-wrap" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
          <div className="sync-bar-fill" style={{ width: pct + '%' }} />
        </div>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------
// Skeleton card
// ---------------------------------------------------------------------------

const SkeletonCard: React.FC = () => (
  <div className="album-card skeleton-card" aria-hidden="true">
    <div className="album-cover skeleton" />
    <div className="album-info">
      <div className="skeleton skeleton-text" style={{ width: '80%' }} />
      <div className="skeleton skeleton-text" style={{ width: '60%' }} />
    </div>
  </div>
);

// ---------------------------------------------------------------------------
// Album grid with sentinel
// ---------------------------------------------------------------------------

interface GridProps {
  albums:      PlaylistAlbum[];
  loading:     boolean;
  loadingMore: boolean;
  hasMore:     boolean;
  onLoadMore:  () => void;
  gridSize:    number;
  selected:    Set<string>;
  onSelect:    (id: string) => void;
}

const PlaylistAlbumGrid: React.FC<GridProps> = React.memo(({
  albums, loading, loadingMore, hasMore, onLoadMore, gridSize, selected, onSelect,
}) => {
  const sentinelRef = useRef<HTMLDivElement>(null);
  const loadMoreRef = useRef(onLoadMore);
  loadMoreRef.current = onLoadMore;

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const obs = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting) loadMoreRef.current(); },
      { rootMargin: '400px' }
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, [hasMore]);

  const gridStyle = {
    gridTemplateColumns: `repeat(auto-fill, minmax(${gridSize}px, 1fr))`,
  };

  if (loading) {
    return (
      <div className="album-grid" style={gridStyle} aria-busy="true">
        {Array.from({ length: 12 }).map((_, i) => <SkeletonCard key={i} />)}
      </div>
    );
  }

  return (
    <>
      <div className="album-grid" style={gridStyle}>
        {albums.map((a) => (
          <AlbumCard
            key={a.id}
            album={a}
            selected={selected.has(a.id)}
            onSelect={onSelect}
            badge={a.trackCount > 0
              ? a.trackCount + (a.trackCount === 1 ? ' track' : ' tracks')
              : undefined}
          />
        ))}
      </div>
      {hasMore && <div ref={sentinelRef} className="load-sentinel" aria-hidden="true" />}
      {loadingMore && (
        <div className="album-grid load-more-grid" style={gridStyle} aria-busy="true">
          {Array.from({ length: 4 }).map((_, i) => <SkeletonCard key={i} />)}
        </div>
      )}
    </>
  );
});
PlaylistAlbumGrid.displayName = 'PlaylistAlbumGrid';

// ---------------------------------------------------------------------------
// Main view
// ---------------------------------------------------------------------------

interface Props {
  playlist:         SpotifyPlaylist;
  meta:             StoredPlaylistMeta | null;
  albums:           PlaylistAlbum[];
  total:            number;
  genres:           string[];
  artists:          string[];
  years:            number[];
  syncState:        PlaylistSyncState;
  loading:          boolean;
  loadingMore:      boolean;
  hasMore:          boolean;
  filters:          FilterState;
  gridSize:         number;
  selected:         Set<string>;
  selectedAlbums:   PlaylistAlbum[];
  queueBusy:        boolean;
  onBack:           () => void;
  onCancel:         () => void;
  onResync:         () => void;
  onExport:         () => void;
  onFilter:         <K extends keyof FilterState>(key: K, value: FilterState[K]) => void;
  onReset:          () => void;
  onLoadMore:       () => void;
  onGridSizeChange: (size: number) => void;
  onSelect:         (id: string) => void;
  onClearSelection: () => void;
  onQueue:          () => void;
  onAddToPlaylist:  () => void;
}

export const PlaylistDashboardView: React.FC<Props> = ({
  playlist, meta, albums, total, genres, artists, years,
  syncState, loading, loadingMore, hasMore, filters, gridSize,
  selected, selectedAlbums, queueBusy,
  onBack, onCancel, onResync, onExport, onFilter, onReset, onLoadMore, onGridSizeChange,
  onSelect, onClearSelection, onQueue, onAddToPlaylist,
}) => {
  const coverUrl  = playlist.images?.[0]?.url;
  const isSyncing = syncState.active;

  return (
    <div className={'pl-dashboard' + (selected.size > 0 ? ' app--has-selection' : '')}>
      {/* ── Header ── */}
      <div className="pl-dash-header">
        <button className="pl-back-btn" onClick={onBack} aria-label="Back to playlists">
          <svg viewBox="0 0 20 20" fill="none" width="18" height="18" aria-hidden="true">
            <path d="M13 4l-7 8 7 8" stroke="currentColor" strokeWidth="2"
                  strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        </button>

        {coverUrl && (
          <img src={coverUrl} alt="" aria-hidden="true" className="pl-dash-cover" loading="lazy" />
        )}

        <div className="pl-dash-info">
          <p className="pl-dash-name">{playlist.name}</p>
          <p className="pl-dash-meta">
            {meta ? meta.album_count + ' albums · ' : ''}{playlist.tracks.total} tracks
            {meta?.synced_at && <span className="pl-synced-at"> · {timeAgo(meta.synced_at)}</span>}
          </p>
        </div>

        {/* Actions */}
        <div className="pl-dash-actions">
          <button
            className={'pl-action-btn' + (isSyncing ? ' active' : '')}
            onClick={isSyncing ? onCancel : onResync}
            aria-label={isSyncing ? 'Stop sync' : 'Re-sync from Spotify'}
            title={isSyncing ? 'Stop' : 'Re-sync'}
          >
            {isSyncing
              ? <span className="spinner" aria-hidden="true" />
              : <span className={loading ? 'spin' : ''}>&#x27F3;</span>
            }
          </button>

          <button
            className="pl-action-btn"
            onClick={onExport}
            disabled={!meta || meta.album_count === 0}
            aria-label="Export CSV"
            title="Export CSV"
          >
            <svg viewBox="0 0 20 20" fill="none" width="17" height="17" aria-hidden="true">
              <path d="M10 3v10M6.5 9.5L10 13l3.5-3.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
              <path d="M3 15h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
            </svg>
          </button>
        </div>
      </div>

      {isSyncing && <FetchProgress state={syncState} onCancel={onCancel} />}

      {(meta && meta.album_count > 0) && (
        <FilterBar
          filters={filters}
          genres={genres}
          artists={artists}
          years={years}
          total={total}
          gridSize={gridSize}
          onFilterChange={onFilter}
          onReset={onReset}
          onGridSizeChange={onGridSizeChange}
        />
      )}

      {albums.length > 0 || loading ? (
        <PlaylistAlbumGrid
          albums={albums}
          loading={loading}
          loadingMore={loadingMore}
          hasMore={hasMore}
          onLoadMore={onLoadMore}
          gridSize={gridSize}
          selected={selected}
          onSelect={onSelect}
        />
      ) : !isSyncing && (
        <div className="empty-state"><p>No albums found.</p></div>
      )}

      <SelectionToolbar
        selectedAlbums={selectedAlbums}
        onQueue={onQueue}
        onPlaylist={onAddToPlaylist}
        onClear={onClearSelection}
        busy={queueBusy}
      />
    </div>
  );
};
