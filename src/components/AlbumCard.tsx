import React, { useEffect, useState, useCallback } from 'react';
import { Album } from '../types';
import { resolveImageSrc } from '../services/SyncService';

interface Props {
  album:    Album;
  selected: boolean;
  onSelect: (id: string) => void;
  badge?:   string; // optional overlay label, e.g. "3 tracks"
}

const AlbumCardInner: React.FC<Props> = ({ album, selected, onSelect, badge }) => {
  const [imgSrc,   setImgSrc]   = useState('');
  const [imgError, setImgError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (album.image_path) {
      resolveImageSrc(album.image_path).then((src) => { if (!cancelled) setImgSrc(src); });
    } else if (album.image_url) {
      setImgSrc(album.image_url);
    }
    return () => { cancelled = true; };
  }, [album.image_path, album.image_url]);

  const handleSelect = useCallback(() => onSelect(album.id), [onSelect, album.id]);
  const showPlaceholder = !imgSrc || imgError;

  return (
    <article
      className={'album-card ' + (selected ? 'album-card--selected' : '')}
      aria-label={album.title + ' by ' + album.artist}
      aria-selected={selected}
    >
      <button
        className="album-checkbox"
        onClick={handleSelect}
        aria-label={(selected ? 'Deselect ' : 'Select ') + album.title}
        aria-checked={selected}
        role="checkbox"
      >
        {selected ? (
          <svg viewBox="0 0 20 20" fill="none" width="18" height="18" aria-hidden="true">
            <circle cx="10" cy="10" r="9" fill="var(--accent)" />
            <path d="M6 10l3 3 5-6" stroke="#000" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        ) : (
          <svg viewBox="0 0 20 20" fill="none" width="18" height="18" aria-hidden="true">
            <circle cx="10" cy="10" r="9" stroke="rgba(255,255,255,0.6)" strokeWidth="1.5" fill="rgba(0,0,0,0.3)"/>
          </svg>
        )}
      </button>

      <div className="album-cover">
        {showPlaceholder ? (
          <div className="album-placeholder" aria-hidden="true">
            <svg viewBox="0 0 48 48" fill="none" width="48" height="48">
              <circle cx="24" cy="24" r="22" stroke="#535353" strokeWidth="2" />
              <circle cx="24" cy="24" r="8"  fill="#535353" />
              <circle cx="24" cy="24" r="3"  fill="#121212" />
            </svg>
          </div>
        ) : (
          <img
            src={imgSrc}
            alt={album.title + ' cover'}
            loading="lazy"
            decoding="async"
            onError={() => setImgError(true)}
          />
        )}
        {/* Track count badge (playlist mode) */}
        {badge && <span className="album-card-badge">{badge}</span>}
      </div>

      <div className="album-info">
        <p className="album-title">{album.title}</p>
        <p className="album-artist">{album.artist}</p>
        <p className="album-meta">
          {album.year > 0 ? album.year : ''}
          {album.genre ? ' · ' + album.genre : ''}
        </p>
      </div>
    </article>
  );
};

export const AlbumCard = React.memo(AlbumCardInner, (prev, next) =>
  prev.selected         === next.selected         &&
  prev.album.id         === next.album.id         &&
  prev.album.image_path === next.album.image_path &&
  prev.album.image_url  === next.album.image_url  &&
  prev.badge            === next.badge            &&
  prev.onSelect         === next.onSelect
);
AlbumCard.displayName = 'AlbumCard';
