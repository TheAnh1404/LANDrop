import test from 'node:test';
import assert from 'node:assert/strict';
import { File } from 'node:buffer';
import { createRequire } from 'node:module';
import { build } from 'esbuild';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const folder = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(folder, '../node_modules/.cache/p2p-tests/core.cjs');
await build({ entryPoints: [path.resolve(folder, '../lib/p2p/engine.js')], outfile: output, bundle: true, platform: 'node', format: 'cjs' });
const { ReliablePeer, LocalStore, FileIO, MEMORY_LIMIT } = createRequire(import.meta.url)(output);
const protocolOutput = path.resolve(folder, '../node_modules/.cache/p2p-tests/protocol.cjs');
await build({ entryPoints: [path.resolve(folder, '../lib/p2p/protocol.js')], outfile: protocolOutput, bundle: true, platform: 'node', format: 'cjs' });
const { uuid, encodeFrame, decodeFrame, hashBytes } = createRequire(import.meta.url)(protocolOutput);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, timeout = 3000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) { if (Date.now() > deadline) throw new Error('Condition timed out'); await sleep(5); }
}
function connect(a, b, filter = () => true) {
  const packets = [];
  function channel(from, to, lane) {
    return { readyState: 'open', bufferedAmount: 0, send(data) {
      const packet = typeof data === 'string' ? JSON.parse(data) : { type: 'BINARY' };
      packets.push({ from, lane, ...packet });
      if (filter(from, packet, data)) queueMicrotask(() => to.receive(data instanceof ArrayBuffer ? data.slice(0) : data));
    } };
  }
  a.attach(channel('a', b, 'control'), channel('a', b, 'file'));
  b.attach(channel('b', a, 'control'), channel('b', a, 'file'));
  return packets;
}
function pair(t, overrides = {}) {
  const errors = [], received = [];
  const defaults = { timeoutMs: 400, offerTimeoutMs: 400, finishTimeoutMs: 100, onError: error => errors.push(error) };
  const a = new ReliablePeer({ scope: 'server|alice|workspace|bob|device-b', ...defaults, ...overrides.a });
  const b = new ReliablePeer({ scope: 'server|bob|workspace|alice|device-a', ...defaults, onReceived: file => received.push(file), autoAccept: true, ...overrides.b });
  t.after(() => { a.dispose(); b.dispose(); });
  return { a, b, errors, received };
}
const source = (size = 37, name = 'kiểm tra.bin') => new File([Uint8Array.from({ length: size }, (_, index) => index % 251)], name);

test('100 offline messages persist and deliver exactly once after reconnect and lost ACK', async t => {
  const { a, b } = pair(t);
  for (let index = 0; index < 100; index++) await a.sendChat(`message ${index}`);
  assert.equal((await a.store.list(a.key('outbox'))).length, 100);
  let drop = true;
  connect(a, b, (from, packet) => !(from === 'b' && packet.type === 'CHAT_ACK' && drop));
  await until(() => b.messages.size === 100);
  drop = false;
  a.detach(); b.detach(); connect(a, b);
  await until(() => [...a.messages.values()].every(message => message.ack));
  assert.equal(b.messages.size, 100);
  assert.equal((await a.store.list(a.key('outbox'))).length, 0);
});

test('chat receiver never ACKs when durable storage fails', async t => {
  const { a, b, errors } = pair(t);
  await b.initialized;
  b.store.put = async () => { throw new Error('Quota exceeded'); };
  const packets = connect(a, b);
  await until(() => a.capable);
  const message = await a.sendChat('must be persisted before ACK');
  await until(() => errors.includes('Quota exceeded'));
  assert.equal(packets.filter(packet => packet.type === 'CHAT_ACK').length, 0);
  assert.equal(message.ack, false);
  assert.equal(b.messages.get(message.id).status, 'failed');
  assert.equal(b.messages.get(message.id).storageError, 'Quota exceeded');
});

