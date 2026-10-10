import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import { once } from 'node:events';
import { createRequire } from 'node:module';

const { chromium } = createRequire(new URL('../frontend/package.json', import.meta.url))('playwright');
const artifact = new URL('../output/p2p/', import.meta.url);
await fs.mkdir(artifact, { recursive: true });
const server = http.createServer(async (request, response) => {
  if (request.url === '/') { response.setHeader('content-type', 'text/html'); response.end('<!doctype html><title>Chat latency</title><main id="messages"></main><script src="/p2p/engine.js"></script>'); return; }
  if (!['/p2p/engine.js', '/p2p/file-worker.js'].includes(request.url)) { response.writeHead(404); response.end(); return; }
  response.setHeader('content-type', 'application/javascript'); response.end(await fs.readFile(new URL(`../frontend/public${request.url}`, import.meta.url)));
});
server.listen(0, '127.0.0.1'); await once(server, 'listening');
const browser = await chromium.launch({ ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : { channel: 'chrome' }), headless: true });
const report = { browser: browser.version(), physicalLan: false, clock: 'Sender performance.now(); round-trip, no remote clock subtraction', samplesPerScenario: 100, scenarios: {} };
const errors = [];
try {
  const pages = [];
  for (const scope of ['chat-benchmark-alice', 'chat-benchmark-bob']) {
    const context = await browser.newContext(); const page = await context.newPage(); pages.push(page);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.evaluate(scope => {
      window.samples = new Map(); window.waiters = new Map(); window.visible = new Set(); window.protocolErrors = [];
      window.core = new LANDropP2P.ReliablePeer({ scope, autoAccept: true, onError: error => protocolErrors.push(error), onChat: message => {
        const sample = samples.get(message.id);
        if (message.direction === 'sent' && message.ack && sample && sample.durable === undefined) { sample.durable = performance.now() - sample.start; sample.resolveDurable(); }
        if (message.direction === 'received' && !visible.has(message.id)) {
          visible.add(message.id); const element = document.createElement('p'); element.textContent = message.text; document.getElementById('messages').append(element);
          (window.chat?.readyState === 'open' ? chat : ctrl).send(JSON.stringify({ benchmark: 'visible', id: message.id }));
        }
      } });
      window.route = data => {
        if (typeof data === 'string') {
          const packet = JSON.parse(data);
          if (packet.benchmark === 'visible') { const sample = samples.get(packet.id); if (sample && sample.visible === undefined) { sample.visible = performance.now() - sample.start; sample.resolveVisible(); } return; }
          if (packet.benchmark === 'probe') { (window.chat?.readyState === 'open' ? chat : ctrl).send(JSON.stringify({ benchmark: 'probe-ack', id: packet.id })); return; }
          if (packet.benchmark === 'probe-ack') { waiters.get(packet.id)?.(); return; }
        }
        core.receive(data);
      };
      window.measure = async count => {
        const values = [];
        for (let index = 0; index < count; index++) {
          let resolveVisible, resolveDurable;
          const shown = new Promise(resolve => { resolveVisible = resolve; }), saved = new Promise(resolve => { resolveDurable = resolve; });
          const start = performance.now();
          // Capture the ID at the first local emission, before an online fast send can return.
          const original = core.options.onChat;
          core.options.onChat = message => { if (message.direction === 'sent' && !samples.has(message.id)) samples.set(message.id, { start, resolveVisible, resolveDurable }); original(message); };
          let message;
          try { message = await core.sendChat(`LAN benchmark ${index}: ${Date.now()}`); } finally { core.options.onChat = original; }
          const timer = new Promise((_, reject) => { const id = setTimeout(() => reject(new Error('Chat did not arrive and persist within 10s')), 10000); Promise.all([shown, saved]).finally(() => clearTimeout(id)); });
          await Promise.race([Promise.all([shown, saved]), timer]);
          const sample = samples.get(message.id); values.push({ visible: sample.visible, durable: sample.durable });
        }
        const stats = field => { const sorted = values.map(value => value[field]).sort((a, b) => a - b); return { minMs: sorted[0], p50Ms: sorted[Math.ceil(sorted.length * .5) - 1], p95Ms: sorted[Math.ceil(sorted.length * .95) - 1], p99Ms: sorted[Math.ceil(sorted.length * .99) - 1], maxMs: sorted.at(-1) }; };
        return { receiverDomRoundTrip: stats('visible'), durableAckRoundTrip: stats('durable') };
      };
    }, scope);
  }
  const [a, b] = pages;
  await a.evaluate(() => {
    window.pc = new RTCPeerConnection({ iceServers: [] }); window.ctrl = pc.createDataChannel('control', { ordered: true }); window.files = pc.createDataChannel('files', { ordered: true });
    window.chat = pc.createDataChannel('chat', { ordered: true });
    for (const channel of [ctrl, files, chat]) { channel.binaryType = 'arraybuffer'; channel.onmessage = event => route(event.data); }
    ctrl.onopen = () => core.attach(ctrl, files, chat); files.onopen = () => core.setFileChannel(files); chat.onopen = () => core.setChatChannel?.(chat);
  });
  await b.evaluate(() => {
    window.pc = new RTCPeerConnection({ iceServers: [] });
    pc.ondatachannel = ({ channel }) => { window[channel.label === 'control' ? 'ctrl' : channel.label] = channel; channel.binaryType = 'arraybuffer'; channel.onmessage = event => route(event.data);
      channel.onopen = () => { if (channel.label === 'control') core.attach(channel, window.files, window.chat); else if (channel.label === 'files') core.setFileChannel(channel); else core.setChatChannel?.(channel); }; };
  });
  const offer = await a.evaluate(async () => { await pc.setLocalDescription(await pc.createOffer()); if (pc.iceGatheringState !== 'complete') await new Promise(resolve => pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve(); })); return pc.localDescription.toJSON(); });
  const answer = await b.evaluate(async offer => { await pc.setRemoteDescription(offer); await pc.setLocalDescription(await pc.createAnswer()); if (pc.iceGatheringState !== 'complete') await new Promise(resolve => pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve(); })); return pc.localDescription.toJSON(); }, offer);
  await a.evaluate(answer => pc.setRemoteDescription(answer), answer);
  for (const page of pages) await page.waitForFunction(() => core.capable && window.chat?.readyState === 'open');
  report.transport = await a.evaluate(async () => { const latencies = []; for (let index = 0; index < 100; index++) { const start = performance.now(); await new Promise(resolve => { waiters.set(index, resolve); chat.send(JSON.stringify({ benchmark: 'probe', id: index })); }); latencies.push(performance.now() - start); } latencies.sort((a, b) => a - b); return { minMs: latencies[0], p50Ms: latencies[49], p95Ms: latencies[94] }; });
  await a.evaluate(() => measure(10));
  report.scenarios.idle = await a.evaluate(() => measure(100)); console.log('Idle chat measured.');
  for (const page of pages) await page.evaluate(() => { const original = core.store.batch.bind(core.store); core.store.batch = async changes => { await new Promise(resolve => setTimeout(resolve, 100)); return original(changes); }; });
  report.scenarios.slowStorage = await a.evaluate(() => measure(10)); report.scenarios.slowStorage.samples = 10; console.log('100ms storage stall measured.');
  // Restore normal writes and inject only file I/O delay to keep the file in flight.
  for (const page of pages) await page.evaluate(() => { core.store = new LANDropP2P.LocalStore(); });
  await b.evaluate(() => { const write = core.io.write.bind(core.io); core.io.write = async (...args) => { const result = await write(...args); await new Promise(resolve => setTimeout(resolve, 20)); return result; }; });
  const fileId = await a.evaluate(() => core.enqueueFile(new File([new Uint8Array(32 * 1024 * 1024)], 'chat-load.bin')));
  await a.waitForFunction(id => core.tasks.get(id)?.status === 'transferring', fileId);
  report.scenarios.fileInFlight = await a.evaluate(() => measure(100));
  await a.waitForFunction(id => ['completed', 'failed'].includes(core.tasks.get(id)?.status), fileId, { timeout: 60000 });
  assert.equal(await a.evaluate(id => core.tasks.get(id).status, fileId), 'completed');
  report.receivedUnique = await b.evaluate(() => visible.size); assert.equal(report.receivedUnique, 220);
  for (const page of pages) assert.deepEqual(await page.evaluate(() => protocolErrors), []);
  assert.deepEqual(errors, []);
  const label = process.env.LANDROP_CHAT_REPORT || 'chat-latency'; assert.match(label, /^[a-z0-9-]+$/);
  await fs.writeFile(new URL(`${label}.json`, artifact), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
} finally { await browser.close(); server.close(); }
