'use client';
import { useEffect, useState } from 'react';
import { useSession } from '../session/session-provider';
import { Workspace } from '../types';
import { listDemoWorkspaces } from './workspace-provider';
import { mapWorkspace, WireWorkspace } from '../api/models';
import { errorMessage } from '../api/client';
import { collectPages, Page } from '../api/pagination';

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
        const data = await collectPages<WireWorkspace>(offset => request<Page<WireWorkspace>>(`/api/workspaces?limit=100&offset=${offset}`, { signal: controller.signal }));
        if (!controller.signal.aborted) setWorkspaces(data.map(mapWorkspace));
      } catch (failure) { if (!controller.signal.aborted) setError(errorMessage(failure)); }
      finally { if (!controller.signal.aborted) setLoading(false); }
    }
    if (user) void load(); else setLoading(false);
    return () => controller.abort();
  }, [mode, request, user?.id, revision]);
  return { workspaces, loading, error, refresh: () => setRevision(r => r + 1) };
}
