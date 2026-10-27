import { useState, useCallback } from 'react';

export interface Toast { msg: string; ok: boolean; }

export function useToast(durationMs = 3500) {
  const [toast, setToast] = useState<Toast | null>(null);

  const show = useCallback((msg: string, ok = true) => {
    setToast({ msg, ok });
    setTimeout(() => setToast(null), durationMs);
  }, [durationMs]);

  return { toast, show };
}
