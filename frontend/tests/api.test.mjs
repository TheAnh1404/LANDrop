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
for (const file of ['client', 'models', 'pagination']) {
  const source = fs.readFileSync(path.resolve(folder, `../lib/api/${file}.ts`), 'utf8');
  fs.writeFileSync(path.join(output, `${file}.cjs`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText);
}
const { ApiError, normalizeServerUrl, fetchJson, tokenRole } = require(path.join(output, 'client.cjs'));
const { mapMessage, mapMember, mapFile } = require(path.join(output, 'models.cjs'));
const { collectPages } = require(path.join(output, 'pagination.cjs'));

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
    assert.equal(options.headers.Authorization, 'Bearer test');
    assert.equal(options.headers['Content-Type'], 'application/json');
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