test('online chat is visible before either journal commits and later messages bypass a blocked write', async t => {
  const events = [];
  const { a, b } = pair(t, { b: { onChat: message => events.push(message) } }); const packets = connect(a, b);
  await until(() => a.capable && b.capable);
  let release; const gate = new Promise(resolve => { release = resolve; }); t.after(() => release());
  for (const peer of [a, b]) { const batch = peer.store.batch.bind(peer.store); peer.store.batch = async changes => { await gate; return batch(changes); }; }
  const first = a.sendChat('first with stalled disk'), second = a.sendChat('second bypasses stalled disk');
  await until(() => b.messages.size === 2);
  const message = [...a.messages.values()][0];
  b.receive(JSON.stringify({ v: 2, type: 'CHAT', id: message.id, text: message.text, timestamp: message.timestamp }));
  await until(() => [...a.messages.values()].every(message => message.received));
  assert.equal(events.filter(message => message.status === 'received').length, 2, 'A replay must not create another visible message');
  assert.equal(packets.filter(packet => packet.type === 'CHAT_ACK').length, 0);
  assert.equal(a.outbox.size, 2);
  release(); await Promise.all([first, second]);
  await until(() => [...a.messages.values()].every(message => message.ack));
  assert.equal(a.outbox.size, 0);
});

test('an early receiver ACK waits for the initial sender journal and cannot resurrect its outbox', async t => {
  const { a, b } = pair(t); connect(a, b); await until(() => a.capable && b.capable);
  let release; const gate = new Promise(resolve => { release = resolve; }); t.after(() => release());
  const batch = a.store.batch.bind(a.store); let calls = 0;
  a.store.batch = async changes => { if (++calls === 1) await gate; return batch(changes); };
  const sending = a.sendChat('remote saved before local journal');
  await until(() => b.messages.size === 1 && a.chatAckWrites.size === 1);
  const message = [...a.messages.values()][0]; assert.equal(message.ack, false);
  release(); await sending; await until(() => message.ack);
  assert.equal((await a.store.list(a.key('outbox'))).length, 0);
  assert.equal((await a.store.get(a.key('chat', message.id))).status, 'delivered');
});

test('cancel and clear wait for outstanding chat commits without reviving cancelled messages', async t => {
  const { a, b } = pair(t); connect(a, b); await until(() => a.capable && b.capable);
  let release; const gate = new Promise(resolve => { release = resolve; }); t.after(() => release());
  const batch = a.store.batch.bind(a.store); let calls = 0;
  a.store.batch = async changes => { if (++calls === 1) await gate; return batch(changes); };
  const sending = a.sendChat('cancel while initial commit is pending');
  await until(() => a.messages.size === 1);
  const message = [...a.messages.values()][0]; const cancelling = a.cancelChat(message.id);
  release(); await Promise.all([sending, cancelling]); await sleep(10);
  assert.equal(message.status, 'cancelled'); assert.equal(message.ack, false);
  assert.equal((await a.store.list(a.key('outbox'))).length, 0);
  await a.clearChat(); assert.equal((await a.store.list(a.key('chat'))).length, 0);
});

test('hot chat path uses the restored index instead of scanning or reading IndexedDB per message', async t => {
  const { a, b } = pair(t); connect(a, b); await until(() => a.capable && b.capable);
  for (const peer of [a, b]) { peer.store.list = peer.store.get = async () => { throw new Error('Unexpected hot-path read'); }; }
  const messages = await Promise.all(Array.from({ length: 25 }, (_, index) => a.sendChat(`burst ${index}`)));
  await until(() => messages.every(message => message.ack));
  assert.equal(b.messages.size, 25); assert.equal(a.outbox.size, 0);
});

test('sender storage failure cannot convert an uncommitted ACK into delivered', async t => {
  const { a, b } = pair(t); connect(a, b);
  await until(() => a.capable);
  const original = a.store.batch.bind(a.store); let calls = 0;
  a.store.batch = async records => { if (++calls > 1) throw new Error('Storage failed'); return original(records); };
  const message = await a.sendChat('ACK must be transactional');
  await until(() => b.messages.size === 1); await sleep(10);
  assert.equal(message.ack, false);
  assert.equal((await a.store.list(a.key('outbox'))).length, 1);
});

