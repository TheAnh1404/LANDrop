'use client';

import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { useSession } from '../session/session-provider';
import { Channel, FileItem, FolderItem, Message, Workspace, WorkspaceMember } from '../types';
import { MOCK_CHANNELS, MOCK_FILES, MOCK_FOLDERS, MOCK_MEMBERS, MOCK_MESSAGES, MOCK_WORKSPACES } from '../mock-data';
import { mapFile, mapFolder, mapMember, mapMessage, mapWorkspace, WireFile, WireFolder, WireMember, WireMessage, WireWorkspace } from '../api/models';
import { errorMessage } from '../api/client';

type Data = { workspace: Workspace | null; channels: Channel[]; members: WorkspaceMember[]; files: FileItem[]; folders: FolderItem[] };
const empty: Data = { workspace: null, channels: [], members: [], files: [], folders: [] };
const Context = createContext<(Data & { loading: boolean; error: string; refresh: () => void; mutate: (route: string, method: string, body?: object) => Promise<void> }) | null>(null);

// Demo changes stay within the explicitly selected demo session.
const demoWorkspaces = new Map(MOCK_WORKSPACES.map(workspace => [workspace.id, workspace]));
const demoChannels = new Map(Object.entries(MOCK_CHANNELS).map(([key, list]) => [key, [...list]]));
const demoMessages = new Map(Object.entries(MOCK_MESSAGES).map(([key, list]) => [key, [...list]]));
export function listDemoWorkspaces() { return [...demoWorkspaces.values()]; }
export function addDemoWorkspace(name: string, description: string, userId: string) {
  const workspace: Workspace = { id: `demo_${crypto.randomUUID()}`, name, description, ownerId: userId, role: 'OWNER', membersCount: 1, channelsCount: 0, filesCount: 0, createdAt: new Date().toISOString() };
  demoWorkspaces.set(workspace.id, workspace);
  demoChannels.set(workspace.id, []);
  return workspace;
}
export function listDemoMessages(channel: string) { return demoMessages.get(channel) || []; }
export function saveDemoMessage(message: Message) { demoMessages.set(message.channelId, [...listDemoMessages(message.channelId), message]); }

export function WorkspaceProvider({ id, children }: { id: string; children: React.ReactNode }) {
  const { mode, request, user } = useSession();
  const [data, setData] = useState<Data>(empty);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const current = useRef(id);
  current.current = id;
  const refresh = useCallback(() => setRevision(r => r + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setData(empty);
    async function load() {
      try {
        if (mode === 'demo') {
          const workspace = demoWorkspaces.get(id);
          if (!workspace) throw new Error('Không tìm thấy không gian làm việc này.');
          const members = MOCK_MEMBERS[id] || [];
          const files = MOCK_FILES[id] || [];
          const channels = demoChannels.get(id) || [];
          setData({ workspace: { ...workspace, membersCount: members.length || workspace.membersCount, channelsCount: channels.length, filesCount: files.length }, channels, members, files, folders: MOCK_FOLDERS[id] || [] });
        } else {
          const get = <T,>(route: string) => request<{ data: T }>(route, { signal: controller.signal });
          const base = `/api/workspaces/${encodeURIComponent(id)}`;
          const workspace = await get<WireWorkspace>(base);
          if (controller.signal.aborted) return;
          const results = await Promise.allSettled([get<Channel[]>(`${base}/channels?limit=100&offset=0`), get<WireMember[]>(`${base}/members?limit=100&offset=0`), get<WireFile[]>(`${base}/files?limit=100&offset=0`), get<WireFolder[]>(`${base}/folders?limit=100&offset=0`)]);
          if (controller.signal.aborted) return;
          const channels = results[0].status === 'fulfilled' ? results[0].value.data : [];
          const members = results[1].status === 'fulfilled' ? results[1].value.data.map(mapMember) : [];
          const files = results[2].status === 'fulfilled' ? results[2].value.data.map(mapFile) : [];
          const folders = results[3].status === 'fulfilled' ? results[3].value.data.map(mapFolder) : [];
          setData({ workspace: { ...mapWorkspace(workspace.data), membersCount: members.length, channelsCount: channels.length, filesCount: files.length }, channels, members, files, folders });
          const failed = results.find(result => result.status === 'rejected');
          if (failed?.status === 'rejected') setError(`Một số dữ liệu chưa tải được: ${errorMessage(failed.reason)}`);
        }
      } catch (failure) { if (!controller.signal.aborted) setError(errorMessage(failure)); }
      finally { if (!controller.signal.aborted) setLoading(false); }
    }
    if (user) void load(); else setLoading(false);
    return () => controller.abort();
  }, [id, mode, request, user?.id, revision]);
  const mutate = async (route: string, method: string, body?: object) => {
    if (mode === 'demo') throw new Error('Thao tác này cần kết nối server thật.');
    const previous = current.current;
    await request(route, { method, ...(body ? { body: JSON.stringify(body) } : {}) });
    if (current.current === previous) refresh();
  };
  return <Context.Provider value={{ ...data, loading, error, refresh, mutate }}>{children}</Context.Provider>;
}

export function useWorkspace() {
  const context = useContext(Context);
  if (!context) throw new Error('WorkspaceProvider is required.');
  return context;
}
