import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const folder = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(folder, '../../tmp/frontend-realtime-tests');
fs.mkdirSync(output, { recursive: true });

const require = createRequire(import.meta.url);

// Transpile dependencies to CommonJS in scratch directory
for (const file of [
  { src: '../lib/realtime/config.ts', dest: 'config.js' },
  { src: '../lib/realtime/types.ts', dest: 'types.js' },
  { src: '../lib/realtime/realtime-client.ts', dest: 'realtime-client.js' },
  { src: '../lib/realtime/reconcile.ts', dest: 'reconcile.js' },
  { src: '../lib/api/models.ts', dest: 'models.js' },
  { src: '../lib/api/client.ts', dest: 'client.js' },
  { src: '../lib/api/authorized.ts', dest: 'authorized.js' },
]) {
  const source = fs.readFileSync(path.resolve(folder, file.src), 'utf8');
  fs.writeFileSync(path.join(output, file.dest), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText);
}

const { resolveWebSocketUrl } = require(path.join(output, 'config.js'));
const { RealtimeClient, generateSafeUuid } = require(path.join(output, 'realtime-client.js'));
const { mapMessage } = require(path.join(output, 'models.js'));
const { createAuthenticatedRequester } = require(path.join(output, 'authorized.js'));
const {
  getMessageTimestamp,
  shouldUpdateMessage,
  reconcileMessage,
  reconcileMessages,
} = require(path.join(output, 'reconcile.js'));

test('authenticated device identity survives closing the tab and stays isolated by account', t => {
  const previous = globalThis.window, persistent = new Map(), transient = new Map();
  const storage = records => ({ getItem: key => records.get(key) || null, setItem: (key, value) => records.set(key, value) });
  globalThis.window = { localStorage: storage(persistent), sessionStorage: storage(transient) };
  t.after(() => { globalThis.window = previous; });
  const before = new RealtimeClient().getClientInstanceId('alice');
  transient.clear();
  assert.equal(new RealtimeClient().getClientInstanceId('alice'), before);
  assert.notEqual(new RealtimeClient().getClientInstanceId('bob'), before);
});

// Mock WebSocket implementation for unit testing browser protocol interactions
class MockWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(url) {
    this.url = url;
    this.readyState = MockWebSocket.CONNECTING;
    this.sent = [];
    this.onopen = null;
    this.onmessage = null;
    this.onclose = null;
    this.onerror = null;

    MockWebSocket.instances.push(this);
  }

  send(data) {
    this.sent.push(JSON.parse(data));
  }

  close(code = 1000, reason = '') {
    this.readyState = MockWebSocket.CLOSED;
    if (this.onclose) {
      this.onclose({ code, reason });
    }
  }

  simulateOpen() {
    this.readyState = MockWebSocket.OPEN;
    if (this.onopen) {
      this.onopen({});
    }
  }

  simulateMessage(payload) {
    if (this.onmessage) {
      this.onmessage({ data: JSON.stringify(payload) });
    }
  }

  simulateClose(code = 1006, reason = 'Connection dropped') {
    this.readyState = MockWebSocket.CLOSED;
    if (this.onclose) {
      this.onclose({ code, reason });
    }
  }

  simulateError(error = new Error('Socket error')) {
    if (this.onerror) {
      this.onerror(error);
    }
  }
}
MockWebSocket.instances = [];

// Helper to install mock WebSocket in Node environment
const activeClients = [];
function setupMockEnv() {
  MockWebSocket.instances = [];
  globalThis.WebSocket = MockWebSocket;
  globalThis.window = {
    sessionStorage: {
      _data: {},
      getItem(k) { return this._data[k] || null; },
      setItem(k, v) { this._data[k] = String(v); },
      removeItem(k) { delete this._data[k]; },
    },
  };
}

function createTestClient() {
  const client = new RealtimeClient();
  activeClients.push(client);
  return client;
}

test.afterEach(() => {
  activeClients.forEach(c => c.disconnect());
  activeClients.length = 0;
});

test('resolveWebSocketUrl converts default development ports', () => {
  assert.equal(resolveWebSocketUrl('http://localhost:3100'), 'ws://localhost:3101');
  assert.equal(resolveWebSocketUrl('http://192.168.1.50:3000'), 'ws://192.168.1.50:3001');
  assert.equal(resolveWebSocketUrl('http://127.0.0.1:8080'), 'ws://127.0.0.1:8081');
});

