import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { createLanProxy } from './lan-proxy.mjs';

const { chromium } = createRequire(new URL('../frontend/package.json', import.meta.url))('playwright');
const backend = process.env.LANDROP_TEST_BACKEND;
assert.ok(backend, 'Set LANDROP_TEST_BACKEND to an isolated, running test backend');
assert.ok(process.env.LANDROP_TEST_CERT && process.env.LANDROP_TEST_KEY, 'Set test TLS certificate paths');
const status = await (await fetch(`${backend}/status`)).json();
const proxy = createLanProxy({ cert: await fs.readFile(process.env.LANDROP_TEST_CERT), key: await fs.readFile(process.env.LANDROP_TEST_KEY), backend, websocket: `http://127.0.0.1:${status.wsPort || status.websocketPort}`, frontend: process.env.LANDROP_TEST_FRONTEND || 'http://127.0.0.1:3333' });
proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');
const origin = `https://127.0.0.1:${proxy.address().port}`;
const browser = await chromium.launch({ ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : { channel: 'chrome' }), headless: true });
const errors = [], gatewayErrors = [], connected = new Set(); let renewals = 0, chatStress;
const bundledPeer = process.env.LANDROP_TEST_BUNDLED_PEER === 'true';
const suffix = Date.now().toString(36), ownerName = `p2p_o_${suffix}`, memberName = `p2p_n_${suffix}`;
const password = 'LAN-test-only-Password-123!';
const artifact = new URL('../output/p2p/', import.meta.url); await fs.mkdir(artifact, { recursive: true });
try {
  const owner = await browser.newPage({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
  const member = await browser.newPage({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  const memberMessage = text => bundledPeer ? member.locator('#messages-stream .msg-bubble').filter({ hasText: text }) : member.getByText(text, { exact: true });
  for (const [page, label] of [[owner, 'bundled'], [member, 'next']]) {
    page.on('pageerror', error => errors.push(error.message));
    page.on('websocket', socket => {
      const recent = [];
      socket.on('framesent', ({ payload }) => { const packet = JSON.parse(payload.toString()); if (packet.type.startsWith('webrtc.') || packet.type.startsWith('transfer.')) { recent.push({ type: packet.type, requestId: packet.requestId, transferId: packet.payload?.transferId, status: packet.payload?.status }); if (recent.length > 8) recent.shift(); } });
      socket.on('framereceived', ({ payload }) => {
      const packet = JSON.parse(payload.toString());
      if (packet.type === 'error') console.log(label, packet.type, packet.requestId, packet.error?.code || '', packet.error?.message || '', JSON.stringify(recent));
      if (packet.type === 'error') gatewayErrors.push(packet.error?.code);
      if (packet.type === 'transfer.renew_ack') renewals++;
      if (packet.type === 'transfer.status' && packet.payload?.status === 'P2P_CONNECTED') connected.add(label);
      });
    });
  }
  await owner.goto(`${origin}/client`); await owner.locator('#auth-switch').click();
  await owner.locator('#login-identifier').fill(ownerName); await owner.locator('#register-email').fill(`${ownerName}@example.test`);
  await owner.locator('#register-name').fill('LAN Desktop'); await owner.locator('#login-password').fill(password); await owner.locator('#auth-submit').click();
  await owner.waitForFunction(() => authenticated && workspaceId, null, { timeout: 30000 });
  console.log('Bundled registration and workspace ready');
  if (bundledPeer) {
    await member.goto(`${origin}/client`); await member.locator('#auth-switch').click();
    await member.locator('#login-identifier').fill(memberName); await member.locator('#register-email').fill(`${memberName}@example.test`);
    await member.locator('#register-name').fill('LAN Laptop'); await member.locator('#login-password').fill(password); await member.locator('#auth-submit').click();
    await member.waitForFunction(() => authenticated && workspaceId, null, { timeout: 30000 });
  } else {
  await member.goto(`${origin}/connect`); await member.locator('input[autocomplete="url"]').fill(origin);
  await member.getByRole('button', { name: 'Kết nối server' }).click(); await member.waitForURL('**/auth/login');
  await member.getByRole('link', { name: 'Tạo tài khoản', exact: true }).click();
  await member.locator('input[autocomplete="name"]').fill('LAN Laptop'); await member.locator('input[autocomplete="username"]').fill(memberName);
  await member.locator('input[autocomplete="email"]').fill(`${memberName}@example.test`); await member.locator('input[autocomplete="new-password"]').fill(password);
  await member.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click(); await member.waitForURL('**/workspaces');
  }
  const workspace = await owner.evaluate(() => workspaceId);
  await owner.evaluate(async email => apiRequest(`/api/workspaces/${workspaceId}/members`, { method: 'POST', body: JSON.stringify({ email }) }), `${memberName}@example.test`);
  if (bundledPeer) await member.evaluate(async workspace => { await loadWorkspaces(); document.getElementById('workspace-select').value = workspace; changeWorkspace(); }, workspace);
  else await member.goto(`${origin}/w/${workspace}/transfers`);
  await member.evaluate(() => { window.showSaveFilePicker = undefined; }); // Exercise real OPFS; an OS picker needs human input.
  await owner.evaluate(() => refreshPeers());
  await owner.waitForFunction(name => peersList.some(peer => peer.displayName === name || peer.username === name), memberName, { timeout: 30000 });
  await owner.evaluate(name => selectPeerConversation(peersList.find(peer => peer.displayName === name || peer.username === name)), memberName);
  if (!bundledPeer) await member.getByRole('button', { name: 'Chấp nhận & Nhận tệp' }).click();
  await owner.waitForFunction(() => rtcDataChannel?.readyState === 'open');
  await owner.locator('#composer-textarea').fill('Chat LAN: Next.js ↔ client Java'); await owner.locator('#btn-composer-send').click();
  await memberMessage('Chat LAN: Next.js ↔ client Java').waitFor();
  console.log('Cross-client WebRTC chat received');
  if (process.env.LANDROP_TEST_CHAT_STRESS === 'true') {
    const latencies = await owner.evaluate(async () => {
      const values = [];
      for (let index = 0; index < 100; index++) {
        const message = await p2pManager.sendChat(`Client RTT sample ${index}`, selectedPeer, workspaceId);
        const deadline = performance.now() + 10000;
        while (!message.ack) { if (performance.now() > deadline) throw new Error('UI chat ACK timed out'); await new Promise(resolve => setTimeout(resolve, 1)); }
        values.push(message.receiveRttMs);
      }
      if (values.some(value => !Number.isFinite(value))) throw new Error('Fast receiver receipt was not negotiated');
      values.sort((a, b) => a - b); return { samples: 100, minMs: values[0], p50Ms: values[49], p95Ms: values[94], p99Ms: values[98], maxMs: values[99] };
    });
    console.log('Client RTT measured:', JSON.stringify(latencies));
    await owner.evaluate(() => Promise.all(Array.from({ length: 30 }, (_, index) => p2pManager.sendChat(`Burst client ${index}`, selectedPeer, workspaceId))));
    await memberMessage('Burst client 29').waitFor();
    await owner.waitForFunction(() => p2pManager.getService().getChatMessages().filter(message => message.text.startsWith('Burst client ') && message.ack).length === 30);
    console.log('30 burst messages acknowledged.');
    assert.equal(await memberMessage('Burst client 0').count(), 1);
    // Reproduce the detached-core/CONNECTED bug and send while reconnecting.
    await owner.evaluate(async () => { p2pManager.getService().fileChannel.close(); await p2pManager.sendChat('Queued chat survives a closed file lane', selectedPeer, workspaceId); });
    await memberMessage('Queued chat survives a closed file lane').waitFor({ timeout: 30000 });
    await owner.waitForFunction(() => p2pManager.getService().getChatMessages().some(message => message.text === 'Queued chat survives a closed file lane' && message.ack), null, { timeout: 30000 });
    console.log('Closed file channel recovery succeeded.');
    // A full gateway reconnect changes connectionId while deviceId remains stable.
    const oldConnectionId = await owner.evaluate(() => myConnectionId);
    await owner.evaluate(() => ws.close());
    await owner.waitForFunction(old => authenticated && myConnectionId && myConnectionId !== old && ws?.readyState === WebSocket.OPEN, oldConnectionId, { timeout: 30000 });
    await owner.locator('#composer-textarea').fill('Chat after gateway reconnect'); await owner.locator('#btn-composer-send').click();
    await memberMessage('Chat after gateway reconnect').waitFor({ timeout: 30000 });
    console.log('Gateway recovery UI snapshot:', JSON.stringify(await owner.evaluate(() => ({ workspaceId, selectedPeer, scope: p2pManager.getService().getReliable()?.scope, histories: Object.entries(conversations).map(([key, values]) => ({ key, count: values.length })), nodes: document.querySelectorAll('#messages-stream .msg-group').length, messageTexts: document.getElementById('messages-stream').innerText.slice(0, 500) }))));
    assert.equal(await owner.locator('#messages-stream .msg-bubble').filter({ hasText: 'Burst client 0' }).count(), 1);
    assert.equal(await owner.locator('#messages-stream .msg-group').count(), 133, 'Device-scoped history survives connection ID changes without duplicates');
    chatStress = { receiverReceiptRoundTrip: latencies, burst: 30, duplicateMessages: 0, fileChannelRecovery: true, changedGatewayConnectionId: true, stableDeviceHistory: true };
    console.log('Client latency, burst, file-lane recovery and gateway reconnect verified.');
  }
  const bytes = Buffer.from('LAN integrity test\n'.repeat(20000));
  await owner.locator('#composer-file-input').setInputFiles({ name: 'LAN kiểm tra.txt', mimeType: 'text/plain', buffer: bytes });
  await member.getByRole('button', { name: 'Nhận và lưu', exact: true }).click();
  await owner.waitForFunction(() => reliableTasks.some(task => task.status === 'completed' && task.verified));
  if (!bundledPeer) await member.getByRole('button', { name: 'Tất cả', exact: true }).click();
  const downloading = member.waitForEvent('download'); await member.getByRole('button', { name: 'Tải tệp đã xác minh', exact: true }).click();
  assert.deepEqual(await fs.readFile(await (await downloading).path()), bytes);
  const leaseSeconds = Number(process.env.LANDROP_TEST_LEASE_SECONDS || 135);
  console.log(`Cross-client file integrity passed. Checking session renewal for ${leaseSeconds}s.`);
  await new Promise(resolve => setTimeout(resolve, leaseSeconds * 1000));
  await owner.locator('#composer-textarea').fill('Chat sau khi vượt lease ban đầu'); await owner.locator('#btn-composer-send').click();
  await memberMessage('Chat sau khi vượt lease ban đầu').waitFor();
  assert.equal(connected.size, 2); assert.deepEqual(gatewayErrors, []); assert.deepEqual(errors, []);
  await owner.evaluate(() => window.scrollTo(0, 0)); await member.evaluate(() => window.scrollTo(0, 0));
  await owner.screenshot({ path: fileURLToPath(new URL('bundled-desktop.png', artifact)), fullPage: true });
  await member.screenshot({ path: fileURLToPath(new URL(bundledPeer ? 'chat-client-desktop.png' : 'next-desktop.png', artifact)), fullPage: true });
  await member.setViewportSize({ width: 390, height: 844 });
  await member.evaluate(() => window.scrollTo(0, 0)); await member.waitForTimeout(250);
  assert.equal(await member.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'Mobile page must not overflow horizontally');
  await member.screenshot({ path: fileURLToPath(new URL(bundledPeer ? 'chat-client-mobile.png' : 'next-mobile.png', artifact)), fullPage: true });
  const report = { browser: browser.version(), cases: ['HTTPS same-origin API and WSS proxy', 'Real PostgreSQL registration and workspace permissions', 'Next.js ↔ bundled shared-engine chat', 'File consent and verified completion', 'Downloaded bytes exactly match source', leaseSeconds >= 120 ? 'Connected session survives initial lease and delivers another message' : 'Connected session remains usable through lease renewal', 'Desktop and mobile screenshots'], bytes: bytes.length, leaseSeconds, renewals, browserErrors: errors, gatewayErrors, physicalLan: false, chatStress };
  await fs.writeFile(new URL('live-browser-report.json', artifact), JSON.stringify(report, null, 2));
  await fs.writeFile(new URL(`live-browser-report-${leaseSeconds}s.json`, artifact), JSON.stringify(report, null, 2));
  if (chatStress) await fs.writeFile(new URL(bundledPeer ? 'chat-bundled-live-report.json' : 'chat-live-report.json', artifact), JSON.stringify({ ...report, clientPair: bundledPeer ? '/client ↔ /client' : '/client ↔ Next.js' }, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally { await browser.close(); proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)); }
