import React, { useCallback, useEffect, useRef } from 'react';
import { Album } from '../types';
import { AlbumCard } from './AlbumCard';

interface Props {
  albums:      Album[];
  loading:     boolean;
  loadingMore: boolean;
  hasMore:     boolean;
  selected:    Set<string>;
  onSelect:    (id: string) => void;
  allSelected: boolean;
  onToggleAll: () => void;
  onLoadMore:  () => void;
  gridSize?:   number;  // min card width in px (default 160)
}

const SkeletonCard: React.FC = () => (
  <div className="album-card skeleton-card" aria-hidden="true">
    <div className="album-cover skeleton" />
    <div className="album-info">
      <div className="skeleton skeleton-text" style={{ width: '80%' }} />
      <div className="skeleton skeleton-text" style={{ width: '60%' }} />
      <div className="skeleton skeleton-text" style={{ width: '40%' }} />
    </div>
  </div>
);

export const AlbumGrid: React.FC<Props> = React.memo(({
  albums, loading, loadingMore, hasMore,
  selected, onSelect, allSelected, onToggleAll, onLoadMore,
  gridSize = 160,
}) => {
  const sentinelRef  = useRef<HTMLDivElement>(null);
  const loadMoreRef  = useRef(onLoadMore);
  loadMoreRef.current = onLoadMore;

  const gridStyle: React.CSSProperties = {
    gridTemplateColumns: `repeat(auto-fill, minmax(min(${gridSize}px, 100%), 1fr))`,
  };

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return;

    const obs = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting) loadMoreRef.current(); },
      { rootMargin: '400px' }
    );
    obs.observe(sentinel);
    return () => obs.disconnect();
  }, [hasMore]);

  if (loading) {
    return (
      <div className="album-grid" style={gridStyle} aria-busy="true">
        {Array.from({ length: 12 }).map((_, i) => <SkeletonCard key={i} />)}
      </div>
    );
  }

  return (
    <>
      {albums.length > 0 && (
        <div className="select-all-bar">
          <button className="select-all-btn" onClick={onToggleAll}>
            <span className={'select-all-box ' + (allSelected ? 'checked' : '')}>
              {allSelected && (
                <svg viewBox="0 0 12 12" fill="none" width="10" height="10" aria-hidden="true">
                  <path d="M2 6l3 3 5-6" stroke="#000" strokeWidth="1.8"
                        strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              )}
            </span>
            {allSelected ? 'Deselect all' : 'Select all (' + albums.length + ')'}
          </button>
          {selected.size > 0 && (
            <span className="select-count">{selected.size} selected</span>
          )}
        </div>
      )}

      <div className="album-grid" style={gridStyle}>
        {albums.map((album) => (
          <AlbumCard
            key={album.id}
            album={album}
            selected={selected.has(album.id)}
            onSelect={onSelect}
          />
        ))}
      </div>

      {hasMore && (
        <div ref={sentinelRef} className="load-sentinel" aria-hidden="true" />
      )}

      {loadingMore && (
        <div className="album-grid load-more-grid" style={gridStyle} aria-busy="true" aria-label="Loading more albums">
          {Array.from({ length: 6 }).map((_, i) => <SkeletonCard key={i} />)}
        </div>
      )}
    </>
  );
}, (prev, next) =>
  prev.loading     === next.loading     &&
  prev.loadingMore === next.loadingMore &&
  prev.hasMore     === next.hasMore     &&
  prev.allSelected === next.allSelected &&
  prev.albums      === next.albums      &&
  prev.selected    === next.selected    &&
  prev.onSelect    === next.onSelect    &&
  prev.onToggleAll === next.onToggleAll &&
  prev.onLoadMore  === next.onLoadMore  &&
  prev.gridSize    === next.gridSize
);
AlbumGrid.displayName = 'AlbumGrid';