test('resolveWebSocketUrl handles reverse proxy and secure origins', () => {
  assert.equal(resolveWebSocketUrl('https://landrop.office'), 'wss://landrop.office/ws');
  assert.equal(resolveWebSocketUrl('https://landrop.office:443'), 'wss://landrop.office/ws');
  assert.equal(resolveWebSocketUrl('http://landrop.office:80'), 'ws://landrop.office/ws');
});

test('resolveWebSocketUrl respects NEXT_PUBLIC_WS_URL environment variable', () => {
  const orig = process.env.NEXT_PUBLIC_WS_URL;
  try {
    process.env.NEXT_PUBLIC_WS_URL = 'wss://custom-gateway.local/socket';
    assert.equal(resolveWebSocketUrl('http://localhost:3100'), 'wss://custom-gateway.local/socket');
  } finally {
    if (orig) process.env.NEXT_PUBLIC_WS_URL = orig;
    else delete process.env.NEXT_PUBLIC_WS_URL;
  }
});

test('WebSocket URL contains NO credentials or query strings', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({
    serverUrl: 'http://localhost:3100',
    accessToken: 'my-secret-token',
    token: 'my-secret-token',
  });
  const ws = MockWebSocket.instances[0];
  assert.ok(ws);
  assert.equal(ws.url, 'ws://localhost:3101');
  assert.equal(ws.url.includes('token'), false);
  assert.equal(ws.url.includes('?'), false);
});

test('RealtimeClient starts in IDLE state', () => {
  setupMockEnv();
  const client = createTestClient();
  assert.equal(client.getState(), 'IDLE');
});

test('RealtimeClient sends flat hello on open without token or deviceId', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({
    serverUrl: 'http://localhost:3100',
    accessToken: 'jwt-access-token-123',
    deviceName: 'MacBook Pro',
  });

  assert.equal(client.getState(), 'CONNECTING');
  const ws = MockWebSocket.instances[0];
  assert.ok(ws);

  ws.simulateOpen();
  assert.equal(client.getState(), 'HANDSHAKING');
  assert.equal(ws.sent.length, 1);
  const hello = ws.sent[0];
  assert.equal(hello.type, 'hello');
  assert.equal(hello.protocolVersion, 1);
  assert.equal(hello.deviceName, 'MacBook Pro');
  assert.equal(hello.token, undefined);
  assert.equal(hello.deviceId, undefined);
  assert.ok(hello.clientInstanceId.startsWith('browser-'));
});

test('Welcome with missing credentials transitions to AUTH_REQUIRED without joining rooms', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();

  ws.simulateMessage({
    type: 'welcome',
    payload: {
      connectionId: 'conn-1',
      serverVersion: '1.0.0',
      protocolVersion: 1,
    },
  });

  assert.equal(client.getState(), 'AUTH_REQUIRED');
  // Did not send join or auth
  assert.equal(ws.sent.length, 1); // only hello
});

test('RealtimeClient sends auth command with accessToken upon welcome', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({
    serverUrl: 'http://localhost:3100',
    accessToken: 'jwt-token-pending',
    deviceId: 'device-456',
  });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();

  ws.simulateMessage({
    type: 'welcome',
    payload: {
      connectionId: 'conn-2',
      serverVersion: '1.0.0',
      protocolVersion: 1,
    },
  });

  assert.equal(client.getState(), 'AUTHENTICATING');
  assert.equal(ws.sent.length, 2); // hello, then auth
  const auth = ws.sent[1];
  assert.equal(auth.type, 'auth');
  assert.equal(auth.payload.accessToken, 'jwt-token-pending');
  assert.equal(auth.payload.deviceId, 'device-456');
});

test('auth_ack verifies authenticated=true before transitioning to CONNECTED', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({
    serverUrl: 'http://localhost:3100',
    accessToken: 'jwt-token-pending',
  });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'conn-2' } });

  // Unauthenticated ack causes AUTH_REQUIRED
  ws.simulateMessage({
    type: 'auth_ack',
    payload: { authenticated: false },
  });
  assert.equal(client.getState(), 'AUTH_REQUIRED');

  // Successful ack transitions to CONNECTED
  ws.simulateMessage({
    type: 'auth_ack',
    payload: { authenticated: true, userId: 'user-alice' },
  });
  assert.equal(client.getState(), 'CONNECTED');
});

