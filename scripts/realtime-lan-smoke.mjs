// Cross-stack acceptance: the actual frontend RealtimeClient, real sockets and full Java bootstrap.
// No browser UI or second physical machine is simulated by this test.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, mkdirSync, createWriteStream } from 'node:fs';
import { resolve, join, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const require = createRequire(join(root, 'frontend/package.json'));
const ts = require('typescript');
const NativeWebSocket = require('next/dist/compiled/ws');
const serverHost = process.env.LANDROP_LAN_HOST || '127.0.0.1';
const apiOrigin = `http://${serverHost}:3100`;
const browserOrigin = `http://${serverHost}:3000`;
if (process.env.LANDROP_REALTIME_TEST_DATABASE !== 'true') throw new Error('Set LANDROP_REALTIME_TEST_DATABASE=true to opt in');
if (!process.env.JAVA_HOME) throw new Error('Set JAVA_HOME to JDK 21');
const output = join(root, 'tmp/realtime01-live-client');
mkdirSync(output, { recursive: true });
const resultPath = join(root, 'tmp/realtime01-lan-smoke-result.json');
writeFileSync(resultPath, JSON.stringify({ status: 'RUNNING', timestamp: new Date().toISOString() }, null, 2));
for (const name of ['config', 'types', 'realtime-client', 'reconcile']) {
  const source = readFileSync(join(root, `frontend/lib/realtime/${name}.ts`), 'utf8');
  writeFileSync(join(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText);
}
writeFileSync(join(output, 'models.js'), ts.transpileModule(readFileSync(join(root, 'frontend/lib/api/models.ts'), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText);
const { RealtimeClient } = require(join(output, 'realtime-client.js'));
const { reconcileMessages } = require(join(output, 'reconcile.js'));
const { mapMessage } = require(join(output, 'models.js'));
const runtimeLog = createWriteStream(join(root, 'tmp/realtime01-lan-runtime.log'));
const dependencies = readFileSync(join(root, 'backend/target/realtime01-classpath.txt'), 'utf8').trim();
const java = join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const runtime = spawn(java, ['--class-path', [join(root, 'backend/target/classes'), dependencies].join(delimiter),
  join(root, 'scripts/Realtime01LanHarness.java')], {
  cwd: root, windowsHide: true,
  env: { ...process.env, WS_ALLOWED_ORIGINS: [browserOrigin, 'http://localhost:3000', 'http://127.0.0.1:3000'].join(',') },
  stdio: ['pipe', 'pipe', 'pipe'],
});
let runtimeText = '', runtimeExit = null;
runtime.stdout.on('data', data => { runtimeText += data; runtimeLog.write(data); });
runtime.stderr.on('data', data => { runtimeLog.write(data); });
runtime.on('exit', code => { runtimeExit = code; });
runtime.on('error', error => { runtimeText += `\nSPAWN_FAILED ${error.message}`; runtimeExit = -1; });
const sleep = ms => new Promise(done => setTimeout(done, ms));
async function until(predicate, description, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Timeout: ${description}`);
    await sleep(25);
  }
}
async function marker(value, start = 0) {
  await until(() => runtimeText.slice(start).includes(value) || runtimeExit !== null, value, 90000);
  assert.ok(runtimeText.slice(start).includes(value), `${value}; see tmp/realtime01-lan-runtime.log`);
}
async function command(value, expected) {
  const start = runtimeText.length;
  runtime.stdin.write(`${value}\n`);
  await marker(expected, start);
}
async function api(method, path, token, body, status = 200) {
  const response = await fetch(apiOrigin + path, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  assert.equal(response.status, status, `${method} ${path}`);
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}
const sockets = [];
class BrowserTransport extends NativeWebSocket {
  constructor(url) {
    assert.equal(new URL(url).search, '', 'WebSocket URL must not carry credentials');
    super(url, { origin: browserOrigin });
    this.received = [];
    this.sentTypes = [];
    sockets.push(this);
    this.on('message', data => this.received.push(JSON.parse(String(data))));
    this.on('error', () => {});
  }
  send(data, ...args) {
    const frame = JSON.parse(data);
    this.sentTypes.push(frame.type);
    if (frame.type === 'auth') assert.ok(frame.payload.accessToken, 'Agreed auth payload.accessToken');
    if (frame.type === 'hello') assert.ok(!frame.token, 'Hello must not duplicate credentials');
    return super.send(data, ...args);
  }
}
globalThis.WebSocket = BrowserTransport;
const clients = [];
function client(name, auth) {
  const storage = new Map();
  globalThis.window = { sessionStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value) } };
  const instance = new RealtimeClient();
  const events = [];
  const state = { rows: [] };
  instance.onMessage(event => {
    events.push({ ...event, receivedAt: Date.now() });
    if (event.payload.message) state.rows = reconcileMessages(state.rows, mapMessage(event.payload.message));
  });
  instance.connect({ serverUrl: apiOrigin, accessToken: auth.accessToken, deviceName: name });
  clients.push(instance);
  return { instance, events, state, auth, socket: () => sockets.findLast(s => s.received.some(e => e.type === 'auth_ack' && e.payload?.userId === auth.user.id)) };
}
async function connected(c) { await until(() => c.instance.getState() === 'CONNECTED', 'authenticated client'); }
async function joinChannel(c, channel) {
  const before = c.socket().received.length;
  c.instance.subscribeChannel(channel);
  await until(() => c.socket().received.slice(before).some(e => e.type === 'channel.joined' && e.payload.channelId === channel), 'channel joined');
}
async function event(c, type, id) {
  await until(() => c.events.some(e => e.type === type && e.payload.message?.id === id), type);
  return c.events.find(e => e.type === type && e.payload.message?.id === id);
}
const passed = [];
function pass(name) { passed.push(name); console.log(`PASS ${name}`); }
try {
  await marker('RT01_READY');
  const fixture = JSON.parse(readFileSync(join(root, 'tmp/realtime01-lan-fixtures.json'), 'utf8'));
  assert.equal((await api('GET', '/health')).status, 'ok');
  const anhAuth = await api('POST', '/api/auth/login', null, { emailOrUsername: fixture.anh, password: fixture.password });
  let kenAuth = await api('POST', '/api/auth/login', null, { emailOrUsername: fixture.ken, password: fixture.password });
  assert.equal(anhAuth.user.displayName, 'Anh'); assert.equal(kenAuth.user.displayName, 'Ken');
  const anh = client('Anh', anhAuth), ken = client('Ken', kenAuth);
  await Promise.all([connected(anh), connected(ken)]);
  await Promise.all([joinChannel(anh, fixture.channel), joinChannel(ken, fixture.channel)]);
  pass('two independent signed browser-style clients: Anh and Ken');
  const joins = anh.socket().sentTypes.filter(t => t === 'channel.join').length;
  anh.instance.subscribeChannel(fixture.channel);
  await sleep(100);
  assert.equal(anh.socket().sentTypes.filter(t => t === 'channel.join').length, joins);
  pass('duplicate subscription sends no duplicate listener/join');
  const sendAt = Date.now(), clientMessageId = crypto.randomUUID();
  const created = await api('POST', `/api/channels/${fixture.channel}/messages`, anhAuth.accessToken,
    { content: 'Anh to Ken realtime acceptance', clientMessageId }, 201);
  const message = created.data;
  await Promise.all([event(anh, 'message.created', message.id), event(ken, 'message.created', message.id)]);
  const latencyMs = (await event(ken, 'message.created', message.id)).receivedAt - sendAt;
  assert.equal((await api('POST', `/api/channels/${fixture.channel}/messages`, anhAuth.accessToken,
    { content: message.content, clientMessageId }, 201)).data.id, message.id);
  await sleep(200);
  for (const c of [anh, ken]) assert.equal(c.events.filter(e => e.type === 'message.created' && e.payload.message.id === message.id).length, 1);
  anh.state.rows = reconcileMessages(anh.state.rows, mapMessage(message));
  assert.equal(anh.state.rows.filter(m => m.id === message.id).length, 1);
  await command(`COUNT ${message.id}`, 'RT01_COUNT 1');
  pass('canonical two-user delivery, sender deduplication, SQL row count 1');
  await api('PATCH', `/api/messages/${message.id}`, anhAuth.accessToken, { content: 'edited realtime acceptance' });
  await Promise.all([event(anh, 'message.updated', message.id), event(ken, 'message.updated', message.id)]);
  await api('DELETE', `/api/messages/${message.id}`, anhAuth.accessToken, null, 204);
  await Promise.all([event(anh, 'message.deleted', message.id), event(ken, 'message.deleted', message.id)]);
  pass('edit/delete canonical events reach both clients');
  ken.instance.unsubscribeChannel(fixture.channel);
  await until(() => ken.socket().received.some(e => e.type === 'channel.left' && e.payload.channelId === fixture.channel), 'channel left');
  await joinChannel(ken, fixture.otherChannel);
  const isolated = (await api('POST', `/api/channels/${fixture.channel}/messages`, anhAuth.accessToken,
    { content: 'channel isolation', clientMessageId: crypto.randomUUID() }, 201)).data;
  await event(anh, 'message.created', isolated.id); await sleep(300);
  assert.ok(!ken.events.some(e => e.payload.message?.id === isolated.id));
  pass('different channel receives no message');
  anh.instance.subscribeChannel(fixture.deniedChannel);
  await until(() => anh.socket().received.some(e => e.type === 'error' && (e.error?.code || e.code) === 'FORBIDDEN'), 'denied channel');
  await api('GET', `/api/channels/${fixture.deniedChannel}/messages`, anhAuth.accessToken, null, 404);
  const foreign = (await api('POST', `/api/channels/${fixture.deniedChannel}/messages`, kenAuth.accessToken,
    { content: 'workspace isolation', clientMessageId: crypto.randomUUID() }, 201)).data;
  await sleep(300); assert.ok(!anh.events.some(e => e.payload.message?.id === foreign.id));
  anh.instance.unsubscribeChannel(fixture.deniedChannel);
  pass('cross-workspace subscription and REST access denied');
  const oldSocket = ken.socket(); oldSocket.terminate();
  await until(() => ken.socket() && ken.socket() !== oldSocket && ken.instance.getState() === 'CONNECTED', 'automatic reconnect');
  await until(() => ken.socket().received.some(e => e.type === 'channel.joined' && e.payload.channelId === fixture.otherChannel), 'automatic rejoin');
  ken.instance.unsubscribeChannel(fixture.otherChannel); await joinChannel(ken, fixture.channel);
  const durable = (await api('POST', `/api/channels/${fixture.channel}/messages`, kenAuth.accessToken,
    { content: 'Ken restart persistence', clientMessageId: crypto.randomUUID() }, 201)).data;
  await Promise.all([event(anh, 'message.created', durable.id), event(ken, 'message.created', durable.id)]);
  pass('network disconnect, authenticated reconnect/rejoin, Ken sends to Anh');
  ken.instance.disconnect();
  await api('PATCH', `/api/messages/${durable.id}`, kenAuth.accessToken, { content: 'edited while Ken transport offline' });
  await event(anh, 'message.updated', durable.id);
  let recovery = await api('GET', `/api/channels/${fixture.channel}/messages?order=desc&limit=50`, kenAuth.accessToken);
  ken.state.rows = reconcileMessages(ken.state.rows, recovery.items.map(mapMessage));
  assert.equal(ken.state.rows.find(m => m.id === durable.id).content, 'edited while Ken transport offline');
  await api('DELETE', `/api/messages/${durable.id}`, kenAuth.accessToken, null, 204);
  await event(anh, 'message.deleted', durable.id);
  recovery = await api('GET', `/api/channels/${fixture.channel}/messages?order=desc&limit=50`, kenAuth.accessToken);
  ken.state.rows = reconcileMessages(ken.state.rows, recovery.items.map(mapMessage));
  assert.equal(ken.state.rows.find(m => m.id === durable.id).isDeleted, true);
  ken.state.rows = reconcileMessages(ken.state.rows, mapMessage(durable));
  assert.equal(ken.state.rows.find(m => m.id === durable.id).isDeleted, true, 'stale REST/create cannot resurrect deleted row');
  ken.instance.connect({ serverUrl: apiOrigin, accessToken: kenAuth.accessToken, deviceName: 'Ken' });
  await connected(ken); await joinChannel(ken, fixture.channel);
  pass('actual Chat reconciliation recovers offline edit/delete and rejects stale snapshot');
  const beforeRefresh = ken.socket();
  kenAuth = await api('POST', '/api/auth/refresh', null, { refreshToken: kenAuth.refreshToken });
  ken.instance.connect({ serverUrl: apiOrigin, accessToken: kenAuth.accessToken, deviceName: 'Ken' });
  await until(() => ken.socket() && ken.socket() !== beforeRefresh && ken.instance.getState() === 'CONNECTED', 'fresh credential binding');
  await until(() => ken.socket().received.some(e => e.type === 'channel.joined' && e.payload.channelId === fixture.channel), 'refresh preserves room');
  pass('real REST refresh, new session auth and retained channel subscription');
  const oldAnh = anh.socket(), oldKen = ken.socket();
  await command('RESTART', 'RT01_RESTARTED');
  for (const [c, old] of [[anh, oldAnh], [ken, oldKen]]) {
    await until(() => c.socket() && c.socket() !== old && c.instance.getState() === 'CONNECTED', 'restart reconnect');
    await until(() => c.socket().received.some(e => e.type === 'channel.joined' && e.payload.channelId === fixture.channel), 'restart rejoin');
  }
  const history = await api('GET', `/api/channels/${fixture.channel}/messages?order=desc&limit=50`, anhAuth.accessToken);
  assert.ok(history.items.some(m => m.id === durable.id));
  await command(`COUNT ${durable.id}`, 'RT01_COUNT 1');
  pass('full backend restart, client reauth/rejoin and persistent history/SQL identity');
  let removedListenerCalls = 0;
  const removeListener = ken.instance.onMessage(() => removedListenerCalls++); removeListener();
  const after = (await api('POST', `/api/channels/${fixture.channel}/messages`, anhAuth.accessToken,
    { content: 'listener cleanup', clientMessageId: crypto.randomUUID() }, 201)).data;
  await event(ken, 'message.created', after.id); assert.equal(removedListenerCalls, 0);
  pass('listener unsubscribe cleanup');
  await until(() => ken.socket().received.some(e => e.type === 'heartbeat_ack'), 'real heartbeat acknowledgement');
  pass('real heartbeat/ack lifecycle');
  await command('REMOVE_KEN', 'RT01_MEMBER_REMOVED');
  const denied = (await api('POST', `/api/channels/${fixture.channel}/messages`, anhAuth.accessToken,
    { content: 'removed member isolation', clientMessageId: crypto.randomUUID() }, 201)).data;
  await event(anh, 'message.created', denied.id); await sleep(300);
  assert.ok(!ken.events.some(e => e.payload.message?.id === denied.id));
  await api('POST', `/api/channels/${fixture.channel}/messages`, kenAuth.accessToken,
    { content: 'must fail', clientMessageId: crypto.randomUUID() }, 404);
  pass('membership removal immediately blocks delivery and writes');
  await api('POST', '/api/auth/logout', kenAuth.accessToken, { refreshToken: kenAuth.refreshToken });
  await until(() => ken.instance.getState() === 'SESSION_REVOKED', 'logout revocation');
  const socketCount = sockets.length; await sleep(1800); assert.equal(sockets.length, socketCount);
  assert.equal(anh.instance.getState(), 'CONNECTED');
  pass('logout closes Ken terminally while Anh stays connected');
  const result = { timestamp: new Date().toISOString(), scope: 'Two actual RealtimeClient instances on one machine; no browser DOM/two-machine claim',
    apiOrigin, browserOrigin, passed, initialDeliveryMs: latencyMs, status: 'PASS', cleanup: 'pending' };
  for (const c of clients) c.disconnect();
  await command('STOP', 'RT01_CLEANED');
  await until(() => runtimeExit !== null, 'runtime exit'); assert.equal(runtimeExit, 0);
  result.cleanup = 'isolated schema dropped and absence verified';
  writeFileSync(resultPath, JSON.stringify(result, null, 2));
  console.log(`PASS ${passed.length} integration scenarios; initial delivery ${latencyMs} ms; schema removed`);
} catch (error) {
  writeFileSync(resultPath, JSON.stringify({ status: 'FAIL', timestamp: new Date().toISOString(), passed, error: error.message }, null, 2));
  throw error;
} finally {
  for (const c of clients) c.disconnect();
  if (runtimeExit === null) {
    runtime.stdin.write('STOP\n'); runtime.stdin.end();
    await marker('RT01_CLEANED');
    await until(() => runtimeExit !== null, 'cleanup runtime exit');
  }
  runtimeLog.end();
}
