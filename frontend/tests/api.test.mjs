import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const folder = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(folder, '../../tmp/frontend-api-tests');
fs.mkdirSync(output, { recursive: true });
const require = createRequire(import.meta.url);
for (const file of ['client', 'models', 'pagination', 'authorized', 'folders']) {
  const source = fs.readFileSync(path.resolve(folder, `../lib/api/${file}.ts`), 'utf8');
  fs.writeFileSync(path.join(output, `${file}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText);
}
const { ApiError, normalizeServerUrl, fetchJson, tokenRole } = require(path.join(output, 'client.js'));
const { mapMessage, mapMember, mapFile } = require(path.join(output, 'models.js'));
const { collectPages } = require(path.join(output, 'pagination.js'));
const { createAuthenticatedRequester } = require(path.join(output, 'authorized.js'));
const { folderPath, folderDestinations, visibleParent, metadataUpdate } = require(path.join(output, 'folders.js'));

test('server origin accepts host, HTTPS and an API suffix', () => {
  assert.equal(normalizeServerUrl(' localhost:3100 '), 'http://localhost:3100');
  assert.equal(normalizeServerUrl('https://lan.example/api/'), 'https://lan.example');
  assert.equal(normalizeServerUrl('http://[::1]:3100'), 'http://[::1]:3100');
});
test('server origin rejects credentials, routes, query, fragment and non-HTTP schemes', () => {
  for (const url of ['http://user:pass@localhost:3100', 'https://lan.example/w/test', 'https://lan.example?token=secret', 'https://lan.example/#token', 'ftp://lan.example', '']) {
    assert.throws(() => normalizeServerUrl(url));
  }
});
test('a malformed token cannot open the admin UI', () => {
  assert.equal(tokenRole('invalid'), 'USER');
  const jwt = role => `header.${Buffer.from(JSON.stringify({ role })).toString('base64url')}.signature`;
  assert.equal(tokenRole(jwt('SYSTEM_ADMIN')), 'SYSTEM_ADMIN');
  assert.equal(tokenRole(jwt('OWNER')), 'USER');
});

async function mockFetch(fetch, run) {
  const original = globalThis.fetch;
  globalThis.fetch = fetch;
  try { await run(); } finally { globalThis.fetch = original; }
}
test('JSON requests retain bearer authorization and disable cache', async () => {
  await mockFetch(async (url, options) => {
    assert.equal(url, 'http://localhost:3100/api/workspaces');
    assert.equal(options.headers.get('Authorization'), 'Bearer test');
    assert.equal(options.headers.get('Content-Type'), 'application/json');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.body, '{"name":"team"}');
    return new Response('{"data":{"id":"workspace"}}');
  }, async () => {
    assert.deepEqual(await fetchJson('http://localhost:3100', '/api/workspaces', { method: 'POST', headers: { Authorization: 'Bearer test' }, body: '{"name":"team"}' }), { data: { id: 'workspace' } });
  });
});
test('server JSON errors retain status and machine code', async () => {
  await mockFetch(async () => new Response('{"error":{"code":"NOT_MEMBER","message":"Membership required"}}', { status: 403 }), async () => {
    await assert.rejects(fetchJson('http://server', '/api/workspaces'), error => error instanceof ApiError && error.status === 403 && error.code === 'NOT_MEMBER' && error.message === 'Membership required');
  });
});
test('flat admin error envelopes are supported', async () => {
  await mockFetch(async () => new Response('{"code":"FORBIDDEN","message":"Admin required"}', { status: 403 }), async () => {
    await assert.rejects(fetchJson('http://server', '/api/admin/users'), error => error.status === 403 && error.code === 'FORBIDDEN');
  });
});
test('204 deletion responses are accepted', async () => {
  await mockFetch(async () => new Response(null, { status: 204 }), async () => {
    assert.equal(await fetchJson('http://server', '/api/messages/id', { method: 'DELETE' }), null);
  });
});
test('HTML responses cannot be mistaken for backend JSON', async () => {
  await mockFetch(async () => new Response('<html>Frontend</html>'), async () => {
    await assert.rejects(fetchJson('http://server', '/health'), error => error.code === 'INVALID_RESPONSE');
  });
});
test('network failures have a usable UI error', async () => {
  await mockFetch(async () => { throw new TypeError('fetch failed'); }, async () => {
    await assert.rejects(fetchJson('http://server', '/health'), error => error instanceof ApiError && error.code === 'NETWORK_ERROR');
  });
});
test('a cancelled workspace request cancels the underlying fetch', async () => {
  const controller = new AbortController();
  await mockFetch(async (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  }), async () => {
    const pending = fetchJson('http://server', '/api/workspaces/id', { signal: controller.signal });
    controller.abort();
    await assert.rejects(pending, error => error.code === 'ABORTED');
  });
});
test('public members do not invent email or active devices', () => {
  const member = mapMember({ id: 'member', workspaceId: 'workspace', userId: 'user', role: 'MEMBER', joinedAt: '2026-01-01', user: { id: 'user', username: 'anh', displayName: 'Anh' } });
  assert.equal(member.name, 'Anh');
  assert.equal(member.email, '');
  assert.deepEqual(member.activeSessions, []);
});
test('chat projection reflects editing and soft deletion', () => {
  const message = mapMessage({ id: 'message', senderId: 'user', channelId: 'channel', sender: { username: 'anh' }, content: '', createdAt: '2026-01-01', editedAt: '2026-01-02', deletedAt: '2026-01-03' });
  assert.equal(message.senderName, 'anh');
  assert.equal(message.isEdited, true);
  assert.equal(message.isDeleted, true);
});
test('file projection retains hash and source owner', () => {
  const file = mapFile({ id: 'file', workspaceId: 'workspace', name: 'report.pdf', size: 4096, uploaderId: 'source', mimeType: 'application/pdf', hash: 'abc', createdAt: '2026-01-01', updatedAt: '2026-01-02', storageType: 'LAN_ONLY' });
  assert.equal(file.ownerId, 'source');
  assert.equal(file.sha256, 'abc');
  assert.equal(file.modifiedAt, '2026-01-02');
});
test('pagination collects every page using actual item offsets', async () => {
  const offsets = [];
  const items = await collectPages(async offset => {
    offsets.push(offset);
    return offset === 0 ? { data: ['a', 'b'], pagination: { hasMore: true } } : { data: ['c'], pagination: { hasMore: false } };
  });
  assert.deepEqual(offsets, [0, 2]);
  assert.deepEqual(items, ['a', 'b', 'c']);
});
test('pagination rejects an endless empty response', async () => {
  await assert.rejects(collectPages(async () => ({ data: [], pagination: { hasMore: true } })), /trang trống/);
});

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const rotated = { accessToken: 'fresh', refreshToken: 'rotated', user: { id: 'user' } };
function authorized(fetcher) {
  const state = { origin: 'http://server', version: 1, tokens: { accessToken: 'old', refreshToken: 'refresh' } };
  let expires = 0;
  const request = createAuthenticatedRequester({ read: () => ({ ...state }), fetcher,
    refreshed: auth => { state.tokens = auth; },
    expired: () => { expires++; state.version++; state.tokens = null; },
  });
  return { state, request, expires: () => expires };
}
test('concurrent 401 responses rotate the refresh token once', async () => {
  const refresh = deferred(), started = deferred();
  let refreshCount = 0;
  const client = authorized(async (_origin, route, init) => {
    if (route === '/api/auth/refresh') { refreshCount++; started.resolve(); return refresh.promise; }
    if (init.headers.get('Authorization') === 'Bearer old') throw new ApiError('Expired', 401);
    return { route };
  });
  const pending = ['/one', '/two', '/three'].map(route => client.request(route));
  await started.promise;
  refresh.resolve(rotated);
  assert.deepEqual(await Promise.all(pending), [{ route: '/one' }, { route: '/two' }, { route: '/three' }]);
  assert.equal(refreshCount, 1);
  assert.equal(client.state.tokens.refreshToken, 'rotated');
});
test('a late old-token 401 reuses the token another request refreshed', async () => {
  const delayed = deferred();
  let refreshCount = 0;
  const client = authorized(async (_origin, route, init) => {
    if (route === '/api/auth/refresh') { refreshCount++; return rotated; }
    if (init.headers.get('Authorization') === 'Bearer old') {
      if (route === '/late') return delayed.promise;
      throw new ApiError('Expired', 401);
    }
    return route;
  });
  const late = client.request('/late');
  assert.equal(await client.request('/first'), '/first');
  delayed.reject(new ApiError('Expired', 401));
  assert.equal(await late, '/late');
  assert.equal(refreshCount, 1);
});
test('successful responses from a logged-out session cannot reach a new screen', async () => {
  const response = deferred();
  const client = authorized(() => response.promise);
  const pending = client.request('/private');
  client.state.version++; client.state.tokens = null;
  response.resolve({ privateData: true });
  await assert.rejects(pending, error => error.code === 'SESSION_CHANGED');
});
test('switching servers during refresh cannot restore the old account', async () => {
  const refresh = deferred(), started = deferred();
  const client = authorized(async (_origin, route) => {
    if (route === '/api/auth/refresh') { started.resolve(); return refresh.promise; }
    throw new ApiError('Expired', 401);
  });
  const pending = client.request('/private');
  await started.promise;
  client.state.version++; client.state.origin = 'http://another'; client.state.tokens = null;
  refresh.resolve(rotated);
  await assert.rejects(pending, error => error.code === 'SESSION_CHANGED');
  assert.equal(client.state.tokens, null);
  assert.equal(client.expires(), 0);
});
test('cancelling one waiter does not cancel the refresh used by other requests', async () => {
  const refresh = deferred(), started = deferred();
  const client = authorized(async (_origin, route, init) => {
    if (route === '/api/auth/refresh') { started.resolve(); return refresh.promise; }
    if (init.headers.get('Authorization') === 'Bearer old') throw new ApiError('Expired', 401);
    return route;
  });
  const controller = new AbortController();
  const cancelled = client.request('/one', { signal: controller.signal });
  const active = client.request('/two');
  await started.promise;
  controller.abort(); refresh.resolve(rotated);
  await assert.rejects(cancelled, error => error.code === 'ABORTED');
  assert.equal(await active, '/two');
  assert.equal(client.expires(), 0);
});
test('temporary refresh outages retain the session for retry', async () => {
  let refreshCount = 0;
  const client = authorized(async (_origin, route, init) => {
    if (route === '/api/auth/refresh') {
      if (++refreshCount === 1) throw new ApiError('Offline');
      return rotated;
    }
    if (init.headers.get('Authorization') === 'Bearer old') throw new ApiError('Expired', 401);
    return 'ok';
  });
  await assert.rejects(client.request('/data'), error => error.code === 'NETWORK_ERROR');
  assert.equal(client.state.tokens.refreshToken, 'refresh');
  assert.equal(client.expires(), 0);
  assert.equal(await client.request('/data'), 'ok');
});
test('invalid refresh tokens close the session', async () => {
  const client = authorized(async () => { throw new ApiError('Invalid token', 401); });
  await assert.rejects(client.request('/data'), error => error.status === 401);
  assert.equal(client.state.tokens, null);
  assert.equal(client.expires(), 1);
});
test('a retry rejected with 401 also closes the session without looping', async () => {
  let requests = 0;
  const client = authorized(async (_origin, route) => {
    if (route === '/api/auth/refresh') return rotated;
    requests++; throw new ApiError('Revoked', 401);
  });
  await assert.rejects(client.request('/data'), error => error.status === 401);
  assert.equal(requests, 2);
  assert.equal(client.expires(), 1);
});
test('403 permission errors never rotate authentication tokens', async () => {
  let requests = 0;
  const client = authorized(async () => { requests++; throw new ApiError('Forbidden', 403); });
  await assert.rejects(client.request('/admin'), error => error.status === 403);
  assert.equal(requests, 1);
  assert.equal(client.expires(), 0);
});

const tree = [
  { id: 'root', name: 'Root', parentId: null },
  { id: 'child', name: 'Child', parentId: 'root' },
  { id: 'grandchild', name: 'Grandchild', parentId: 'child' },
  { id: 'another', name: 'Another', parentId: null },
];
test('folder destinations exclude the moved folder and all descendants', () => {
  assert.deepEqual(folderDestinations(tree, 'child').map(folder => folder.id), ['root', 'another']);
  assert.deepEqual(folderDestinations(tree, 'root').map(folder => folder.id), ['another']);
  assert.equal(folderDestinations(tree).length, 4);
});
test('folder breadcrumbs terminate even if the server supplies a cycle', () => {
  const cyclic = [{ id: 'one', name: 'One', parentId: 'two' }, { id: 'two', name: 'Two', parentId: 'one' }];
  assert.equal(folderPath(cyclic, 'one').length, 2);
  assert.deepEqual(folderDestinations(cyclic, 'unrelated'), []);
});
test('contents whose parent is missing stay visible at the root', () => {
  assert.equal(visibleParent(tree, 'deleted-parent'), null);
  assert.equal(visibleParent(tree, 'child'), 'child');
  assert.equal(visibleParent(tree, null), null);
});
test('nested folders have a root-first path', () => {
  assert.deepEqual(folderPath(tree, 'grandchild').map(folder => folder.name), ['Root', 'Child', 'Grandchild']);
});
test('moving metadata to the root sends explicit null using the Java contract keys', () => {
  assert.deepEqual(metadataUpdate('file', ' report.pdf ', ''), { name: 'report.pdf', folderId: null });
  assert.deepEqual(metadataUpdate('folder', ' Docs ', 'root'), { name: 'Docs', parentId: 'root' });
  assert.throws(() => metadataUpdate('file', ' ', ''), /255/);
  assert.throws(() => metadataUpdate('folder', 'a'.repeat(256), ''), /255/);
});

test('session restore classifies network errors vs auth errors correctly', async () => {
  function handleRestoreOutcome(error, clearFn, keepFn) {
    const isAuthError = error instanceof ApiError && [401, 403].includes(error.status);
    if (isAuthError) {
      clearFn();
    } else {
      keepFn();
    }
  }

  let cleared = 0, kept = 0;
  const onClear = () => { cleared++; };
  const onKeep = () => { kept++; };

  // Network error (timeout / connection refused / status 0)
  handleRestoreOutcome(new ApiError('Network down', 0, 'NETWORK_ERROR'), onClear, onKeep);
  assert.equal(cleared, 0);
  assert.equal(kept, 1);

  // Transient 503 / 500 server reboot
  handleRestoreOutcome(new ApiError('Service Unavailable', 503, 'SERVICE_UNAVAILABLE'), onClear, onKeep);
  assert.equal(cleared, 0);
  assert.equal(kept, 2);

  // Expired / invalid refresh token (401)
  handleRestoreOutcome(new ApiError('Refresh token expired', 401, 'INVALID_TOKEN'), onClear, onKeep);
  assert.equal(cleared, 1);
  assert.equal(kept, 2);

  // Forbidden token (403)
  handleRestoreOutcome(new ApiError('Forbidden', 403, 'FORBIDDEN'), onClear, onKeep);
  assert.equal(cleared, 2);
  assert.equal(kept, 2);
});
