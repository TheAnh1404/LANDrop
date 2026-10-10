'use client';

import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { CURRENT_USER } from '../mock-data';
import { ServerConnectionInfo, User } from '../types';
import { ApiError, AuthResponse, fetchJson, normalizeServerUrl, TokenPair, tokenRole, errorMessage } from '../api/client';
import { createAuthenticatedRequester } from '../api/authorized';
import { getRealtimeClient } from '../realtime/realtime-client';

type Mode = 'live' | 'demo';
interface Session {
  mode: Mode;
  ready: boolean;
  user: User | null;
  serverInfo: ServerConnectionInfo;
  error: string;
  isAuthenticated: boolean;
  isAdmin: boolean;
  authVersion: number;
  getAccessToken: () => string | null;
  serverUrl: string;
  refreshSession: () => Promise<string | null>;
  connectServer: (url: string) => Promise<boolean>;
  login: (identifier: string, password: string, admin?: boolean) => Promise<boolean>;
  register: (name: string, username: string, email: string, password: string) => Promise<boolean>;
  enterDemo: () => void;
  logout: () => Promise<void>;
  request: <T>(route: string, init?: RequestInit) => Promise<T>;
}
const Context = createContext<Session | null>(null);
const defaultServer: ServerConnectionInfo = { serverUrl: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:3100', serverName: 'LANDrop Server', status: 'disconnected', p2pStatus: 'unavailable', version: '', protocol: '' };
const mapUser = (auth: AuthResponse): User => ({ id: auth.user.id, name: auth.user.displayName || auth.user.username, email: auth.user.email, avatarUrl: auth.user.avatarUrl || undefined, createdAt: auth.user.createdAt, status: 'online', role: tokenRole(auth.accessToken) });

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [mode, setMode] = useState<Mode>('live');
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState<User | null>(null);
  const [serverInfo, setServerInfo] = useState(defaultServer);
  const [error, setError] = useState('');
  const [authVersion, setAuthVersion] = useState(0);
  const tokens = useRef<TokenPair | null>(null);
  const origin = useRef(defaultServer.serverUrl);
  const generation = useRef(0);

  const clear = useCallback(() => {
    generation.current++;
    tokens.current = null;
    setUser(null);
    setAuthVersion(v => v + 1);
    sessionStorage.removeItem('landrop.refresh');
    sessionStorage.removeItem('landrop.mode');
  }, []);

  const apply = useCallback((auth: AuthResponse) => {
    tokens.current = auth;
    sessionStorage.setItem('landrop.refresh', auth.refreshToken);
    sessionStorage.removeItem('landrop.mode');
    setUser(prev => {
      const mapped = mapUser(auth);
      if (
        prev &&
        prev.id === mapped.id &&
        prev.name === mapped.name &&
        prev.email === mapped.email &&
        prev.role === mapped.role &&
        prev.avatarUrl === mapped.avatarUrl
      ) {
        return prev;
      }
      return mapped;
    });
    setMode('live');
    setAuthVersion(v => v + 1);
  }, []);

  const enrollBrowser = useCallback(async (auth: AuthResponse, serverUrl: string, signal?: AbortSignal) => {
    try {
      await fetchJson(serverUrl, '/api/devices/enroll', {
        method: 'POST', signal,
        headers: { Authorization: `Bearer ${auth.accessToken}` },
        body: JSON.stringify({
          clientInstanceId: getRealtimeClient().getClientInstanceId(auth.user.id),
          deviceName: `${navigator.platform || 'Web'} Browser`, platform: 'web', clientVersion: '1.0.0',
        }),
      });
    } catch (failure) {
      if (signal?.aborted) throw failure;
      // Authentication has already persisted successfully; keep that session available for retry.
      setError(`Đã đăng nhập nhưng chưa lưu được thiết bị: ${errorMessage(failure)}`);
    }
  }, []);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const version = generation.current;
    async function restore() {
      try {
        const saved = localStorage.getItem('landrop.server');
        if (saved) { origin.current = normalizeServerUrl(saved); setServerInfo(s => ({ ...s, serverUrl: origin.current })); }
        const refreshToken = sessionStorage.getItem('landrop.refresh');
        if (!refreshToken && sessionStorage.getItem('landrop.mode') === 'demo') { setMode('demo'); setUser(CURRENT_USER); return; }
        if (refreshToken) {
          const auth = await fetchJson<AuthResponse>(origin.current, '/api/auth/refresh', { method: 'POST', body: JSON.stringify({ refreshToken }), signal: controller.signal });
          if (active && version === generation.current) {
            // Rotation is committed already; retain the replacement before another network operation.
            sessionStorage.setItem('landrop.refresh', auth.refreshToken);
            sessionStorage.removeItem('landrop.mode');
            await enrollBrowser(auth, origin.current, controller.signal);
          }
          if (active && version === generation.current) { apply(auth); setServerInfo(s => ({ ...s, status: 'connected' })); }
        }
      } catch { if (active && version === generation.current) clear(); }
      finally { if (active) setReady(true); }
    }
    // Defer so StrictMode's discarded effect never rotates a refresh token.
    const timer = setTimeout(restore, 0);
    return () => { active = false; clearTimeout(timer); controller.abort(); };
  }, [apply, clear, enrollBrowser]);

  const request = useMemo(() => createAuthenticatedRequester({
    read: () => ({ origin: origin.current, tokens: tokens.current, version: generation.current }),
    refreshed: apply,
    expired: clear,
  }), [apply, clear]);

  const connectServer = async (input: string) => {
    const version = generation.current;
    setError('');
    setServerInfo(s => ({ ...s, status: 'connecting' }));
    try {
      const nextOrigin = normalizeServerUrl(input);
      if (typeof location !== 'undefined' && nextOrigin === location.origin) throw new ApiError('Đây là cổng frontend. Hãy nhập cổng backend, mặc định localhost:3100 khi chạy cùng máy.');
      const start = performance.now();
      const health = await fetchJson<{ status: string; serverVersion?: string; protocolVersion?: number; persistenceAvailable?: boolean }>(nextOrigin, '/health');
      if (version !== generation.current) return false;
      if (health.status !== 'ok') throw new ApiError('Server chưa sẵn sàng.');
      if (health.persistenceAvailable === false) throw new ApiError('Backend chưa bật database. Cần cấu hình DATABASE_URL và khởi động lại server để đăng ký, đăng nhập và lưu dữ liệu.');
      clear();
      origin.current = nextOrigin;
      localStorage.setItem('landrop.server', nextOrigin);
      setMode('live');
      setServerInfo({ ...defaultServer, serverUrl: nextOrigin, status: 'connected', latencyMs: Math.round(performance.now() - start), version: health.serverVersion || '', protocol: String(health.protocolVersion || 1) });
      return true;
    } catch (failure) { if (version === generation.current) { setError(errorMessage(failure)); setServerInfo(s => ({ ...s, status: 'error' })); } return false; }
  };

  const authenticate = async (route: string, body: object) => {
    setError('');
    const version = ++generation.current;
    try {
      const auth = await fetchJson<AuthResponse>(origin.current, route, { method: 'POST', body: JSON.stringify(body) });
      if (version !== generation.current) return false;
      sessionStorage.setItem('landrop.refresh', auth.refreshToken);
      sessionStorage.removeItem('landrop.mode');
      await enrollBrowser(auth, origin.current);
      if (version !== generation.current) return false;
      apply(auth);
      setServerInfo(s => ({ ...s, status: 'connected' }));
      return true;
    } catch (failure) { if (version === generation.current) setError(errorMessage(failure)); return false; }
  };

  const refreshSession = useCallback(async (): Promise<string | null> => {
    if (mode === 'demo') return null;
    try {
      return await request.refreshToken();
    } catch {
      return null;
    }
  }, [mode, request]);

  const getAccessToken = useCallback(() => tokens.current?.accessToken || null, []);

  const logout = async () => {
    const pair = tokens.current;
    const oldOrigin = origin.current;
    clear();
    setMode('live');
    if (pair) { try { await fetchJson(oldOrigin, '/api/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken: pair.refreshToken }), headers: { Authorization: `Bearer ${pair.accessToken}` } }); } catch { setError('Đã đóng phiên trên trình duyệt. Server chưa xác nhận thu hồi phiên.'); } }
  };
  return <Context.Provider value={{ mode, ready, user, serverInfo, error, isAuthenticated: !!user, isAdmin: user?.role === 'SYSTEM_ADMIN', authVersion, request, connectServer,
    getAccessToken,
    serverUrl: origin.current,
    refreshSession,
    login: (emailOrUsername, password, admin = false) => authenticate(admin ? '/api/admin/auth/login' : '/api/auth/login', { emailOrUsername, password }),
    register: (displayName, username, email, password) => authenticate('/api/auth/register', { displayName, username, email, password }),
    logout, enterDemo: () => { clear(); setMode('demo'); setUser(CURRENT_USER); sessionStorage.setItem('landrop.mode', 'demo'); setError(''); }
  }}>{children}</Context.Provider>;
}

export function useSession() {
  const context = useContext(Context);
  if (!context) throw new Error('SessionProvider is required.');
  return context;
}
