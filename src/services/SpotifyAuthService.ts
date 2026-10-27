import { Browser }      from '@capacitor/browser';
import { App }          from '@capacitor/app';
import { Preferences }  from '@capacitor/preferences';
import {
  SPOTIFY_CLIENT_ID,
  SPOTIFY_REDIRECT_URI,
  SPOTIFY_SCOPES,
  SPOTIFY_ACCOUNTS_URL,
} from '../config/spotify';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface SpotifyTokens {
  access_token:  string;
  refresh_token: string;
  expires_at:    number;
  token_type:    string;
}

// ---------------------------------------------------------------------------
// Module-level callback store  (avoids listener race condition)
// ---------------------------------------------------------------------------

type AuthCallback = (url: string) => void;
let _pendingCallback: AuthCallback | null = null;
let _listenerRegistered = false;

/**
 * Call this ONCE at app startup (e.g. inside useSpotifyAuth) so the listener
 * is always live — never registered inside an async function after Browser.open.
 */
export function registerAppUrlListener(): void {
  if (_listenerRegistered) return;
  _listenerRegistered = true;

  App.addListener('appUrlOpen', (event: { url: string }) => {
    console.log('[SpotifyAuth] appUrlOpen fired:', event.url);
    if (_pendingCallback) {
      const cb = _pendingCallback;
      _pendingCallback = null;
      cb(event.url);
    }
  });
}

// ---------------------------------------------------------------------------
// PKCE helpers
// ---------------------------------------------------------------------------

function generateCodeVerifier(): string {
  const array = new Uint8Array(96);
  crypto.getRandomValues(array);
  return base64UrlEncode(array);
}

async function generateCodeChallenge(verifier: string): Promise<string> {
  const data   = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return base64UrlEncode(new Uint8Array(digest));
}

function base64UrlEncode(arr: Uint8Array): string {
  return btoa(String.fromCharCode(...arr))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

// ---------------------------------------------------------------------------
// Token persistence
// ---------------------------------------------------------------------------

const PREFS_KEY = 'spotify_tokens';

export async function loadStoredTokens(): Promise<SpotifyTokens | null> {
  const { value } = await Preferences.get({ key: PREFS_KEY });
  return value ? (JSON.parse(value) as SpotifyTokens) : null;
}

async function saveTokens(t: SpotifyTokens): Promise<void> {
  await Preferences.set({ key: PREFS_KEY, value: JSON.stringify(t) });
}

export async function clearTokens(): Promise<void> {
  await Preferences.remove({ key: PREFS_KEY });
}

// ---------------------------------------------------------------------------
// Auth flow
// ---------------------------------------------------------------------------

let _codeVerifier = '';
let _authState    = '';

export async function startAuthFlow(): Promise<SpotifyTokens> {
  _codeVerifier = generateCodeVerifier();
  const challenge = await generateCodeChallenge(_codeVerifier);
  _authState = base64UrlEncode(crypto.getRandomValues(new Uint8Array(16)));

  const params = new URLSearchParams({
    client_id:             SPOTIFY_CLIENT_ID,
    response_type:         'code',
    redirect_uri:          SPOTIFY_REDIRECT_URI,
    code_challenge_method: 'S256',
    code_challenge:        challenge,
    state:                 _authState,
    scope:                 SPOTIFY_SCOPES,
    show_dialog:           'false',
  });

  const authUrl = `${SPOTIFY_ACCOUNTS_URL}/authorize?${params}`;
  console.log('[SpotifyAuth] Opening auth URL:', authUrl);
  console.log('[SpotifyAuth] Redirect URI:', SPOTIFY_REDIRECT_URI);

  return new Promise((resolve, reject) => {
    // Set the callback BEFORE opening the browser
    _pendingCallback = async (rawUrl: string) => {
      try {
        await Browser.close().catch(() => {});

        console.log('[SpotifyAuth] Callback URL received:', rawUrl);

        let url: URL;
        try {
          url = new URL(rawUrl);
        } catch {
          reject(new Error(`Invalid callback URL: ${rawUrl}`));
          return;
        }

        const errorParam = url.searchParams.get('error');
        if (errorParam) {
          reject(new Error(`Spotify denied access: ${errorParam}`));
          return;
        }

        const returnedState = url.searchParams.get('state');
        if (returnedState !== _authState) {
          reject(new Error('State mismatch — possible CSRF'));
          return;
        }

        const code = url.searchParams.get('code');
        if (!code) {
          reject(new Error('No authorization code in callback URL'));
          return;
        }

        console.log('[SpotifyAuth] Exchanging code for tokens…');
        const tokens = await exchangeCodeForTokens(code);
        await saveTokens(tokens);
        console.log('[SpotifyAuth] Tokens saved. Auth complete.');
        resolve(tokens);
      } catch (err) {
        reject(err);
      }
    };

    // Now open the browser
    Browser.open({ url: authUrl }).catch(reject);
  });
}

// ---------------------------------------------------------------------------
// Token exchange
// ---------------------------------------------------------------------------

async function exchangeCodeForTokens(code: string): Promise<SpotifyTokens> {
  const body = new URLSearchParams({
    grant_type:    'authorization_code',
    code,
    redirect_uri:  SPOTIFY_REDIRECT_URI,
    client_id:     SPOTIFY_CLIENT_ID,
    code_verifier: _codeVerifier,
  });

  const res = await fetch(`${SPOTIFY_ACCOUNTS_URL}/api/token`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body:    body.toString(),
  });

  if (!res.ok) throw new Error(`Token exchange failed: ${await res.text()}`);
  return normaliseTokenResponse(await res.json());
}

// ---------------------------------------------------------------------------
// Token refresh
// ---------------------------------------------------------------------------

export async function refreshAccessToken(refreshToken: string): Promise<SpotifyTokens> {
  const body = new URLSearchParams({
    grant_type:    'refresh_token',
    refresh_token: refreshToken,
    client_id:     SPOTIFY_CLIENT_ID,
  });

  const res = await fetch(`${SPOTIFY_ACCOUNTS_URL}/api/token`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body:    body.toString(),
  });

  if (!res.ok) throw new Error('Token refresh failed — please log in again');
  const data = await res.json();
  if (!data.refresh_token) data.refresh_token = refreshToken;
  const tokens = normaliseTokenResponse(data);
  await saveTokens(tokens);
  return tokens;
}

export async function getValidAccessToken(): Promise<string> {
  const tokens = await loadStoredTokens();
  if (!tokens) throw new Error('Not authenticated');
  if (Date.now() < tokens.expires_at - 60_000) return tokens.access_token;
  return (await refreshAccessToken(tokens.refresh_token)).access_token;
}

function normaliseTokenResponse(data: Record<string, unknown>): SpotifyTokens {
  return {
    access_token:  data.access_token  as string,
    refresh_token: data.refresh_token as string,
    expires_at:    Date.now() + ((data.expires_in as number) ?? 3600) * 1000,
    token_type:    (data.token_type as string) ?? 'Bearer',
  };
}
