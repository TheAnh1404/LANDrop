'use client';

import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { useSession } from '../session/session-provider';

export function useAdminResource<T>(route: string, demo: T) {
  const { mode, request } = useSession();
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setData(null); setLoading(true); setError('');
    if (mode === 'demo') { setData(demo); setLoading(false); return; }
    request<T>(route, { signal: controller.signal })
      .then(response => { if (!controller.signal.aborted) setData(response); })
      .catch(failure => { if (!controller.signal.aborted) setError(errorMessage(failure)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [route, mode, request, revision, demo]);
  return { data, loading, error, refresh: () => setRevision(value => value + 1) };
}
