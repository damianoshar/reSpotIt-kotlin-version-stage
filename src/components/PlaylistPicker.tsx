import React, { useState, useMemo } from 'react';
import { SpotifyPlaylist } from '../services/SpotifyPlaylistService';

interface Props {
  playlists:  SpotifyPlaylist[];
  loading:    boolean;
  error:      string;
  cachedIds:  Set<string>;
  onSelect:   (p: SpotifyPlaylist) => void;
  onRefresh:  () => void;
}

const SkeletonCard: React.FC = () => (
  <div className="pl-card pl-card--skeleton" aria-hidden="true">
    <div className="pl-card-img skeleton" />
    <div className="pl-card-info">
      <div className="skeleton skeleton-text" style={{ width: '75%' }} />
      <div className="skeleton skeleton-text" style={{ width: '45%' }} />
    </div>
  </div>
);

export const PlaylistPicker: React.FC<Props> = ({
  playlists, loading, error, cachedIds, onSelect, onRefresh,
}) => {
  const [search, setSearch] = useState('');

  const filtered = useMemo(() =>
    search.trim()
      ? playlists.filter((p) => p.name.toLowerCase().includes(search.toLowerCase()))
      : playlists,
    [playlists, search]
  );

  const cachedCount = playlists.filter((p) => cachedIds.has(p.id)).length;

  return (
    <div className="pl-picker">
      <div className="pl-picker-header">
        <div>
          <h2 className="pl-picker-title">Your Playlists</h2>
          {cachedCount > 0 && (
            <p className="pl-picker-subtitle">
              {cachedCount} saved offline
            </p>
          )}
        </div>
        <button className="pl-refresh-btn" onClick={onRefresh} disabled={loading} aria-label="Refresh">
          {loading
            ? <span className="spinner" aria-hidden="true" />
            :<span className={loading ? 'spin' : ''}>&#x27F3;</span>

          }
        </button>
      </div>

      <div className="search-wrap pl-search">
        <svg className="search-icon" viewBox="0 0 20 20" fill="none" width="16" height="16" aria-hidden="true">
          <circle cx="8.5" cy="8.5" r="5.5" stroke="currentColor" strokeWidth="1.8"/>
          <path d="M13 13l4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
        </svg>
        <input
          className="search-input"
          type="search"
          placeholder={'Search ' + playlists.length + ' playlists…'}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          aria-label="Search playlists"
        />
        {search && (
          <button className="search-clear" onClick={() => setSearch('')} aria-label="Clear">&#x2715;</button>
        )}
      </div>

      {error && <div className="error-banner" role="alert">&#x26A0; {error}</div>}

      <div className="pl-grid">
        {loading && playlists.length === 0
          ? Array.from({ length: 8 }).map((_, i) => <SkeletonCard key={i} />)
          : filtered.length === 0
            ? <div className="empty-state"><p>{search ? 'No playlists match.' : 'No playlists found.'}</p></div>
            : filtered.map((p) => (
                <button
                  key={p.id}
                  className={'pl-card' + (cachedIds.has(p.id) ? ' pl-card--cached' : '')}
                  onClick={() => onSelect(p)}
                  aria-label={'Open ' + p.name}
                >
                  <div className="pl-card-img">
                    {p.images?.[0]?.url ? (
                      <img src={p.images[0].url} alt={p.name} loading="lazy" decoding="async" />
                    ) : (
                      <div className="pl-card-img-placeholder" aria-hidden="true">
                        <svg viewBox="0 0 48 48" fill="none" width="32" height="32">
                          <rect width="48" height="48" rx="8" fill="#282828"/>
                          <path d="M14 33c5-3 15-3 20 0" stroke="#1DB954" strokeWidth="2.5" strokeLinecap="round"/>
                          <path d="M12 26c7-4.5 17-4.5 24 0" stroke="#1DB954" strokeWidth="2.5" strokeLinecap="round"/>
                          <path d="M16 19.5c4-2.5 12-2.5 16 0" stroke="#1DB954" strokeWidth="2.5" strokeLinecap="round"/>
                        </svg>
                      </div>
                    )}
                    {/* Saved-offline badge */}
                    {cachedIds.has(p.id) && (
                      <span className="pl-cached-badge" aria-label="Saved offline">
                        <svg viewBox="0 0 12 12" fill="none" width="10" height="10" aria-hidden="true">
                          <path d="M6 1v6M3.5 4.5L6 7l2.5-2.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
                          <path d="M1 9.5h10" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
                        </svg>
                      </span>
                    )}
                  </div>
                  <div className="pl-card-info">
                    <p className="pl-card-name">{p.name}</p>
                    <p className="pl-card-meta">{p.tracks.total} tracks</p>
                  </div>
                </button>
              ))
        }
      </div>
    </div>
  );
};
