import { Queueable } from '../types';
import { addAlbumsToQueue } from '../services/SpotifyPlaylistService';

/**
 * Factory that returns a queue handler for any selection that satisfies Queueable.
 * Eliminates the near-identical handleQueue / handlePlQueue pattern in App.tsx.
 */
export function createQueueHandler(
  getSelected:  () => Queueable[],
  setBusy:      (b: boolean) => void,
  showToast:    (msg: string, ok?: boolean) => void,
  onSuccess:    () => void,
): () => Promise<void> {
  return async () => {
    setBusy(true);
    try {
      const { success, failed, error } = await addAlbumsToQueue(getSelected());
      if (success === 0) {
        showToast(error ?? 'Open Spotify on a device first', false);
      } else {
        showToast(
          'Added ' + success + ' tracks to queue' +
          (failed ? ' (' + failed + ' failed)' : ''),
        );
        onSuccess();
      }
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Queue failed', false);
    } finally {
      setBusy(false);
    }
  };
}
