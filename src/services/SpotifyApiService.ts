import { getValidAccessToken } from './SpotifyAuthService';
import { spotifyFetch, sleep } from '../utils/spotifyFetch';
import { Album } from '../types';
import { SPOTIFY_API_URL } from '../config/spotify';

// ---------------------------------------------------------------------------
// Cancellation
// ---------------------------------------------------------------------------

export class SyncCancelledError extends Error {
  constructor() { super('Sync cancelled by user'); this.name = 'SyncCancelledError'; }
}

export interface CancellationToken { cancelled: boolean; }

function assertNotCancelled(token?: CancellationToken): void {
  if (token?.cancelled) throw new SyncCancelledError();
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface SpotifyImage  { url: string; width: number; height: number; }
interface SpotifyArtist { id: string; name: string; genres?: string[]; }

interface SpotifyAlbumObject {
  id:           string;
  name:         string;
  artists:      SpotifyArtist[];
  images:       SpotifyImage[];
  release_date: string;
  genres:       string[];
}

interface SavedAlbumItem {
  added_at: string;
  album:    SpotifyAlbumObject;
}

interface SavedAlbumsPage {
  items: SavedAlbumItem[];
  next:  string | null;
  total: number;
}

export type SyncStage = 'albums' | 'genres' | 'saving';

export interface FetchProgress {
  stage:   SyncStage;
  fetched: number;
  total:   number;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export async function fetchAllSavedAlbums(
  onProgress?: (p: FetchProgress) => void,
  token?:       CancellationToken,
): Promise<Album[]> {
  const accessToken = await getValidAccessToken();
  const items       = await fetchAllPages(accessToken, onProgress, token);
  const genreMap    = await fetchArtistGenres(items, accessToken, onProgress, token);
  return items.map((item) => mapToAlbum(item, genreMap));
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

async function fetchAllPages(
  accessToken:  string,
  onProgress?:  (p: FetchProgress) => void,
  token?:       CancellationToken,
): Promise<SavedAlbumItem[]> {
  const all: SavedAlbumItem[] = [];
  let url: string | null = `${SPOTIFY_API_URL}/me/albums?limit=50`;
  let total = 0;

  while (url) {
    assertNotCancelled(token);
    const page: SavedAlbumsPage = await spotifyFetch<SavedAlbumsPage>(url, accessToken);
    total = page.total;
    all.push(...page.items);
    onProgress?.({ stage: 'albums', fetched: all.length, total });
    url = page.next;
    if (url) await sleep(100);
  }

  return all;
}

async function fetchArtistGenres(
  items:        SavedAlbumItem[],
  accessToken:  string,
  onProgress?:  (p: FetchProgress) => void,
  token?:       CancellationToken,
): Promise<Map<string, string[]>> {
  const ids   = [...new Set(items.flatMap((i) => i.album.artists.map((a) => a.id)))];
  const map   = new Map<string, string[]>();
  const total = ids.length;

  for (let i = 0; i < ids.length; i += 50) {
    assertNotCancelled(token);
    try {
      const batch = ids.slice(i, i + 50).join(',');
      const res = await spotifyFetch<{ artists: SpotifyArtist[] }>(
        `${SPOTIFY_API_URL}/artists?ids=${batch}`, accessToken
      );
      res.artists.forEach((a) => a?.id && map.set(a.id, a.genres ?? []));
      onProgress?.({ stage: 'genres', fetched: Math.min(i + 50, total), total });
    } catch (e) {
      if (e instanceof SyncCancelledError) throw e;
      /* genre enrichment is best-effort */
    }
    await sleep(100);
  }

  return map;
}

function mapToAlbum(item: SavedAlbumItem, genreMap: Map<string, string[]>): Album {
  const { album, added_at } = item;
  const primaryArtistId = album.artists[0]?.id ?? '';
  const genres = genreMap.get(primaryArtistId) ?? album.genres ?? [];

  return {
    id:         album.id,
    title:      album.name,
    artist:     album.artists.map((a) => a.name).join(', '),
    year:       parseInt(album.release_date?.split('-')[0] ?? '0', 10) || 0,
    genre:      genres[0] ?? '',
    image_url:  pickBestImage(album.images),
    image_path: '',
    added_at:   added_at ?? '',
  };
}

// Fix: use largest image as fallback instead of smallest.
// The old code used sorted[sorted.length - 1] which picked the 64px thumbnail.
function pickBestImage(images: SpotifyImage[]): string {
  if (!images.length) return '';
  // Sort descending by width so sorted[0] is always the largest
  const sorted = [...images].sort((a, b) => (b.width ?? 0) - (a.width ?? 0));
  // Prefer ~300px for card display; fall back to largest available
  return (
    sorted.find((img) => img.width >= 200 && img.width <= 320) ??
    sorted[0]
  )?.url ?? '';
}
