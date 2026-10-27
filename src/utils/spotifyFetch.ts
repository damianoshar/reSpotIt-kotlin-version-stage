/**
 * Thin wrapper around fetch for the Spotify Web API.
 * Handles 429 rate-limiting with Retry-After backoff.
 * Single Responsibility: HTTP transport only — no auth, no business logic.
 */
export async function spotifyFetch<T>(url: string, token: string): Promise<T> {
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (res.status === 429) {
    const retryAfter = parseInt(res.headers.get('Retry-After') ?? '2', 10);
    await sleep(retryAfter * 1000);
    return spotifyFetch<T>(url, token);
  }

  if (!res.ok) {
    throw new Error(`Spotify API ${res.status}: ${await res.text()}`);
  }

  return res.json() as Promise<T>;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