test('reload preserves outbox and isolates server/account/workspace/device', async t => {
  const store = new LocalStore(); const { a, b } = pair(t, { a: { store } });
  const message = await a.sendChat('survive reload'); a.dispose();
  const restored = new ReliablePeer({ scope: a.scope, store }); t.after(() => restored.dispose());
  const other = new ReliablePeer({ scope: 'another-account', store }); t.after(() => other.dispose());
  await Promise.all([restored.initialized, other.initialized]);
  assert.equal(restored.messages.get(message.id).text, message.text);
  assert.equal(other.messages.size, 0);
  connect(restored, b); await until(() => restored.messages.get(message.id).ack);
});

test('offline replay preserves send sequence after IndexedDB cursor order changes on reload', async t => {
  const store = new LocalStore(); const { a, b } = pair(t, { a: { store } });
  const messages = await Promise.all(Array.from({ length: 20 }, (_, index) => a.sendChat(`ordered offline ${index}`)));
  a.dispose(); const list = store.list.bind(store); store.list = async prefix => (await list(prefix)).reverse();
  const restored = new ReliablePeer({ scope: a.scope, store }); t.after(() => restored.dispose()); await restored.initialized;
  const packets = connect(restored, b); await until(() => restored.outbox.size === 0);
  assert.deepEqual(packets.filter(packet => packet.type === 'CHAT').map(packet => packet.id), messages.map(message => message.id));
  const next = await restored.sendChat('next after reload'); assert.equal(next.sequence, 21);
});

test('cancelled offline chat is retained in history and removed from outbox', async t => {
  const { a } = pair(t); const message = await a.sendChat('cancel me'); await a.cancelChat(message.id);
  assert.equal(message.status, 'cancelled'); assert.equal((await a.store.list(a.key('outbox'))).length, 0);
});

test('receiver consent precedes bytes, rejection does not close chat', async t => {
  const { a, b } = pair(t, { b: { autoAccept: false } }); const packets = connect(a, b);
  const id = await a.enqueueFile(source()); await until(() => b.tasks.get(id)?.status === 'offered');
  assert.equal(packets.some(packet => packet.type === 'BINARY'), false);
  await b.rejectFile(id); await until(() => a.tasks.get(id).status === 'rejected');
  const message = await a.sendChat('chat still works'); await until(() => message.ack);
});

test('empty, Unicode-named and multi-block files complete only with verified receiver receipt', async t => {
  const { a, b, received } = pair(t); const packets = connect(a, b);
  for (const size of [0, 31, 2 * 1024 * 1024 + 17]) {
    const id = await a.enqueueFile(source(size)); await until(() => a.tasks.get(id)?.status === 'completed', 8000);
    assert.equal(a.tasks.get(id).verified, true); assert.equal(b.tasks.get(id).offset, size);
  }
  assert.equal(received.length, 3);
  assert.equal(new Set(packets.filter(packet => packet.type === 'COMPLETE').map(packet => packet.id)).size, 3);
  assert.ok(packets.filter(packet => ['BLOCK', 'FINISH', 'BINARY'].includes(packet.type)).every(packet => packet.lane === 'file'));
});

test('lost completion ACK is replayed without duplicate file delivery', async t => {
  const { a, b, received } = pair(t); let lost = false;
  const packets = connect(a, b, (from, packet) => {
    if (from === 'b' && packet.type === 'COMPLETE' && !lost) { lost = true; return false; } return true;
  });
  const id = await a.enqueueFile(source()); await until(() => a.tasks.get(id)?.status === 'completed');
  assert.equal(received.length, 1);
  assert.equal(packets.filter(packet => packet.type === 'FINISH').length, 2);
});

