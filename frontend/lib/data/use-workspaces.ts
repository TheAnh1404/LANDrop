'use client';
import { useEffect, useState } from 'react';
import { useSession } from '../session/session-provider';
import { Workspace } from '../types';
import { listDemoWorkspaces } from './workspace-provider';
import { mapWorkspace, WireWorkspace } from '../api/models';
import { errorMessage } from '../api/client';

export function useWorkspaces() {
  const { mode, request, user } = useSession();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setWorkspaces([]);
    async function load() {
      try {
        if (mode === 'demo') { setWorkspaces(listDemoWorkspaces()); return; }
        const data: Workspace[] = [];
        let offset = 0;
        for (;;) {
          const response = await request<{ data: WireWorkspace[]; pagination?: { hasMore: boolean }; page?: { hasMore: boolean } }>(`/api/workspaces?limit=100&offset=${offset}`, { signal: controller.signal });
          data.push(...response.data.map(mapWorkspace));
          if (!(response.pagination?.hasMore || response.page?.hasMore) || !response.data.length || offset >= 100000) break;
          offset += response.data.length;
        }
        if (!controller.signal.aborted) setWorkspaces(data);
      } catch (failure) { if (!controller.signal.aborted) setError(errorMessage(failure)); }
      finally { if (!controller.signal.aborted) setLoading(false); }
    }
    if (user) void load(); else setLoading(false);
    return () => controller.abort();
  }, [mode, request, user?.id, revision]);
  return { workspaces, loading, error, refresh: () => setRevision(r => r + 1) };
}
