'use client';

import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useSession } from '../session/session-provider';
import { getRealtimeClient, RealtimeClient } from './realtime-client';
import type { ConnectionState } from './types';

interface RealtimeContextValue {
  connectionState: ConnectionState;
  client: RealtimeClient;
}

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

const MIN_REFRESH_BACKOFF_MS = 1000;
const MAX_REFRESH_BACKOFF_MS = 10_000;

export function RealtimeProvider({ children }: { children: React.ReactNode }) {
  const { mode, user, serverUrl, authVersion, getAccessToken, refreshSession } = useSession();
  const client = getRealtimeClient();
  const [connectionState, setConnectionState] = useState<ConnectionState>(() => client.getState());
  const refreshingRef = useRef(false);
  const activeUserIdRef = useRef<string | null>(null);
  const activeServerUrlRef = useRef<string>(serverUrl);
  const activeAuthVersionRef = useRef<number>(authVersion);
  const isMountedRef = useRef(true);
  const lifecycleEpochRef = useRef(0);
  const cancelWaitRef = useRef<(() => void) | null>(null);

  const userId = user?.id || null;

  activeUserIdRef.current = userId;
  activeServerUrlRef.current = serverUrl;
  activeAuthVersionRef.current = authVersion;

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      lifecycleEpochRef.current++;
      if (cancelWaitRef.current) {
        cancelWaitRef.current();
        cancelWaitRef.current = null;
      }
      activeUserIdRef.current = null;
    };
  }, []);

  // Track state changes and handle AUTH_REQUIRED recovery
  useEffect(() => {
    const unsubscribe = client.onStateChange(nextState => {
      setConnectionState(nextState);

      // Single-flight refresh recovery when access token expires (AUTH_REQUIRED / UNAUTHORIZED)
      if (
        nextState === 'AUTH_REQUIRED' &&
        mode === 'live' &&
        activeUserIdRef.current &&
        isMountedRef.current &&
        !refreshingRef.current
      ) {
        refreshingRef.current = true;
        const captured = {
          userId: activeUserIdRef.current,
          serverUrl: activeServerUrlRef.current,
          authVersion: activeAuthVersionRef.current,
          epoch: lifecycleEpochRef.current,
        };

        const waitWithBackoff = (delayMs: number): Promise<boolean> => {
          return new Promise(resolve => {
            const timer = setTimeout(() => {
              cancelWaitRef.current = null;
              resolve(true);
            }, delayMs);

            cancelWaitRef.current = () => {
              clearTimeout(timer);
              cancelWaitRef.current = null;
              resolve(false);
            };
          });
        };

        void (async () => {
          let attempt = 0;
          try {
            while (true) {
              // Verify identity, server, version, and lifecycle epoch before attempting refresh
              if (
                !isMountedRef.current ||
                client.getState() !== 'AUTH_REQUIRED' ||
                lifecycleEpochRef.current !== captured.epoch ||
                activeUserIdRef.current !== captured.userId ||
                activeServerUrlRef.current !== captured.serverUrl ||
                activeAuthVersionRef.current !== captured.authVersion ||
                !getAccessToken()
              ) {
                break;
              }

              let nextToken: string | null = null;
              try {
                nextToken = await refreshSession();
              } catch {
                nextToken = null;
              }

              // Guard against late completions if lifecycle changed during await
              if (
                !isMountedRef.current ||
                lifecycleEpochRef.current !== captured.epoch ||
                activeUserIdRef.current !== captured.userId ||
                activeServerUrlRef.current !== captured.serverUrl
              ) {
                break;
              }

              if (nextToken) {
                // Credential success: apply() in SessionProvider incremented authVersion.
                // Reconnection with new token is handled ONLY through the authVersion effect.
                break;
              }

              // If credentials were lost or version changed (e.g. 401 terminal expiry revoked session):
              // Stop retries!
              if (activeAuthVersionRef.current !== captured.authVersion || !getAccessToken()) {
                // Failure disconnect is strictly scoped to matching identity, server, epoch, and authVersion
                if (
                  isMountedRef.current &&
                  lifecycleEpochRef.current === captured.epoch &&
                  activeUserIdRef.current === captured.userId &&
                  activeServerUrlRef.current === captured.serverUrl &&
                  activeAuthVersionRef.current === captured.authVersion &&
                  !getAccessToken()
                ) {
                  client.disconnect();
                }
                break;
              }

              // Transient network failure while credentials remain retained:
              // Preserve subscriptions and AUTH_REQUIRED state; retry with bounded backoff.
              const delay = Math.min(
                MIN_REFRESH_BACKOFF_MS * Math.pow(1.5, attempt),
                MAX_REFRESH_BACKOFF_MS
              ) + Math.random() * 250;
              attempt++;

              const ok = await waitWithBackoff(delay);
              if (!ok) {
                break;
              }
            }
          } finally {
            refreshingRef.current = false;
          }
        })();
      }
    });
    return unsubscribe;
  }, [client, mode, refreshSession, getAccessToken]);

  // Teardown when user identity changes, demo mode, logout, or unmount
  useEffect(() => {
    lifecycleEpochRef.current++;
    if (cancelWaitRef.current) {
      cancelWaitRef.current();
      cancelWaitRef.current = null;
    }

    if (mode === 'demo' || !userId) {
      client.disconnect();
      return;
    }

    return () => {
      client.disconnect();
    };
  }, [mode, userId, serverUrl, client]);

  // Connect or reconnect on mount or whenever authVersion increments (preserving subscriptions)
  useEffect(() => {
    if (mode === 'demo' || !userId) {
      return;
    }

    const token = getAccessToken();
    client.connect({
      serverUrl,
      accessToken: token,
      token,
      deviceName: typeof navigator !== 'undefined' ? `${navigator.platform || 'Web'} Browser` : 'Browser',
    });
  }, [mode, userId, serverUrl, authVersion, getAccessToken, client]);

  return (
    <RealtimeContext.Provider value={{ connectionState, client }}>
      {children}
    </RealtimeContext.Provider>
  );
}

export function useRealtime(): RealtimeContextValue {
  const context = useContext(RealtimeContext);
  if (!context) {
    // Graceful fallback for non-provider components (e.g. testing)
    const client = getRealtimeClient();
    return {
      connectionState: client.getState(),
      client,
    };
  }
  return context;
}
