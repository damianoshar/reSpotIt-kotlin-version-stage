import React from 'react';

// Accepts any object with an id — works for both Album and PlaylistAlbum
interface Props {
  selectedAlbums: { id: string }[];
  onQueue:        () => void;
  onPlaylist:     () => void;
  onClear:        () => void;
  busy:           boolean;
}

export const SelectionToolbar: React.FC<Props> = ({
  selectedAlbums, onQueue, onPlaylist, onClear, busy,
}) => {
  const count = selectedAlbums.length;
  if (count === 0) return null;

  return (
    <div className="selection-toolbar" role="toolbar" aria-label="Selection actions">
      <span className="selection-count">
        <strong>{count}</strong> album{count !== 1 ? 's' : ''} selected
      </span>

      <div className="selection-actions">
        <button
          className="sel-btn sel-btn--queue"
          onClick={onQueue}
          disabled={busy}
          aria-label="Add to queue"
          title="Add to Spotify queue (requires active device)"
        >
          {busy ? <span className="spinner" aria-hidden="true" /> : (
            <svg viewBox="0 0 20 20" fill="none" width="16" height="16" aria-hidden="true">
              <path d="M3 5h14M3 10h10M3 15h7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
              <circle cx="16" cy="14" r="3" stroke="currentColor" strokeWidth="1.5"/>
              <path d="M16 12v2l1 1" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/>
            </svg>
          )}
          Queue
        </button>

        <button
          className="sel-btn sel-btn--playlist"
          onClick={onPlaylist}
          disabled={busy}
          aria-label="Add to playlist"
        >
          {busy ? <span className="spinner" aria-hidden="true" /> : (
            <svg viewBox="0 0 20 20" fill="none" width="16" height="16" aria-hidden="true">
              <path d="M3 5h14M3 10h14M3 15h8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
              <path d="M14 13v4m-2-2h4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
            </svg>
          )}
          Playlist
        </button>

        <button className="sel-btn sel-btn--clear" onClick={onClear} aria-label="Clear selection">
          ✕
        </button>
      </div>
    </div>
  );
};
