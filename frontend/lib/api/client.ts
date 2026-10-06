export class ApiError extends Error {
  constructor(message: string, public status = 0, public code = 'NETWORK_ERROR') {
    super(message);
    this.name = 'ApiError';
  }
}

export function normalizeServerUrl(input: string): string {
  const url = new URL(/^https?:\/\//i.test(input.trim()) ? input.trim() : `http://${input.trim()}`);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !['', '/', '/api', '/api/'].includes(url.pathname)) {
    throw new ApiError('Nhập địa chỉ HTTP hoặc HTTPS của server, ví dụ localhost:3100.');
  }
  return url.origin;
}

export interface TokenPair { accessToken: string; refreshToken: string }
export interface AuthResponse extends TokenPair {
  user: { id: string; email: string; username: string; displayName?: string; avatarUrl?: string; createdAt: string };
}

export async function fetchJson<T>(origin: string, route: string, init: RequestInit = {}): Promise<T> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 15000);
  const abort = () => timeout.abort();
  init.signal?.addEventListener('abort', abort, { once: true });
  if (init.signal?.aborted) timeout.abort();
  try {
    const headers = new Headers(init.headers);
    if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    const response = await fetch(`${origin}${route}`, { ...init, signal: timeout.signal, cache: 'no-store', headers });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) throw new ApiError(body?.error?.message || body?.message || `Server trả về lỗi ${response.status}.`, response.status, body?.error?.code || body?.code);
    if (body === null && response.status !== 204) throw new ApiError('Server không trả về JSON hợp lệ.', response.status, 'INVALID_RESPONSE');
    return body as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (timeout.signal.aborted) throw new ApiError('Yêu cầu đã hủy hoặc server phản hồi quá chậm.', 0, 'ABORTED');
    throw new ApiError('Không kết nối được server. Kiểm tra địa chỉ, cổng và kết nối mạng.');
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener('abort', abort);
  }
}

export function tokenRole(token: string): 'USER' | 'SYSTEM_ADMIN' {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.role === 'SYSTEM_ADMIN' ? 'SYSTEM_ADMIN' : 'USER';
  } catch { return 'USER'; }
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Có lỗi xảy ra. Vui lòng thử lại.';
}
