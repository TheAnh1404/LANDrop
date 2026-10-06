import { ApiError, AuthResponse, fetchJson, TokenPair } from './client';

export interface AuthSnapshot {
  origin: string;
  tokens: TokenPair | null;
  version: number;
}

/** One refresh for concurrent 401s, with results scoped to the original session. */
export function createAuthenticatedRequester({ read, refreshed, expired, fetcher = fetchJson }: {
  read: () => AuthSnapshot;
  refreshed: (auth: AuthResponse) => void;
  expired: () => void;
  fetcher?: typeof fetchJson;
}) {
  let flight: { version: number; origin: string; promise: Promise<string> } | null = null;
  const isUnauthorized = (error: unknown) => error instanceof ApiError && error.status === 401;
  const changed = (initial: AuthSnapshot) => {
    const current = read();
    return current.version !== initial.version || current.origin !== initial.origin;
  };
  const assertCurrent = (initial: AuthSnapshot, signal?: AbortSignal | null) => {
    if (signal?.aborted) throw new ApiError('Yêu cầu đã hủy.', 0, 'ABORTED');
    if (changed(initial)) throw new ApiError('Phiên đăng nhập đã thay đổi.', 401, 'SESSION_CHANGED');
  };

  return async <T>(route: string, init: RequestInit = {}): Promise<T> => {
    const initial = read();
    assertCurrent(initial, init.signal);
    const send = async (token?: string) => {
      assertCurrent(initial, init.signal);
      const headers = new Headers(init.headers);
      if (token) headers.set('Authorization', `Bearer ${token}`);
      const response = await fetcher<T>(initial.origin, route, { ...init, headers });
      assertCurrent(initial, init.signal);
      return response;
    };
    try { return await send(initial.tokens?.accessToken); }
    catch (error) {
      assertCurrent(initial, init.signal);
      if (!isUnauthorized(error) || !read().tokens) throw error;
    }

    let token = read().tokens!.accessToken;
    if (initial.tokens?.accessToken === token) {
      if (!flight || flight.version !== initial.version || flight.origin !== initial.origin) {
        const refreshToken = read().tokens!.refreshToken;
        const pending = {
          version: initial.version,
          origin: initial.origin,
          promise: Promise.resolve(''),
        };
        pending.promise = fetcher<AuthResponse>(initial.origin, '/api/auth/refresh', {
          method: 'POST', body: JSON.stringify({ refreshToken }),
        }).then(auth => {
          assertCurrent(initial);
          refreshed(auth);
          return auth.accessToken;
        }).catch(error => {
          // A transient network error should not discard a still-valid refresh token.
          if (!changed(initial) && error instanceof ApiError && [401, 403].includes(error.status)) expired();
          throw error;
        }).finally(() => { if (flight === pending) flight = null; });
        flight = pending;
      }
      token = await flight.promise;
    }
    assertCurrent(initial, init.signal);
    try { return await send(token); }
    catch (error) {
      if (!changed(initial) && isUnauthorized(error)) expired();
      throw error;
    }
  };
}