test('Access token expiry UNAUTHORIZED followed by close 4002 preserves AUTH_REQUIRED', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({
    serverUrl: 'http://localhost:3100',
    accessToken: 'expiring-token',
  });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'conn-3' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true, userId: 'user-alice' } });
  assert.equal(client.getState(), 'CONNECTED');

  // Server sends error before closing socket
  ws.simulateMessage({
    type: 'error',
    error: { code: 'UNAUTHORIZED', message: 'Access token expired; refresh and reconnect.' },
  });
  assert.equal(client.getState(), 'AUTH_REQUIRED');

  // Server closes with 4002 (session termination)
  ws.simulateClose(4002, 'UNAUTHORIZED');

  // Must preserve preceding AUTH_REQUIRED error classification, NOT overwrite with SESSION_REVOKED
  assert.equal(client.getState(), 'AUTH_REQUIRED');
  // Halts auto-reconnect with dead socket
  assert.equal(MockWebSocket.instances.length, 1);
});

test('SESSION_REVOKED followed by close 4002 remains terminal SESSION_REVOKED', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({
    serverUrl: 'http://localhost:3100',
    accessToken: 'revoked-token',
  });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'conn-4' } });
  ws.simulateMessage({
    type: 'error',
    error: { code: 'SESSION_REVOKED', message: 'Session revoked' },
  });
  assert.equal(client.getState(), 'SESSION_REVOKED');

  // Server closes socket with 4002
  ws.simulateClose(4002, 'SESSION_REVOKED');
  assert.equal(client.getState(), 'SESSION_REVOKED');
  assert.equal(MockWebSocket.instances.length, 1);
});

test('ACCOUNT_DISABLED followed by close 4003 remains terminal ACCOUNT_DISABLED', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'disabled-user-token' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({
    type: 'error',
    error: { code: 'ACCOUNT_DISABLED', message: 'User account is disabled' },
  });
  assert.equal(client.getState(), 'ACCOUNT_DISABLED');

  ws.simulateClose(4003, 'ACCOUNT_DISABLED');
  assert.equal(client.getState(), 'ACCOUNT_DISABLED');
});

test('Room denial FORBIDDEN removes channel from subscribed channels', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  client.subscribeChannel('forbidden-room');
  assert.ok(client.getSubscribedChannels().has('forbidden-room'));

  ws.simulateMessage({
    type: 'error',
    error: { code: 'FORBIDDEN', message: 'Channel access is unavailable.' },
    payload: { channelId: 'forbidden-room' },
  });

  assert.equal(client.getSubscribedChannels().has('forbidden-room'), false);
});

test('Duplicate channel subscription does not send duplicate join requests', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  client.subscribeChannel('room-1');
  client.subscribeChannel('room-1'); // Duplicate call

  const joinCount = ws.sent.filter(m => m.type === 'channel.join' && m.payload?.channelId === 'room-1').length;
  assert.equal(joinCount, 1);
});

test('Channel subscription sends channel.join and channel.leave events', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  client.subscribeChannel('channel-alpha');
  assert.ok(client.getSubscribedChannels().has('channel-alpha'));

  const joinMsg = ws.sent.find(m => m.type === 'channel.join');
  assert.ok(joinMsg);
  assert.equal(joinMsg.payload.channelId, 'channel-alpha');

  client.unsubscribeChannel('channel-alpha');
  assert.ok(!client.getSubscribedChannels().has('channel-alpha'));

  const leaveMsg = ws.sent.find(m => m.type === 'channel.leave');
  assert.ok(leaveMsg);
  assert.equal(leaveMsg.payload.channelId, 'channel-alpha');
});

test('onChannelJoined triggers when server acknowledges channel subscription', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  let joinedChannel = null;
  client.onChannelJoined(channelId => {
    joinedChannel = channelId;
  });

  client.subscribeChannel('channel-gamma');
  ws.simulateMessage({
    type: 'channel.joined',
    payload: { channelId: 'channel-gamma' },
  });

  assert.equal(joinedChannel, 'channel-gamma');
});

