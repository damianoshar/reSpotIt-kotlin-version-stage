import React from 'react';
import { LibraryStats } from '../types';

interface Props {
  stats:       LibraryStats | null;
  onSyncClick: () => void;
  onLogout:    () => void;
  syncing:     boolean;
  activeTab?:  string;
}

export const Header: React.FC<Props> = ({ stats, onSyncClick, onLogout, syncing }) => (
  <header className="app-header">
    <div className="header-left">
      <svg className="logo-icon" viewBox="0 0 32 32" fill="none" aria-label="Library Dashboard">
        <circle cx="16" cy="16" r="16" fill="#1DB954" />
      </svg>
      <div>
        <h1 className="app-title">Library Dashboard</h1>
        {stats && (
          <p className="app-subtitle">{stats.totalAlbums} albums · {stats.totalArtists} artists</p>
        )}
      </div>
    </div>

    <div className="header-actions">
      <button
        className={`sync-btn ${syncing ? 'syncing' : ''}`}
        onClick={onSyncClick}
        disabled={syncing}
        aria-label="Sync library"
      >
        {syncing ? <><span className="spinner" aria-hidden="true" /> Syncing…</> : <>↑ Sync</>}
      </button>
      <button className="logout-btn" onClick={onLogout} aria-label="Log out">
        ⏏
      </button>
    </div>
  </header>
);
