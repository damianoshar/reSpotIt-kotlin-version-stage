import { useState, useEffect, useCallback } from 'react';
import {
  SpotifyTokens,
  loadStoredTokens,
  startAuthFlow,
  clearTokens,
  registerAppUrlListener,
} from '../services/SpotifyAuthService';

export type AuthState = 'loading' | 'unauthenticated' | 'authenticated';

export function useSpotifyAuth() {
  const [authState, setAuthState] = useState<AuthState>('loading');
  const [tokens, setTokens]       = useState<SpotifyTokens | null>(null);
  const [error, setError]         = useState<string | null>(null);

  useEffect(() => {
    // ✅ Register the deep-link listener immediately at app boot,
    //    BEFORE any browser is ever opened — prevents race conditions.
    registerAppUrlListener();

    loadStoredTokens()
      .then((t) => {
        if (t) { setTokens(t); setAuthState('authenticated'); }
        else     setAuthState('unauthenticated');
      })
      .catch(() => setAuthState('unauthenticated'));
  }, []);

  const login = useCallback(async () => {
    setError(null);
    try {
      const t = await startAuthFlow();
      setTokens(t);
      setAuthState('authenticated');
    } catch (e: any) {
      setError(e?.message ?? 'Login failed');
      setAuthState('unauthenticated');
    }
  }, []);

  const logout = useCallback(async () => {
    await clearTokens();
    setTokens(null);
    setAuthState('unauthenticated');
  }, []);

  return { authState, tokens, error, login, logout };
}