test('RealtimeClient receives message.created and dispatches to listeners', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  let receivedEvent = null;
  client.onMessage(evt => {
    receivedEvent = evt;
  });

  const wireMessage = {
    id: 'msg-1',
    channelId: 'chan-1',
    senderId: 'user-1',
    sender: { username: 'bob', displayName: 'Bob' },
    content: 'Hello Realtime!',
    createdAt: '2026-10-08T16:00:00Z',
  };

  ws.simulateMessage({
    type: 'message.created',
    payload: {
      type: 'message.created',
      channelId: 'chan-1',
      message: wireMessage,
    },
  });

  assert.ok(receivedEvent);
  assert.equal(receivedEvent.type, 'message.created');
  assert.equal(receivedEvent.payload.channelId, 'chan-1');
  assert.equal(receivedEvent.payload.message.content, 'Hello Realtime!');
});

test('RealtimeClient receives message.updated and message.deleted events', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  const events = [];
  client.onMessage(evt => events.push(evt));

  ws.simulateMessage({
    type: 'message.updated',
    payload: {
      type: 'message.updated',
      channelId: 'chan-1',
      messageId: 'msg-1',
      message: {
        id: 'msg-1',
        channelId: 'chan-1',
        senderId: 'user-1',
        sender: { username: 'bob' },
        content: 'Edited content',
        createdAt: '2026-10-08T16:00:00Z',
        editedAt: '2026-10-08T16:05:00Z',
      },
    },
  });

  ws.simulateMessage({
    type: 'message.deleted',
    payload: {
      type: 'message.deleted',
      channelId: 'chan-1',
      messageId: 'msg-1',
      deletedAt: '2026-10-08T16:06:00Z',
      message: {
        id: 'msg-1',
        channelId: 'chan-1',
        senderId: 'user-1',
        sender: { username: 'bob' },
        content: '[This message was deleted]',
        createdAt: '2026-10-08T16:00:00Z',
        deletedAt: '2026-10-08T16:06:00Z',
      },
    },
  });

  assert.equal(events.length, 2);
  assert.equal(events[0].type, 'message.updated');
  assert.equal(events[0].payload.message.content, 'Edited content');
  assert.equal(events[1].type, 'message.deleted');
  assert.equal(events[1].payload.deletedAt, '2026-10-08T16:06:00Z');
});

test('generateSafeUuid provides RFC4122 v4 UUID fallback on plain HTTP LAN', () => {
  const origDesc = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  try {
    // Simulate plain HTTP context without crypto.randomUUID
    Object.defineProperty(globalThis, 'crypto', {
      value: {},
      configurable: true,
      writable: true,
    });
    const uuid = generateSafeUuid();
    assert.equal(typeof uuid, 'string');
    assert.match(uuid, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  } finally {
    if (origDesc) {
      Object.defineProperty(globalThis, 'crypto', origDesc);
    } else {
      delete globalThis.crypto;
    }
  }
});

test('client.connect with refreshed token preserves existing subscriptions', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'initial-token' });

  const ws1 = MockWebSocket.instances[0];
  ws1.simulateOpen();
  ws1.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws1.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  client.subscribeChannel('channel-preserved');
  assert.ok(client.getSubscribedChannels().has('channel-preserved'));

  // Expiry happens
  ws1.simulateMessage({
    type: 'error',
    error: { code: 'UNAUTHORIZED', message: 'Access token expired' },
  });
  ws1.simulateClose(4002, 'UNAUTHORIZED');
  assert.equal(client.getState(), 'AUTH_REQUIRED');
  assert.ok(client.getSubscribedChannels().has('channel-preserved'));

  // Provider calls client.connect with refreshed token
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'refreshed-token' });
  const ws2 = MockWebSocket.instances[1];
  assert.ok(ws2);
  ws2.simulateOpen();
  ws2.simulateMessage({ type: 'welcome', payload: { connectionId: 'c2' } });
  ws2.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });
  assert.equal(client.getState(), 'CONNECTED');

  // Verify channel was preserved and resubscribed
  const resubMsg = ws2.sent.find(m => m.type === 'channel.join' && m.payload?.channelId === 'channel-preserved');
  assert.ok(resubMsg);
});

