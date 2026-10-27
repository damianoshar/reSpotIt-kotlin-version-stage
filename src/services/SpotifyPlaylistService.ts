import { getValidAccessToken } from './SpotifyAuthService';
import { spotifyFetch, sleep } from '../utils/spotifyFetch';
import { SPOTIFY_API_URL } from '../config/spotify';
import { Album } from '../types';

// ---------------------------------------------------------------------------
// Types  (only fields we actually use — YAGNI)
// ---------------------------------------------------------------------------

export interface SpotifyPlaylist {
  id:     string;
  name:   string;
  images: { url: string }[];
  tracks: { total: number };
  owner:  { id: string };
}

export interface QueueResult {
  success: number;
  failed:  number;
  error?:  string;
}

// ---------------------------------------------------------------------------
// User identity  (cached for session to avoid repeated /me calls causing
// empty playlist lists when the extra fetch races or fails)
// ---------------------------------------------------------------------------

let _cachedUserId: string | null = null;

async function getUserId(): Promise<string> {
  if (_cachedUserId) return _cachedUserId;
  const token = await getValidAccessToken();
  const { id } = await spotifyFetch<{ id: string }>(`${SPOTIFY_API_URL}/me`, token);
  _cachedUserId = id;
  return id;
}

/** Call on logout alongside clearTokens() */
export function clearUserIdCache(): void {
  _cachedUserId = null;
}

// ---------------------------------------------------------------------------
// Playlists
// ---------------------------------------------------------------------------

export async function getUserPlaylists(): Promise<SpotifyPlaylist[]> {
  const token  = await getValidAccessToken();
  const userId = await getUserId();
  const all:   SpotifyPlaylist[] = [];
  let url: string | null = `${SPOTIFY_API_URL}/me/playlists?limit=50`;

  while (url) {
    const page: { items: SpotifyPlaylist[]; next: string | null } =
      await spotifyFetch<{ items: SpotifyPlaylist[]; next: string | null }>(url, token);
    all.push(...page.items.filter((p) => p.owner?.id === userId));
    url = page.next;
  }

  return all;
}

export async function createPlaylist(name: string): Promise<SpotifyPlaylist> {
  const token  = await getValidAccessToken();
  const userId = await getUserId();

  const res = await fetch(`${SPOTIFY_API_URL}/users/${userId}/playlists`, {
    method:  'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body:    JSON.stringify({ name, public: false }),
  });
  if (!res.ok) throw new Error(`Failed to create playlist: ${await res.text()}`);
  return res.json() as Promise<SpotifyPlaylist>;
}

// ---------------------------------------------------------------------------
// Tracks
// ---------------------------------------------------------------------------

async function getAlbumTrackUris(albumId: string): Promise<string[]> {
  const token = await getValidAccessToken();
  const uris: string[] = [];
  let url: string | null = `${SPOTIFY_API_URL}/albums/${albumId}/tracks?limit=50`;

  while (url) {
    const page: { items: { uri: string }[]; next: string | null } =
      await spotifyFetch<{ items: { uri: string }[]; next: string | null }>(url, token);
    uris.push(...page.items.map((t) => t.uri));
    url = page.next;
  }
  return uris;
}

async function addTrackUrisToPlaylist(playlistId: string, uris: string[]): Promise<void> {
  const token = await getValidAccessToken();
  for (let i = 0; i < uris.length; i += 100) {
    const res = await fetch(`${SPOTIFY_API_URL}/playlists/${playlistId}/tracks`, {
      method:  'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body:    JSON.stringify({ uris: uris.slice(i, i + 100) }),
    });
    if (!res.ok) throw new Error(`Failed to add tracks: ${await res.text()}`);
  }
}

// ---------------------------------------------------------------------------
// Composed operations  (used by UI)
// ---------------------------------------------------------------------------

export async function addAlbumsToPlaylist(
  albums:     Album[],
  playlistId: string
): Promise<{ tracksAdded: number }> {
  if (!playlistId) throw new Error('No playlist selected');
  const uris: string[] = [];
  for (const album of albums) {
    const albumUris = await getAlbumTrackUris(album.id);
    if (albumUris.length === 0) console.warn(`[Playlist] No tracks found for album ${album.id}`);
    uris.push(...albumUris);
  }
  if (uris.length === 0) throw new Error('No tracks found in selected albums');
  await addTrackUrisToPlaylist(playlistId, uris);
  return { tracksAdded: uris.length };
}

export async function addAlbumsToQueue(albums: Album[]): Promise<QueueResult> {
  const token = await getValidAccessToken();
  let success = 0;
  let failed  = 0;
  let lastError = '';

  for (const album of albums) {
    const uris = await getAlbumTrackUris(album.id);
    for (const uri of uris) {
      try {
        const res = await fetch(
          `${SPOTIFY_API_URL}/me/player/queue?uri=${encodeURIComponent(uri)}`,
          { method: 'POST', headers: { Authorization: `Bearer ${token}` } }
        );
        if (res.status === 204) {
          success++;
        } else if (res.status === 403) {
          throw new Error('Premium required or no active device');
        } else if (res.status === 404) {
          throw new Error('No active device found — open Spotify on a device first');
        } else {
          const text = await res.text();
          failed++;
          lastError = `HTTP ${res.status}: ${text}`;
          console.warn('[Queue] Failed to queue track:', uri, lastError);
        }
      } catch (e) {
        if (e instanceof Error &&
            (e.message.includes('Premium') || e.message.includes('device'))) {
          throw e; // surface immediately — retrying won't help
        }
        failed++;
        lastError = String(e);
      }
      await sleep(120); // slightly more breathing room for rate limits
    }
  }

  return { success, failed, error: lastError || undefined };
}