test('corrupt binary block never advances checkpoint or completes', async t => {
  const { a, b } = pair(t); let corrupted = false;
  connect(a, b, (from, packet, data) => {
    if (from === 'a' && packet.type === 'BINARY' && !corrupted) { new Uint8Array(data)[32] ^= 1; corrupted = true; } return true;
  });
  const id = await a.enqueueFile(source(100)); await until(() => a.tasks.get(id)?.status === 'failed');
  assert.equal(b.tasks.get(id).offset, 0); assert.equal(b.tasks.get(id).verified, undefined);
  assert.match(a.tasks.get(id).error, /checksum/);
});

test('receiver write failure never ACKs the block or completes', async t => {
  const io = new FileIO('failure'); io.write = async () => { throw new Error('Disk full'); };
  const { a, b } = pair(t, { b: { io } }); const packets = connect(a, b);
  const id = await a.enqueueFile(source()); await until(() => a.tasks.get(id)?.status === 'failed');
  assert.equal(packets.some(packet => packet.type === 'PROGRESS' || packet.type === 'COMPLETE'), false);
  assert.equal(b.tasks.get(id).offset, 0);
});

test('receiver cancellation immediately releases sender ACK wait and preserves chat', async t => {
  const { a, b } = pair(t, { b: { autoAccept: false } }); connect(a, b);
  const id = await a.enqueueFile(source()); await until(() => b.tasks.get(id)?.status === 'offered');
  await b.cancel(id); await until(() => a.tasks.get(id)?.status === 'cancelled');
  assert.equal(a.waiters.size, 0);
  const message = await a.sendChat('after cancel'); await until(() => message.ack);
});

test('file queue runs sequentially and queued cancellation transmits no bytes', async t => {
  const { a, b, received } = pair(t, { b: { autoAccept: false } }); const packets = connect(a, b);
  const ids = [];
  for (let index = 0; index < 3; index++) ids.push(await a.enqueueFile(source(10, `${index}.txt`)));
  await until(() => b.tasks.has(ids[0])); await a.cancel(ids[1]);
  await b.acceptFile(ids[0]); await until(() => b.tasks.has(ids[2])); await b.acceptFile(ids[2]);
  await until(() => a.tasks.get(ids[2]).status === 'completed');
  assert.equal(received.length, 2);
  assert.equal(packets.some(packet => packet.type === 'OFFER' && packet.id === ids[1]), false);
});

test('memory fallback enforces a total 64 MiB reservation', async () => {
  const io = new FileIO('memory');
  await io.open({ id: 'a', size: MEMORY_LIMIT }, 0, false);
  await assert.rejects(io.open({ id: 'b', size: 1 }, 0, false), /budget/);
  await io.close({ id: 'a' }, false, true);
  await io.open({ id: 'b', size: 1 }, 0, false); io.dispose();
});

test('unsupported READY version and oversized source are rejected', async t => {
  const { a, errors } = pair(t);
  a.receive(JSON.stringify({ v: 2, type: 'READY', version: 9 })); await until(() => errors.length > 0);
  assert.equal(a.capable, false);
  await assert.rejects(a.enqueueFile(source(1, '../unsafe')), /Invalid source/);
  await assert.rejects(a.enqueueFile({ name: 'too-large.bin', size: 6 * 1024 ** 4 }), /Invalid source/);
});

test('binary framing retains offsets beyond 4 GiB and rejects malformed bounds', () => {
  const id = uuid(), offset = 5 * 1024 ** 3 + 17, bytes = new Uint8Array([1, 2, 3]);
  const frame = encodeFrame(id, offset, bytes), decoded = decodeFrame(frame);
  assert.equal(decoded.id, id); assert.equal(decoded.offset, offset); assert.deepEqual(decoded.bytes, bytes);
  new DataView(frame).setUint32(28, 4); assert.throws(() => decodeFrame(frame), /bounds/);
  assert.throws(() => decodeFrame(new ArrayBuffer(17)), /size/);
});