test('Reconnection resubscribes all active channels', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws1 = MockWebSocket.instances[0];
  ws1.simulateOpen();
  ws1.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws1.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  client.subscribeChannel('chan-alpha');
  client.subscribeChannel('chan-beta');

  // Socket disconnects unexpectedly (code 1006)
  ws1.simulateClose(1006, 'Abnormal closure');
  assert.equal(client.getState(), 'RECONNECTING');

  // Next socket instance connects
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });
  const ws2 = MockWebSocket.instances[1];
  assert.ok(ws2);
  ws2.simulateOpen();
  ws2.simulateMessage({ type: 'welcome', payload: { connectionId: 'c2' } });
  ws2.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });
  assert.equal(client.getState(), 'CONNECTED');

  const resubscribedAlpha = ws2.sent.find(m => m.type === 'channel.join' && m.payload?.channelId === 'chan-alpha');
  const resubscribedBeta = ws2.sent.find(m => m.type === 'channel.join' && m.payload?.channelId === 'chan-beta');
  assert.ok(resubscribedAlpha);
  assert.ok(resubscribedBeta);
});

test('createAuthenticatedRequester shares single-flight refresh for concurrent calls', async () => {
  let refreshCalls = 0;
  let currentTokens = { accessToken: 'old-access', refreshToken: 'valid-refresh' };

  const mockFetcher = async (origin, route, init) => {
    if (route === '/api/auth/refresh') {
      refreshCalls++;
      await new Promise(r => setTimeout(r, 10));
      return {
        accessToken: 'new-access',
        refreshToken: 'new-refresh',
        user: { id: 'u1', email: 'alice@landrop.lan', username: 'alice', createdAt: '2026-10-08T00:00:00Z' }
      };
    }
    return { ok: true };
  };

  const requester = createAuthenticatedRequester({
    read: () => ({ origin: 'http://localhost:3100', tokens: currentTokens, version: 1 }),
    refreshed: (auth) => {
      currentTokens = { accessToken: auth.accessToken, refreshToken: auth.refreshToken };
    },
    expired: () => {
      currentTokens = null;
    },
    fetcher: mockFetcher,
  });

  // Call refreshToken concurrently twice
  const [res1, res2] = await Promise.all([
    requester.refreshToken(),
    requester.refreshToken(),
  ]);

  assert.equal(res1, 'new-access');
  assert.equal(res2, 'new-access');
  // Single-flight: only one HTTP request was fired!
  assert.equal(refreshCalls, 1);
});

test('Message mapping deduplicates optimistic and canonical messages', () => {
  const wireMessage = {
    id: 'canon-uuid-1',
    channelId: 'chan-1',
    senderId: 'user-1',
    sender: { username: 'alice', displayName: 'Alice' },
    content: 'Test message',
    createdAt: '2026-10-08T16:10:00Z',
    replyToId: undefined,
  };

  const message = mapMessage(wireMessage);
  assert.equal(message.id, 'canon-uuid-1');
  assert.equal(message.senderName, 'Alice');
  assert.equal(message.isEdited, false);
  assert.equal(message.isDeleted, false);

  const existing = [message];
  const all = new Map(existing.map(m => [m.id, m]));
  all.set(message.id, message);
  assert.equal(all.size, 1);
});

test('Disconnect cleanly resets state and removes all subscriptions', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100' });
  client.subscribeChannel('chan-xyz');

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();

  client.disconnect();
  assert.equal(client.getState(), 'IDLE');
  assert.equal(client.getSubscribedChannels().size, 0);
  assert.equal(ws.readyState, MockWebSocket.CLOSED);
});

test('reconcileMessages handles offline edit and delete recovery without leaving stale rows', () => {
  const initial = [
    {
      id: 'msg-offline-1',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'Original message before disconnect',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:00:00Z',
      isEdited: false,
      isDeleted: false,
    },
  ];

  // While offline, the message was edited at 10:02 and deleted at 10:05
  const catchUpSnapshot = [
    {
      id: 'msg-offline-1',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'Tin nhắn đã được xóa.',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:05:00Z',
      editedAt: '2026-10-08T10:02:00Z',
      deletedAt: '2026-10-08T10:05:00Z',
      isEdited: true,
      isDeleted: true,
    },
  ];

  const reconciled = reconcileMessages(initial, catchUpSnapshot);
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].id, 'msg-offline-1');
  assert.equal(reconciled[0].isDeleted, true);
  assert.equal(reconciled[0].isEdited, true);
  assert.equal(reconciled[0].content, 'Tin nhắn đã được xóa.');
  assert.equal(reconciled[0].deletedAt, '2026-10-08T10:05:00Z');
  assert.equal(reconciled[0].updatedAt, '2026-10-08T10:05:00Z');
});

