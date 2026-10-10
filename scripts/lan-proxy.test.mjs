import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import { once } from 'node:events';
import { createLanProxy } from './lan-proxy.mjs';

test('LAN proxy rejects unsupported upstream protocols', () => {
  assert.throws(() => createLanProxy({ backend: 'https://remote.example' }), /HTTP/);
});
const tls = process.env.LANDROP_TEST_CERT && process.env.LANDROP_TEST_KEY;
test('TLS proxy routes API/assets/frontend and preserves authenticated WS Origin', { skip: !tls }, async t => {
  const requests = [];
  const backend = http.createServer((request, response) => { requests.push({ path: request.url, authorization: request.headers.authorization }); response.end('backend'); });
  const frontend = http.createServer((request, response) => response.end('frontend'));
  let origin;
  backend.on('upgrade', (request, socket) => { origin = request.headers.origin; socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n'); socket.end(); });
  for (const server of [backend, frontend]) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); }
  const proxy = createLanProxy({ cert: fs.readFileSync(process.env.LANDROP_TEST_CERT), key: fs.readFileSync(process.env.LANDROP_TEST_KEY), backend: `http://127.0.0.1:${backend.address().port}`, websocket: `http://127.0.0.1:${backend.address().port}`, frontend: `http://127.0.0.1:${frontend.address().port}` });
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');
  t.after(async () => { for (const server of [proxy, backend, frontend]) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } });
  const port = proxy.address().port;
  const get = route => new Promise((resolve, reject) => {
    https.get({ hostname: '127.0.0.1', port, path: route, rejectUnauthorized: false, headers: { authorization: 'Bearer test-token' } }, response => { let body = ''; response.on('data', chunk => body += chunk); response.on('end', () => resolve(body)); }).on('error', reject);
  });
  for (const route of ['/api/auth/me', '/health', '/p2p/engine.js', '/client']) assert.equal(await get(route), 'backend');
  assert.equal(await get('/w/example/transfers'), 'frontend');
  assert.ok(requests.every(request => request.authorization === 'Bearer test-token'));
  await new Promise((resolve, reject) => {
    const request = https.request({ hostname: '127.0.0.1', port, path: '/ws', rejectUnauthorized: false, headers: { Connection: 'Upgrade', Upgrade: 'websocket', Origin: 'https://landrop.test:3443' } });
    request.on('upgrade', (response, socket) => { assert.equal(response.statusCode, 101); socket.destroy(); resolve(); }); request.on('error', reject); request.end();
  });
  assert.equal(origin, 'https://landrop.test:3443');
});
