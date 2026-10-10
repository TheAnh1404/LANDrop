import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { once } from 'node:events';
import { createLanProxy } from './lan-proxy.mjs';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium } = require('playwright');
const root = new URL('../', import.meta.url);
const output = new URL('../output/p2p/', import.meta.url);
await fs.mkdir(output, { recursive: true });
const server = http.createServer(async (request, response) => {
  if (request.url === '/') { response.setHeader('content-type', 'text/html'); response.end('<!doctype html><title>LANDrop protocol verification</title><input id="source" type="file"><script src="/p2p/engine.js"></script>'); return; }
  if (!['/p2p/engine.js', '/p2p/file-worker.js'].includes(request.url)) { response.writeHead(404); response.end(); return; }
  response.setHeader('content-type', 'application/javascript'); response.end(await fs.readFile(new URL(`frontend/public${request.url}`, root)));
});
server.listen(0, '127.0.0.1'); await once(server, 'listening');
let url = `http://127.0.0.1:${server.address().port}`, proxy;
if (process.env.LANDROP_TEST_CERT && process.env.LANDROP_TEST_KEY) {
  proxy = createLanProxy({ cert: await fs.readFile(process.env.LANDROP_TEST_CERT), key: await fs.readFile(process.env.LANDROP_TEST_KEY), backend: url });
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening'); url = `https://127.0.0.1:${proxy.address().port}`;
}
const browser = await chromium.launch({ ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : { channel: 'chrome' }), headless: true });
const contexts = [], errors = [];
const count = Number(process.env.LANDROP_TEST_MIB || 128);
assert.ok(Number.isInteger(count) && count >= 8 && count <= 1024, 'Test size must be 8–1024 MiB');
const report = { browser: browser.version(), origin: new URL(url).protocol, fileMiB: count, cases: [], physicalLan: false };
const fixture = new URL(`../tmp/p2p-source-${process.pid}.bin`, import.meta.url);
async function page(scope) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true }); contexts.push(context);
  const result = await context.newPage(); result.on('pageerror', error => errors.push(error.message));
  await result.goto(url); await setup(result, scope); return result;
}
async function setup(page, scope) {
  await page.evaluate(scope => {
    window.errors = []; window.delivered = []; window.events = [];
    window.core = new LANDropP2P.ReliablePeer({ scope, onChat: message => events.push(message), onReceived: file => delivered.push(file), onError: message => errors.push(message) });
  }, scope);
  assert.equal(await page.evaluate(() => isSecureContext && core.io.disk), true, 'Real OPFS disk storage must be available');
}
async function connect(a, b) {
  await a.evaluate(() => {
    window.pc?.close(); window.pc = new RTCPeerConnection({ iceServers: [] });
    window.ctrl = pc.createDataChannel('control', { ordered: true }); window.files = pc.createDataChannel('files', { ordered: true });
    for (const channel of [ctrl, files]) { channel.binaryType = 'arraybuffer'; channel.onmessage = event => core.receive(event.data); }
    ctrl.onopen = () => core.attach(ctrl, files); files.onopen = () => core.setFileChannel(files); ctrl.onclose = () => core.detach();
  });
  await b.evaluate(() => {
    window.pc?.close(); window.pc = new RTCPeerConnection({ iceServers: [] });
    pc.ondatachannel = ({ channel }) => {
      channel.binaryType = 'arraybuffer'; channel.onmessage = event => core.receive(event.data);
      if (channel.label === 'control') { window.ctrl = channel; channel.onopen = () => core.attach(channel, window.files || null); channel.onclose = () => core.detach(); }
      else { window.files = channel; channel.onopen = () => core.setFileChannel(channel); }
    };
  });
  const offer = await a.evaluate(async () => {
    await pc.setLocalDescription(await pc.createOffer());
    if (pc.iceGatheringState !== 'complete') await new Promise(resolve => pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve(); }));
    return pc.localDescription.toJSON();
  });
  const answer = await b.evaluate(async offer => {
    await pc.setRemoteDescription(offer); await pc.setLocalDescription(await pc.createAnswer());
    if (pc.iceGatheringState !== 'complete') await new Promise(resolve => pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve(); }));
    return pc.localDescription.toJSON();
  }, offer);
  await a.evaluate(answer => pc.setRemoteDescription(answer), answer);
  for (const page of [a, b]) await page.waitForFunction(() => core.capable, null, { timeout: 15000 });
}
try {
  const a = await page('alice-device|bob-device'), b = await page('bob-device|alice-device');
  await connect(a, b);
  await a.evaluate(async () => { window.message = await core.sendChat('Durable chat before reload'); });
  await a.waitForFunction(() => message.ack);
  report.cases.push('IndexedDB ACK through real WebRTC');
  // Select a real on-disk source: do not allocate the benchmark file in Chrome's JS heap.
  await fs.mkdir(new URL('../tmp/', import.meta.url), { recursive: true });
  const fixtureHandle = await fs.open(fixture, 'w');
  try {
    const block = Buffer.alloc(1024 * 1024); for (let index = 0; index < block.length; index++) block[index] = index % 251;
    for (let index = 0; index < count; index++) await fixtureHandle.write(block);
  } finally { await fixtureHandle.close(); }
  await a.locator('#source').setInputFiles(fixture.pathname.replace(/^\/(\w:)/, '$1'));
  const id = await a.evaluate(async () => {
    window.source = document.querySelector('#source').files[0];
    return core.enqueueFile(source);
  });
  const fileTimeout = Math.max(180000, count * 1000);
  await b.waitForFunction(id => core.tasks.get(id)?.status === 'offered', id, { timeout: fileTimeout });
  assert.equal(await b.evaluate(id => core.tasks.get(id).offset, id), 0);
  report.cases.push('Receiver consent: zero bytes before acceptance');
  await b.evaluate(async id => {
    const write = core.io.write.bind(core.io);
    core.io.write = async (...args) => { const result = await write(...args); await new Promise(resolve => setTimeout(resolve, 8)); return result; };
    await core.acceptFile(id);
  }, id);
  await a.waitForFunction(id => core.tasks.get(id)?.offset >= 2 * 1024 * 1024, id);
  await a.evaluate(id => core.pause(id), id);
  await a.waitForFunction(() => !core.running);
  await b.waitForFunction(id => core.tasks.get(id)?.status === 'paused', id);
  const checkpoint = await b.evaluate(id => core.tasks.get(id).offset, id);
  assert.ok(checkpoint > 0 && checkpoint < count * 1024 * 1024);
  await b.reload(); await setup(b, 'bob-device|alice-device');
  await b.evaluate(() => core.initialized);
  assert.equal(await b.evaluate(id => core.tasks.get(id).offset, id), checkpoint);
  assert.equal(await b.evaluate(() => core.messages.size), 1);
  report.cases.push('Worker flush + IndexedDB checkpoint survives receiver reload');
  await connect(a, b);
  const started = Date.now();
  await a.evaluate(id => core.resume(id, source), id);
  await b.locator('#source').setInputFiles(fixture.pathname.replace(/^\/(\w:)/, '$1'));
  const reverseId = await b.evaluate(() => core.enqueueFile(new File([document.querySelector('#source').files[0].slice(0, 8 * 1024 * 1024)], 'reverse-8MiB.bin')));
  await a.waitForFunction(id => core.tasks.get(id)?.status === 'offered', reverseId, { timeout: fileTimeout });
  await a.evaluate(id => core.acceptFile(id), reverseId);
  await b.waitForFunction(id => core.tasks.get(id)?.status === 'offered', id, { timeout: fileTimeout });
  assert.equal(await b.evaluate(id => core.tasks.get(id).offset, id), checkpoint);
  await b.evaluate(id => core.acceptFile(id), id);
  const latencies = [];
  for (let index = 0; index < 20; index++) {
    const latency = await a.evaluate(async index => { const start = performance.now(), item = await core.sendChat(`During file transfer ${index}`); while (!item.ack) await new Promise(resolve => setTimeout(resolve, 2)); return performance.now() - start; }, index);
    latencies.push(latency);
  }
  for (const page of [a, b]) {
    await page.waitForFunction(id => ['completed', 'failed', 'cancelled', 'rejected', 'interrupted'].includes(core.tasks.get(id)?.status), id, { timeout: fileTimeout });
    assert.equal(await page.evaluate(id => core.tasks.get(id).status, id), 'completed', 'Transfer must complete with a verified receipt');
  }
  const sent = await a.evaluate(id => core.tasks.get(id), id), received = await b.evaluate(id => core.tasks.get(id), id);
  assert.equal(sent.hash, received.hash); assert.equal(received.offset, count * 1024 * 1024); assert.equal(received.savedTo, 'browser');
  assert.equal(await b.evaluate(() => delivered.length), 1);
  await b.waitForFunction(id => core.tasks.get(id)?.status === 'completed', reverseId, { timeout: fileTimeout });
  assert.equal(await a.evaluate(() => delivered.length), 1);
  report.cases.push('Bidirectional transfer: source hashing does not block incoming disk writes');
  report.cases.push('Resume sends from committed checkpoint; full SHA-256 matches; receiver COMPLETE receipt');
  report.checkpointBytes = checkpoint; report.hash = sent.hash;
  report.resumeSeconds = (Date.now() - started) / 1000;
  report.chatAckP95Ms = Math.round(latencies.sort((a, b) => a - b)[18]);
  await b.reload(); await setup(b, 'bob-device|alice-device');
  const saved = await b.evaluate(async id => { await core.initialized; return core.savedFile(id); }, id);
  assert.equal(saved.hash, sent.hash);
  report.cases.push('Completed OPFS file remains downloadable after reload and re-verification');
  for (const page of [a, b]) assert.deepEqual(await page.evaluate(() => errors), []);
  assert.deepEqual(errors, []);
  await fs.writeFile(new URL('browser-report.json', output), JSON.stringify(report, null, 2));
  await fs.writeFile(new URL(`browser-report-${count}mib.json`, output), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await Promise.all(contexts.map(context => context.close())); await browser.close();
  if (proxy) { proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)); }
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  await fs.unlink(fixture).catch(error => { if (error.code !== 'ENOENT') throw error; });
}
