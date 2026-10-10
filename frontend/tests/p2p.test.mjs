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