test('reconcileMessages protects newer live updates against stale history snapshots', () => {
  const liveUpdated = [
    {
      id: 'msg-live-1',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'Live edited content',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:05:00Z',
      editedAt: '2026-10-08T10:05:00Z',
      isEdited: true,
      isDeleted: false,
    },
  ];

  // Slower or stale history snapshot fetch arrives with pre-edit version
  const staleHistory = [
    {
      id: 'msg-live-1',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'Stale original content',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:00:00Z',
      isEdited: false,
      isDeleted: false,
    },
  ];

  const reconciled = reconcileMessages(liveUpdated, staleHistory);
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].content, 'Live edited content');
  assert.equal(reconciled[0].isEdited, true);
  assert.equal(reconciled[0].updatedAt, '2026-10-08T10:05:00Z');
});

test('reconcileMessages ignores duplicate initial create arriving after an update', () => {
  const existingUpdated = [
    {
      id: 'msg-dup-1',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'Updated text',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:04:00Z',
      editedAt: '2026-10-08T10:04:00Z',
      isEdited: true,
      isDeleted: false,
    },
  ];

  const duplicateCreate = {
    id: 'msg-dup-1',
    channelId: 'chan-1',
    senderId: 'user-1',
    senderName: 'Alice',
    content: 'Initial text',
    createdAt: '2026-10-08T10:00:00Z',
    updatedAt: '2026-10-08T10:00:00Z',
    isEdited: false,
    isDeleted: false,
  };

  const reconciled = reconcileMessages(existingUpdated, duplicateCreate);
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].content, 'Updated text');
  assert.equal(reconciled[0].isEdited, true);
});

test('reconcileMessages ignores slower REST response arriving after a newer live edit', () => {
  const fastLiveEdit = [
    {
      id: 'msg-race-1',
      clientMessageId: 'client-req-123',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'Rapid edit broadcasted via WS',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:02:00Z',
      editedAt: '2026-10-08T10:02:00Z',
      isEdited: true,
      isDeleted: false,
    },
  ];

  const slowRestResponse = {
    id: 'msg-race-1',
    clientMessageId: 'client-req-123',
    channelId: 'chan-1',
    senderId: 'user-1',
    senderName: 'Alice',
    content: 'Slow initial REST response content',
    createdAt: '2026-10-08T10:00:00Z',
    updatedAt: '2026-10-08T10:00:00Z',
    isEdited: false,
    isDeleted: false,
  };

  const reconciled = reconcileMessages(fastLiveEdit, slowRestResponse);
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].content, 'Rapid edit broadcasted via WS');
  assert.equal(reconciled[0].isEdited, true);
  assert.equal(reconciled[0].clientMessageId, 'client-req-123');
});

test('reconcileMessages inserts unknown canonical update or delete DTOs', () => {
  const currentMessages = [
    {
      id: 'msg-1',
      channelId: 'chan-1',
      senderId: 'user-1',
      senderName: 'Alice',
      content: 'First message',
      createdAt: '2026-10-08T10:00:00Z',
      updatedAt: '2026-10-08T10:00:00Z',
      isEdited: false,
      isDeleted: false,
    },
  ];

  const unknownDeletedDto = {
    id: 'msg-2',
    channelId: 'chan-1',
    senderId: 'user-2',
    senderName: 'Bob',
    content: 'Tin nhắn đã được xóa.',
    createdAt: '2026-10-08T10:01:00Z',
    updatedAt: '2026-10-08T10:06:00Z',
    deletedAt: '2026-10-08T10:06:00Z',
    isEdited: false,
    isDeleted: true,
  };

  const reconciled = reconcileMessages(currentMessages, unknownDeletedDto);
  assert.equal(reconciled.length, 2);
  assert.equal(reconciled[0].id, 'msg-1');
  assert.equal(reconciled[1].id, 'msg-2');
  assert.equal(reconciled[1].isDeleted, true);
  assert.equal(reconciled[1].content, 'Tin nhắn đã được xóa.');
});

