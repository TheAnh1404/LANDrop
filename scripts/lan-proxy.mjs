import https from 'node:https';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseDotEnv } from './start-server.mjs';

export function createLanProxy(options) {
  const backend = new URL(options.backend || 'http://127.0.0.1:3100');
  const websocket = new URL(options.websocket || 'http://127.0.0.1:3101');
  const frontend = options.frontend ? new URL(options.frontend) : backend;
  for (const destination of [backend, websocket, frontend]) if (destination.protocol !== 'http:') throw new Error('Proxy upstreams must be local HTTP endpoints.');
  const server = https.createServer({ cert: options.cert, key: options.key }, (request, response) => {
    const route = new URL(request.url, 'https://localhost').pathname;
    const api = route.startsWith('/api/') || ['/api', '/health', '/status', '/api/status', '/api/health'].includes(route);
    const target = api || route.startsWith('/p2p/') || route === '/client' ? backend : frontend;
    const headers = { ...request.headers, host: target.host, 'x-forwarded-proto': 'https', 'x-forwarded-host': request.headers.host };
    const upstream = http.request(target, { path: request.url, method: request.method, headers }, incoming => {
      response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response);
    });
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502, { 'content-type': 'application/json' }); response.end('{"error":{"message":"LAN backend is unavailable"}}'); });
    request.on('aborted', () => upstream.destroy()); request.pipe(upstream);
  });
  server.on('upgrade', (request, socket, head) => {
    if (new URL(request.url, 'https://localhost').pathname !== '/ws') { socket.destroy(); return; }
    const upstream = http.request(websocket, { path: '/', headers: { ...request.headers, host: websocket.host } });
    upstream.on('upgrade', (incoming, peer, upstreamHead) => {
      socket.write(`HTTP/1.1 101 Switching Protocols\r\n${Object.entries(incoming.headers).map(([key, value]) => `${key}: ${value}`).join('\r\n')}\r\n\r\n`);
      if (head.length) peer.write(head); if (upstreamHead.length) socket.write(upstreamHead);
      socket.pipe(peer).pipe(socket);
      socket.on('error', () => peer.destroy()); peer.on('error', () => socket.destroy());
      socket.on('close', () => peer.destroy()); peer.on('close', () => socket.destroy());
    });
    upstream.on('response', incoming => { socket.end(`HTTP/1.1 ${incoming.statusCode} Upgrade rejected\r\nConnection: close\r\n\r\n`); incoming.resume(); });
    upstream.on('error', () => socket.destroy()); upstream.end();
  });
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const environment = { ...(['.env', '.env.local'].reduce((values, file) => {
    const location = path.join(root, file); return { ...values, ...parseDotEnv(fs.existsSync(location) ? fs.readFileSync(location, 'utf8') : '') };
  }, {})), ...process.env };
  const certificate = environment.LANDROP_TLS_CERT || path.join(root, 'data/tls/lan.pem');
  const key = environment.LANDROP_TLS_KEY || path.join(root, 'data/tls/lan-key.pem');
  if (!fs.existsSync(certificate) || !fs.existsSync(key)) throw new Error('Create LAN TLS certificates using scripts/setup-lan-tls.ps1 or set LANDROP_TLS_CERT and LANDROP_TLS_KEY.');
  const server = createLanProxy({ cert: fs.readFileSync(certificate), key: fs.readFileSync(key), backend: environment.LANDROP_PROXY_BACKEND, websocket: environment.LANDROP_PROXY_WS, frontend: environment.LANDROP_PROXY_FRONTEND });
  const port = Number(environment.LANDROP_TLS_PORT || 3443);
  server.listen(port, environment.LANDROP_TLS_HOST || '0.0.0.0', () => console.log(`LANDrop secure LAN proxy listening on port ${port}. Use https://<certificate hostname>:${port}.`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
