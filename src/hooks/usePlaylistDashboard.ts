import { useState, useEffect, useCallback, useRef } from 'react';
import { SpotifyPlaylist, getUserPlaylists } from '../services/SpotifyPlaylistService';
import { fetchPlaylistAlbums, PlaylistAlbum, PlaylistFetchProgress } from '../services/SpotifyPlaylistFeedService';
import {
  savePlaylistAlbums, loadPlaylistAlbums, countPlaylistAlbums,
  getPlaylistMeta, getCachedPlaylistIds, getPlaylistFilterOptions,
  exportPlaylistCSV, StoredPlaylistMeta,
} from '../services/PlaylistDatabaseService';
import { CancellationToken, SyncCancelledError } from '../services/SpotifyApiService';
import { FilterState } from '../types';
import { PAGE_SIZE } from '../services/DatabaseService';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function yearStr(v: number | ''): string { return v === '' ? '' : String(v); }

function useDebounce<T>(value: T, delay: number): T {
  const [d, setD] = useState<T>(value);
  useEffect(() => {
    const t = setTimeout(() => setD(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return d;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const DEFAULT_FILTER: FilterState = {
  search: '', genre: '', artist: '', yearFrom: '', yearTo: '',
  sortField: 'artist', sortOrder: 'asc',
};

export interface PlaylistSyncState {
  active:  boolean;
  stage:   'tracks' | 'genres' | 'idle';
  fetched: number;
  total:   number;
}
const IDLE: PlaylistSyncState = { active: false, stage: 'idle', fetched: 0, total: 0 };

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function usePlaylistDashboard() {
  // ── Playlist list ─────────────────────────────────────────────────────────
  const [playlists,        setPlaylists]        = useState<SpotifyPlaylist[]>([]);
  const [playlistsLoading, setPlaylistsLoading] = useState(false);
  const [playlistsError,   setPlaylistsError]   = useState('');
  const [cachedIds,        setCachedIds]        = useState<Set<string>>(new Set());

  // ── Selected playlist state ───────────────────────────────────────────────
  const [selectedPlaylist, setSelectedPlaylist] = useState<SpotifyPlaylist | null>(null);
  const [playlistMeta,     setPlaylistMeta]     = useState<StoredPlaylistMeta | null>(null);

  // Paginated album state (DB-backed, mirrors library)
  const [albums,      setAlbums]      = useState<PlaylistAlbum[]>([]);
  const [total,       setTotal]       = useState(0);
  const [hasMore,     setHasMore]     = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const offsetRef = useRef(0);

  const [genres,  setGenres]  = useState<string[]>([]);
  const [artists, setArtists] = useState<string[]>([]);
  const [years,   setYears]   = useState<number[]>([]);

  const [loading,   setLoading]   = useState(false);
  const [syncState, setSyncState] = useState<PlaylistSyncState>(IDLE);

  const [filters, setFilters] = useState<FilterState>(DEFAULT_FILTER);
  const dFilters = useDebounce(filters, 250);

  const cancelRef = useRef<CancellationToken>({ cancelled: false });

  // ── Load list of playlists ────────────────────────────────────────────────
  const loadPlaylists = useCallback(async () => {
    setPlaylistsLoading(true); setPlaylistsError('');
    try {
      const [list, ids] = await Promise.all([getUserPlaylists(), getCachedPlaylistIds()]);
      setPlaylists(list);
      setCachedIds(ids);
    } catch (e: any) {
      setPlaylistsError(e?.message ?? 'Failed to load playlists');
    } finally {
      setPlaylistsLoading(false);
    }
  }, []);

  // ── Core DB query (resets list) ───────────────────────────────────────────
  const runQuery = useCallback(async (playlistId: string, f: FilterState) => {
    setLoading(true);
    offsetRef.current = 0;
    try {
      const [first, cnt, opts, meta] = await Promise.all([
        loadPlaylistAlbums(playlistId, f.search, f.genre, f.sortField, f.sortOrder,
                           yearStr(f.yearFrom), yearStr(f.yearTo), f.artist, PAGE_SIZE, 0),
        countPlaylistAlbums(playlistId, f.search, f.genre, yearStr(f.yearFrom), yearStr(f.yearTo), f.artist),
        getPlaylistFilterOptions(playlistId),
        getPlaylistMeta(playlistId),
      ]);
      offsetRef.current = first.length;
      setAlbums(first); setTotal(cnt); setHasMore(first.length < cnt);
      setGenres(opts.genres); setArtists(opts.artists); setYears(opts.years);
      setPlaylistMeta(meta);
    } finally {
      setLoading(false);
    }
  }, []);

  // Re-run query when debounced filters change (only if a playlist is selected)
  const selectedIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (selectedIdRef.current) runQuery(selectedIdRef.current, dFilters);
  }, [dFilters, runQuery]);

  // ── Load more (DB pagination) ─────────────────────────────────────────────
  const loadMore = useCallback(async () => {
    if (!hasMore || loadingMore || loading || !selectedIdRef.current) return;
    setLoadingMore(true);
    try {
      const f    = dFilters;
      const more = await loadPlaylistAlbums(
        selectedIdRef.current, f.search, f.genre, f.sortField, f.sortOrder,
        yearStr(f.yearFrom), yearStr(f.yearTo), f.artist, PAGE_SIZE, offsetRef.current
      );
      if (!more.length) { setHasMore(false); return; }
      offsetRef.current += more.length;
      setAlbums((prev) => [...prev, ...more]);
      setHasMore(offsetRef.current < total);
    } finally {
      setLoadingMore(false);
    }
  }, [hasMore, loadingMore, loading, dFilters, total]);

  // ── Select a playlist: load from DB first, auto-sync if not cached ────────
  const selectPlaylist = useCallback(async (playlist: SpotifyPlaylist) => {
    selectedIdRef.current = playlist.id;
    setSelectedPlaylist(playlist);
    setFilters(DEFAULT_FILTER);
    setAlbums([]); setTotal(0); setHasMore(false);

    const meta = await getPlaylistMeta(playlist.id);
    if (meta && meta.album_count > 0) {
      // Cached — load from DB immediately
      await runQuery(playlist.id, DEFAULT_FILTER);
    } else {
      // Not cached — auto-sync from Spotify
      await syncPlaylist(playlist);
    }
  }, [runQuery]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Sync / re-sync from Spotify ───────────────────────────────────────────
  const syncPlaylist = useCallback(async (playlist: SpotifyPlaylist) => {
    const token: CancellationToken = { cancelled: false };
    cancelRef.current = token;
    selectedIdRef.current = playlist.id;
    setSyncState({ active: true, stage: 'tracks', fetched: 0, total: playlist.tracks.total });

    try {
      const fetched = await fetchPlaylistAlbums(
        playlist.id,
        (p: PlaylistFetchProgress) => setSyncState({ active: true, ...p }),
        token,
      );
      await savePlaylistAlbums(
        playlist.id,
        { name: playlist.name, image_url: playlist.images?.[0]?.url ?? '', track_count: playlist.tracks.total },
        fetched,
      );
      setCachedIds((prev) => new Set([...prev, playlist.id]));
      await runQuery(playlist.id, DEFAULT_FILTER);
      setFilters(DEFAULT_FILTER);
    } catch (e) {
      if (e instanceof SyncCancelledError) {
        // Partial data may exist — reload what's there
        if (selectedIdRef.current) await runQuery(selectedIdRef.current, DEFAULT_FILTER);
      } else throw e;
    } finally {
      setSyncState(IDLE);
    }
  }, [runQuery]);

  const cancelFetch = useCallback(() => { cancelRef.current.cancelled = true; }, []);

  const clearPlaylist = useCallback(() => {
    cancelRef.current.cancelled = true;
    selectedIdRef.current = null;
    setSelectedPlaylist(null); setPlaylistMeta(null);
    setAlbums([]); setTotal(0); setHasMore(false);
    setSyncState(IDLE); setFilters(DEFAULT_FILTER);
  }, []);

  // ── Export CSV ────────────────────────────────────────────────────────────
  const exportCSV = useCallback(async (): Promise<{ path: string; bytes: number } | null> => {
    if (!selectedPlaylist) return null;
    return exportPlaylistCSV(selectedPlaylist.id, selectedPlaylist.name);
  }, [selectedPlaylist]);

  // ── Filters ───────────────────────────────────────────────────────────────
  const updateFilter = useCallback(<K extends keyof FilterState>(key: K, value: FilterState[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }, []);
  const resetFilters = useCallback(() => setFilters(DEFAULT_FILTER), []);

  return {
    playlists, playlistsLoading, playlistsError, cachedIds, loadPlaylists,
    selectedPlaylist, playlistMeta, selectPlaylist, clearPlaylist,
    syncPlaylist, cancelFetch, syncState,
    albums, total, hasMore, loadingMore, loading,
    genres, artists, years,
    filters, updateFilter, resetFilters, loadMore,
    exportCSV,
  };
}