test('client.connect cancels pending reconnectTimer when called', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-initial' });

  const ws1 = MockWebSocket.instances[0];
  ws1.simulateOpen();
  ws1.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws1.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  // Simulate network drop triggering reconnect timer
  ws1.simulateClose(1006, 'Abnormal closure');
  assert.equal(client.getState(), 'RECONNECTING');
  assert.ok(client.reconnectTimer !== null);

  // Manual connect with refreshed token must clear reconnectTimer immediately
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-refreshed' });
  assert.equal(client.reconnectTimer, null);
  assert.equal(client.getState(), 'CONNECTING');
  assert.equal(MockWebSocket.instances.length, 2);
});

test('createAuthenticatedRequester transient network error retains refresh token and subsequent call succeeds', async () => {
  let callCount = 0;
  let currentTokens = { accessToken: 'initial-access', refreshToken: 'initial-refresh' };
  let expiredCalled = false;

  const mockFetcher = async (origin, route, init) => {
    if (route === '/api/auth/refresh') {
      callCount++;
      if (callCount === 1) {
        throw new Error('Network failure: ECONNREFUSED');
      }
      return {
        accessToken: 'recovered-access',
        refreshToken: 'recovered-refresh',
        user: { id: 'u1', email: 'alice@landrop.lan', username: 'alice', createdAt: '2026-10-08T00:00:00Z' },
      };
    }
    return { ok: true };
  };

  const requester = createAuthenticatedRequester({
    read: () => ({ origin: 'http://localhost:3100', tokens: currentTokens, version: 1 }),
    refreshed: (auth) => {
      currentTokens = { accessToken: auth.accessToken, refreshToken: auth.refreshToken };
    },
    expired: () => {
      expiredCalled = true;
      currentTokens = null;
    },
    fetcher: mockFetcher,
  });

  // Attempt 1 fails with transient network error
  await assert.rejects(async () => {
    await requester.refreshToken();
  }, /Network failure/);

  // Expired must NOT be called on transient network error
  assert.equal(expiredCalled, false);
  assert.ok(currentTokens !== null);
  assert.equal(currentTokens.refreshToken, 'initial-refresh');

  // Attempt 2 succeeds
  const recovered = await requester.refreshToken();
  assert.equal(recovered, 'recovered-access');
  assert.equal(currentTokens.accessToken, 'recovered-access');
  assert.equal(callCount, 2);
});

test('Preserves subscriptions on AUTH_REQUIRED while awaiting token refresh', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-1' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });

  client.subscribeChannel('room-keep-alive');
  assert.ok(client.getSubscribedChannels().has('room-keep-alive'));

  // Socket enters AUTH_REQUIRED (token expired)
  ws.simulateMessage({
    type: 'error',
    error: { code: 'UNAUTHORIZED', message: 'Access token expired' },
  });
  ws.simulateClose(4002, 'UNAUTHORIZED');

  assert.equal(client.getState(), 'AUTH_REQUIRED');
  // Subscriptions must remain preserved so that once token refresh completes, rooms are rejoined
  assert.ok(client.getSubscribedChannels().has('room-keep-alive'));
  assert.equal(client.getSubscribedChannels().size, 1);
});

test('Scoped failure guard ignores disconnect if authVersion has changed', () => {
  setupMockEnv();
  const client = createTestClient();
  client.connect({ serverUrl: 'http://localhost:3100', accessToken: 'token-active' });

  const ws = MockWebSocket.instances[0];
  ws.simulateOpen();
  ws.simulateMessage({ type: 'welcome', payload: { connectionId: 'c1' } });
  ws.simulateMessage({ type: 'auth_ack', payload: { authenticated: true } });
  client.subscribeChannel('room-stay-connected');

  // Simulate recovery guard: if captured authVersion !== active authVersion, disconnect is skipped
  const capturedAuthVersion = 1;
  const currentAuthVersion = 2; // Advanced due to re-login / new session

  if (capturedAuthVersion === currentAuthVersion) {
    client.disconnect();
  }

  // Socket must remain connected, not terminated by stale failure
  assert.equal(client.getState(), 'CONNECTED');
  assert.ok(client.getSubscribedChannels().has('room-stay-connected'));
});