test('SHA-256 matches the known standard digest', () => {
  assert.equal(hashBytes(new TextEncoder().encode('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('pause ACK drains the file lane; changed source is rejected before resuming', async t => {
  const { a, b } = pair(t, { b: { autoAccept: false } }); const packets = connect(a, b);
  const original = source(50), id = await a.enqueueFile(original);
  await until(() => b.tasks.get(id)?.status === 'offered');
  await a.pause(id); await until(() => !a.running);
  assert.ok(packets.some(packet => packet.type === 'PAUSE_ACK'));
  const count = packets.filter(packet => packet.type === 'OFFER').length;
  await a.resume(id, new File(['different contents'], original.name)); await until(() => a.tasks.get(id).status === 'failed');
  assert.match(a.tasks.get(id).error, /changed/);
  assert.equal(packets.filter(packet => packet.type === 'OFFER').length, count);
  await a.resume(id, original); await until(() => b.tasks.get(id).status === 'offered');
  await b.acceptFile(id); await until(() => a.tasks.get(id).status === 'completed');
});

test('pause during transport backpressure follows all frames of the current committed block', async t => {
  const { a, b } = pair(t); const packets = connect(a, b), file = source(2 * 1024 * 1024);
  let pauseResult, stalled = false, id;
  const channel = a.files, send = channel.send;
  channel.send = data => {
    send(data);
    if (data instanceof ArrayBuffer && !stalled) {
      stalled = true; channel.bufferedAmount = 400000;
      pauseResult = a.pause(id);
      setTimeout(() => { channel.bufferedAmount = 0; }, 15);
    }
  };
  id = await a.enqueueFile(file);
  await until(() => !!pauseResult); await pauseResult;
  assert.equal(b.tasks.get(id).status, 'paused'); assert.equal(b.tasks.get(id).offset, 1024 * 1024);
  const pauseIndex = packets.findIndex(packet => packet.type === 'PAUSE');
  assert.equal(packets.slice(pauseIndex + 1).some(packet => packet.type === 'BINARY'), false);
  await a.resume(id, file); await until(() => a.tasks.get(id).status === 'completed');
});

test('failure saving a completion receipt never emits COMPLETE on retries', async t => {
  const { a, b } = pair(t); await b.initialized;
  const put = b.store.put.bind(b.store);
  b.store.put = async (key, value) => { if (value.status === 'completed') throw new Error('Receipt storage failed'); return put(key, value); };
  const packets = connect(a, b), id = await a.enqueueFile(source());
  await until(() => a.tasks.get(id)?.status === 'failed');
  assert.equal(packets.some(packet => packet.type === 'COMPLETE'), false);
  assert.notEqual(b.tasks.get(id).status, 'completed');
});

test('selected destination must close successfully before the receiver sends COMPLETE', async t => {
  const { a, b } = pair(t, { b: { autoAccept: false } }); const packets = connect(a, b);
  const file = source(50), id = await a.enqueueFile(file); let closed = false, written = 0;
  await until(() => b.tasks.get(id)?.status === 'offered');
  await b.acceptFile(id, { createWritable: async () => new WritableStream({ write(bytes) { written += bytes.length; }, async close() { await sleep(15); closed = true; } }) });
  await until(() => a.tasks.get(id).status === 'completed');
  assert.equal(closed, true); assert.equal(written, file.size); assert.equal(a.tasks.get(id).savedTo, 'disk');
  assert.ok(packets.some(packet => packet.type === 'COMPLETE'));
});

test('destination commit failure never reports a fully received file as completed', async t => {
  const { a, b } = pair(t, { b: { autoAccept: false } }); const packets = connect(a, b);
  const id = await a.enqueueFile(source(50)); await until(() => b.tasks.get(id)?.status === 'offered');
  await b.acceptFile(id, { createWritable: async () => new WritableStream({ close() { throw new Error('Destination permission revoked'); } }) });
  await until(() => a.tasks.get(id).status === 'failed');
  assert.equal(packets.some(packet => packet.type === 'COMPLETE'), false);
  assert.equal(b.tasks.get(id).offset, 50); assert.notEqual(b.tasks.get(id).status, 'completed');
});
