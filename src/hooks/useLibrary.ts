import { useState, useEffect, useCallback, useRef } from 'react';
import { Album, LibraryStats, FilterState } from '../types';
import {
  initDB, getAllAlbums, countAlbums, getDistinctGenres, getDistinctArtists,
  getDistinctYears, getStats, clearLibrary, upsertAlbum, saveToStore,
  getExistingAlbumIds, PAGE_SIZE,
} from '../services/DatabaseService';
import { importFromCSV, SyncResult, downloadAlbumImage } from '../services/SyncService';
import {
  fetchAllSavedAlbums, FetchProgress, SyncStage,
  CancellationToken, SyncCancelledError,
} from '../services/SpotifyApiService';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function yearStr(val: number | ''): string { return val === '' ? '' : String(val); }

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

export interface SpotifySyncState {
  active:  boolean;
  stage:   SyncStage;
  fetched: number;
  total:   number;
}
const IDLE_SYNC: SpotifySyncState = { active: false, stage: 'albums', fetched: 0, total: 0 };

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useLibrary() {
  const [albums,      setAlbums]      = useState<Album[]>([]);
  const [total,       setTotal]       = useState(0);        // total matching the current filter
  const [hasMore,     setHasMore]     = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const offsetRef = useRef(0);                              // tracks OFFSET for next page

  const [genres,  setGenres]  = useState<string[]>([]);
  const [artists, setArtists] = useState<string[]>([]);
  const [years,   setYears]   = useState<number[]>([]);
  const [stats,   setStats]   = useState<LibraryStats | null>(null);

  const [filters, setFilters]   = useState<FilterState>(DEFAULT_FILTER);
  const queryFilters = useDebounce(filters, 250);

  const [loading, setLoading] = useState(true);
  const [dbReady, setDbReady] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [spotifySync, setSpotifySync] = useState<SpotifySyncState>(IDLE_SYNC);
  const [error,   setError]   = useState<string | null>(null);

  const cancelTokenRef = useRef<CancellationToken>({ cancelled: false });

  useEffect(() => {
    initDB()
      .then(() => setDbReady(true))
      .catch((e) => setError('DB init failed: ' + (e?.message ?? e)))
      .finally(() => setLoading(false));
  }, []);

  // ── Core query — resets album list, runs on debounced filter change ───────
  const runQuery = useCallback(async (f: FilterState) => {
    if (!dbReady) return;
    setLoading(true);
    offsetRef.current = 0;
    try {
      const [first, cnt, g, ar, y, s] = await Promise.all([
        getAllAlbums(
          f.search, f.genre, f.sortField, f.sortOrder,
          yearStr(f.yearFrom), yearStr(f.yearTo), f.artist,
          PAGE_SIZE, 0
        ),
        countAlbums(f.search, f.genre, yearStr(f.yearFrom), yearStr(f.yearTo), f.artist),
        getDistinctGenres(),
        getDistinctArtists(),
        getDistinctYears(),
        getStats(),
      ]);
      offsetRef.current = first.length;
      setAlbums(first);
      setTotal(cnt);
      setHasMore(first.length < cnt);
      setGenres(g); setArtists(ar); setYears(y); setStats(s);
      setError(null);
    } catch (e: any) {
      setError('Failed to load library: ' + (e?.message ?? e));
    } finally {
      setLoading(false);
    }
  }, [dbReady]);

  useEffect(() => { runQuery(queryFilters); }, [runQuery, queryFilters]);

  // ── Load next page — appends to album list ────────────────────────────────
  const loadMore = useCallback(async () => {
    if (!hasMore || loadingMore || loading) return;
    setLoadingMore(true);
    try {
      const f    = queryFilters;
      const more = await getAllAlbums(
        f.search, f.genre, f.sortField, f.sortOrder,
        yearStr(f.yearFrom), yearStr(f.yearTo), f.artist,
        PAGE_SIZE, offsetRef.current
      );
      if (more.length === 0) {
        setHasMore(false);
        return;
      }
      offsetRef.current += more.length;
      setAlbums((prev) => [...prev, ...more]);
      setHasMore(offsetRef.current < total);
    } catch (e: any) {
      setError('Failed to load more: ' + (e?.message ?? e));
    } finally {
      setLoadingMore(false);
    }
  }, [hasMore, loadingMore, loading, queryFilters, total]);

  const forceRefresh = useCallback(() => runQuery(queryFilters), [runQuery, queryFilters]);

  // ── Sync CSV ──────────────────────────────────────────────────────────────
  const syncCSV = useCallback(async (csvText: string): Promise<SyncResult> => {
    setSyncing(true);
    try { const r = await importFromCSV(csvText); await forceRefresh(); return r; }
    finally { setSyncing(false); }
  }, [forceRefresh]);

  // ── Sync Spotify ──────────────────────────────────────────────────────────
  const syncFromSpotify = useCallback(async (clean = false): Promise<SyncResult> => {
    const token: CancellationToken = { cancelled: false };
    cancelTokenRef.current = token;

    if (clean) await clearLibrary();
    setSpotifySync({ active: true, stage: 'albums', fetched: 0, total: 0 });
    const result: SyncResult = { imported: 0, skipped: 0, existing: 0, errors: [] };

    try {
      const fetched = await fetchAllSavedAlbums(
        (p: FetchProgress) => setSpotifySync({ active: true, ...p }), token,
      );
      const existingIds: Set<string> = clean ? new Set() : await getExistingAlbumIds();
      const total = fetched.length;

      for (let i = 0; i < total; i++) {
        if (token.cancelled) break;
        const album = fetched[i];
        setSpotifySync({ active: true, stage: 'saving', fetched: i + 1, total });
        if (existingIds.has(album.id)) { result.existing++; continue; }
        try {
          const imagePath = await downloadAlbumImage(album.image_url, album.id);
          await upsertAlbum({ ...album, image_path: imagePath });
          result.imported++;
        } catch (e: any) {
          result.skipped++;
          result.errors.push('"' + album.title + '": ' + (e?.message ?? e));
        }
      }

      await saveToStore();
      await forceRefresh();
      return result;
    } catch (e) {
      if (e instanceof SyncCancelledError) {
        await saveToStore(); await forceRefresh(); return result;
      }
      throw e;
    } finally { setSpotifySync(IDLE_SYNC); }
  }, [forceRefresh]);

  const cancelSync   = useCallback(() => { cancelTokenRef.current.cancelled = true; }, []);
  const clearAll     = useCallback(async () => { await clearLibrary(); await forceRefresh(); }, [forceRefresh]);
  const updateFilter = useCallback(<K extends keyof FilterState>(key: K, value: FilterState[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }, []);
  const resetFilters = useCallback(() => setFilters(DEFAULT_FILTER), []);

  return {
    albums, total, hasMore, loadingMore,
    genres, artists, years, stats,
    filters, loading, dbReady, syncing, spotifySync, error,
    refresh: forceRefresh, syncCSV, syncFromSpotify, cancelSync, clearAll,
    updateFilter, resetFilters, loadMore,
  };
}
