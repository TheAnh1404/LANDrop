'use client';

import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { CURRENT_USER } from '../mock-data';
import { ServerConnectionInfo, User } from '../types';
import { ApiError, AuthResponse, fetchJson, normalizeServerUrl, TokenPair, tokenRole, errorMessage } from '../api/client';

type Mode = 'live' | 'demo';
interface Session {
  mode: Mode;
  ready: boolean;
  user: User | null;
  serverInfo: ServerConnectionInfo;
  error: string;
  isAuthenticated: boolean;
  isAdmin: boolean;
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
  const tokens = useRef<TokenPair | null>(null);
  const origin = useRef(defaultServer.serverUrl);
  const generation = useRef(0);
  const refreshing = useRef<Promise<string> | null>(null);

  const clear = useCallback(() => {
    generation.current++;
    tokens.current = null;
    refreshing.current = null;
    setUser(null);
    sessionStorage.removeItem('landrop.refresh');
    sessionStorage.removeItem('landrop.mode');
  }, []);

  const apply = useCallback((auth: AuthResponse) => {
    tokens.current = auth;
    sessionStorage.setItem('landrop.refresh', auth.refreshToken);
    setUser(mapUser(auth));
    setMode('live');
  }, []);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    async function restore() {
      try {
        const saved = localStorage.getItem('landrop.server');
        if (saved) { origin.current = normalizeServerUrl(saved); setServerInfo(s => ({ ...s, serverUrl: origin.current })); }
        if (sessionStorage.getItem('landrop.mode') === 'demo') { setMode('demo'); setUser(CURRENT_USER); return; }
        const refreshToken = sessionStorage.getItem('landrop.refresh');
        if (refreshToken) {
          const auth = await fetchJson<AuthResponse>(origin.current, '/api/auth/refresh', { method: 'POST', body: JSON.stringify({ refreshToken }), signal: controller.signal });
          if (active) { apply(auth); setServerInfo(s => ({ ...s, status: 'connected' })); }
        }
      } catch { if (active) clear(); }
      finally { if (active) setReady(true); }
    }
    // Defer so StrictMode's discarded effect never rotates a refresh token.
    const timer = setTimeout(restore, 0);
    return () => { active = false; clearTimeout(timer); controller.abort(); };
  }, [apply, clear]);

  const request = useCallback(async <T,>(route: string, init: RequestInit = {}): Promise<T> => {
    const version = generation.current;
    const currentOrigin = origin.current;
    const send = (token?: string) => fetchJson<T>(currentOrigin, route, { ...init, headers: { ...init.headers, ...(token ? { Authorization: `Bearer ${token}` } : {}) } });
    const initialToken = tokens.current?.accessToken;
    try { return await send(initialToken); }
    catch (failure) {
      if (!(failure instanceof ApiError) || failure.status !== 401 || !tokens.current || version !== generation.current) throw failure;
      if (initialToken !== tokens.current.accessToken) return send(tokens.current.accessToken);
      if (!refreshing.current) {
        const refreshToken = tokens.current.refreshToken;
        refreshing.current = fetchJson<AuthResponse>(currentOrigin, '/api/auth/refresh', { method: 'POST', body: JSON.stringify({ refreshToken }) }).then(auth => {
          if (version !== generation.current) throw new ApiError('Phiên đăng nhập đã thay đổi.', 401);
          apply(auth);
          return auth.accessToken;
        }).catch(error => { if (version === generation.current) clear(); throw error; }).finally(() => { if (version === generation.current) refreshing.current = null; });
      }
      return send(await refreshing.current);
    }
  }, [apply, clear]);

  const connectServer = async (input: string) => {
    setError('');
    setServerInfo(s => ({ ...s, status: 'connecting' }));
    try {
      const nextOrigin = normalizeServerUrl(input);
      if (typeof location !== 'undefined' && nextOrigin === location.origin) throw new ApiError('Đây là cổng frontend. Hãy nhập cổng backend, mặc định localhost:3100 khi chạy cùng máy.');
      const start = performance.now();
      const health = await fetchJson<{ status: string; version?: string; protocolVersion?: number }>(nextOrigin, '/health');
      if (health.status !== 'ok') throw new ApiError('Server chưa sẵn sàng.');
      clear();
      origin.current = nextOrigin;
      localStorage.setItem('landrop.server', nextOrigin);
      setMode('live');
      setServerInfo({ ...defaultServer, serverUrl: nextOrigin, status: 'connected', latencyMs: Math.round(performance.now() - start), version: health.version || '', protocol: String(health.protocolVersion || 1) });
      return true;
    } catch (failure) { setError(errorMessage(failure)); setServerInfo(s => ({ ...s, status: 'error' })); return false; }
  };

  const authenticate = async (route: string, body: object) => {
    setError('');
    const version = generation.current;
    try {
      const auth = await fetchJson<AuthResponse>(origin.current, route, { method: 'POST', body: JSON.stringify(body) });
      if (version !== generation.current) return false;
      apply(auth);
      setServerInfo(s => ({ ...s, status: 'connected' }));
      return true;
    } catch (failure) { setError(errorMessage(failure)); return false; }
  };
  const logout = async () => {
    const pair = tokens.current;
    const oldOrigin = origin.current;
    clear();
    setMode('live');
    if (pair) { try { await fetchJson(oldOrigin, '/api/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken: pair.refreshToken }), headers: { Authorization: `Bearer ${pair.accessToken}` } }); } catch { setError('Đã đóng phiên trên trình duyệt. Server chưa xác nhận thu hồi phiên.'); } }
  };
  return <Context.Provider value={{ mode, ready, user, serverInfo, error, isAuthenticated: !!user, isAdmin: user?.role === 'SYSTEM_ADMIN', request, connectServer,
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
