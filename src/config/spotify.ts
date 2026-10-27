// ⚠️  Set your Client ID from https://developer.spotify.com/dashboard
export const SPOTIFY_CLIENT_ID = '386e37553a3b492f9381ad44084d9251';

// Must be registered EXACTLY in your Spotify App's Redirect URIs
export const SPOTIFY_REDIRECT_URI = 'com.spotifydashboard.app://callback';

export const SPOTIFY_SCOPES = [
  // Library
  'user-library-read',

  // User profile (needed to identify owned playlists + create playlists)
  'user-read-private',
  'user-read-email',

  // Playlists — read
  'playlist-read-private',
  'playlist-read-collaborative',

  // Playlists — write (create + add tracks)
  'playlist-modify-private',
  'playlist-modify-public',

  // Queue — requires Premium
  'user-modify-playback-state',
].join(' ');

export const SPOTIFY_ACCOUNTS_URL = 'https://accounts.spotify.com';
export const SPOTIFY_API_URL      = 'https://api.spotify.com/v1';
