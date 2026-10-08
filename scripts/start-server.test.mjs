import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import http from 'node:http';
import net from 'node:net';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';

import {
  parseDotEnv,
  loadServerConfig,
  backendEnvironment,
  validateServerConfig,
  resolveLanIpv4Addresses,
  checkPortInUse,
  mavenServerInvocation,
  resolveBackendInvocation,
  browserInvocation,
  openBrowser,
  formatServerBanner,
  waitForHealth,
  waitForAdminReady,
  createProcessManager,
  runServer
} from './start-server.mjs';

test('parseDotEnv parses KEY=VALUE lines without evaluating shell expressions', () => {
  const input = `
    # LANDrop Config
    PORT=3050
    WS_PORT="3051"
    HOST='0.0.0.0'
    COMPLEX_SHELL=\$(rm -rf /)
    EMPTY_VAL=
  `;
  const parsed = parseDotEnv(input);
  assert.equal(parsed.PORT, '3050');
  assert.equal(parsed.WS_PORT, '3051');
  assert.equal(parsed.HOST, '0.0.0.0');
  assert.equal(parsed.COMPLEX_SHELL, '$(rm -rf /)');
  assert.equal(parsed.EMPTY_VAL, '');
});

test('loadServerConfig returns canonical defaults when no environment is set', () => {
  const config = loadServerConfig('/non/existent/path', {});
  assert.equal(config.serverName, 'LANDrop Server');
  assert.equal(config.httpPort, 3000);
  assert.equal(config.wsPort, 3001);
  assert.equal(config.host, '0.0.0.0');
  assert.equal(config.adminPort, 3000);
  assert.equal(config.adminPath, '/admin');
  assert.equal(config.protocolVersion, 1);
  assert.equal(config.environment, 'development');
});

test('loadServerConfig respects inherited process.env overrides and aliases', () => {
  const inherited = {
    PORT: '4000',
    WS_PORT: '4001',
    HOST: '192.168.1.100',
    PROTOCOL_VERSION: '2',
    NODE_ENV: 'staging',
    ADMIN_PORT: '8080'
  };
  const config = loadServerConfig('/non/existent/path', inherited);
  assert.equal(config.httpPort, 4000);
  assert.equal(config.wsPort, 4001);
  assert.equal(config.host, '192.168.1.100');
  assert.equal(config.protocolVersion, 2);
  assert.equal(config.environment, 'staging');
  assert.equal(config.adminPort, 8080);
});

test('validateServerConfig enforces port boundaries and prevents collisions', () => {
  // Valid config
  const valid = validateServerConfig({
    httpPort: 3000,
    wsPort: 3001,
    adminPort: 3000
  });
  assert.equal(valid.valid, true);
  assert.equal(valid.errors.length, 0);

  // Invalid HTTP port
  const invalidHttp = validateServerConfig({
    httpPort: 80, // privileged port (< 1024)
    wsPort: 3001,
    adminPort: 3000
  });
  assert.equal(invalidHttp.valid, false);
  assert.match(invalidHttp.errors[0], /Invalid HTTP port/);

  // Port collision
  const collision = validateServerConfig({
    httpPort: 3000,
    wsPort: 3000,
    adminPort: 3000
  });
  assert.equal(collision.valid, false);
  assert.match(collision.errors[0], /Port conflict/);
});

test('Java receives file Origin settings and exactly the ports resolved by launcher preflight', () => {
  const root = mkdtempSync(join(tmpdir(), 'landrop-launch-'));
  try {
    writeFileSync(join(root, '.env'), 'AUTH_PORT=3100\nREALTIME_PORT=3101\nWS_ALLOWED_ORIGINS=http://192.168.22.206:3000\n');
    writeFileSync(join(root, '.env.local'), 'WS_AUTH_TIMEOUT_MS=12000\nWS_PORT=4101\n');
    const config = loadServerConfig(root, { WS_PORT: '5101', ENVIRONMENT: 'test' });
    const child = backendEnvironment(config);
    assert.equal(child.WS_ALLOWED_ORIGINS, 'http://192.168.22.206:3000');
    assert.equal(child.WS_AUTH_TIMEOUT_MS, '12000');
    assert.equal(child.PORT, String(config.httpPort));
    assert.equal(child.PORT, '3100');
    assert.equal(child.REALTIME_PORT, String(config.wsPort));
    assert.equal(child.WS_PORT, '5101');
    assert.equal(child.NODE_ENV, 'test');
  } finally {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()));
    assert.match(basename(root), /^landrop-launch-/);
    rmSync(root, { recursive: true, force: true });
  }
});

test('resolveLanIpv4Addresses excludes loopback, link-local, and IPv6', () => {
  const mockInterfaces = {
    lo: [
      { address: '127.0.0.1', family: 'IPv4', internal: true }
    ],
    eth0: [
      { address: '192.168.1.55', family: 'IPv4', internal: false },
      { address: 'fe80::1', family: 'IPv6', internal: false }
    ],
    wlan0: [
      { address: '10.0.0.12', family: 4, internal: false },
      { address: '169.254.10.20', family: 'IPv4', internal: false } // link-local
    ]
  };

  const addrs = resolveLanIpv4Addresses(mockInterfaces);
  assert.deepEqual(addrs, ['192.168.1.55', '10.0.0.12']);
});

test('checkPortInUse correctly identifies free and bound ports', async () => {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const boundPort = server.address().port;

  try {
    const isBound = await checkPortInUse(boundPort, '127.0.0.1');
    assert.equal(isBound, true, 'Bound port must be reported as in use');

    const isFree = await checkPortInUse(boundPort + 100, '127.0.0.1');
    assert.equal(isFree, false, 'Free port must be reported as available');
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});

test('mavenServerInvocation produces valid platform command structures', () => {
  const win = mavenServerInvocation('win32', 'run');
  assert.equal(win.command, 'cmd.exe');
  assert.match(win.args[3], /mvnw\.cmd -pl backend exec:java.*< nul/);

  const linux = mavenServerInvocation('linux', 'run');
  assert.equal(linux.command, './mvnw');
  assert.deepEqual(linux.args, ['-pl', 'backend', 'exec:java', '-Dexec.mainClass=com.landrop.server.LandropServerMain']);

  const prepare = mavenServerInvocation('linux', 'prepare');
  assert.deepEqual(prepare.args, ['-q', '-pl', 'backend', 'compile', '-Dmaven.test.skip=true']);
});

test('resolveBackendInvocation supports custom commands and built JARs', () => {
  const custom = resolveBackendInvocation('linux', { backendCommand: 'java -jar custom.jar' });
  assert.deepEqual(custom, { command: '/bin/sh', args: ['-c', 'java -jar custom.jar'] });

  const customWin = resolveBackendInvocation('win32', { backendCommand: 'java -jar custom.jar' });
  assert.deepEqual(customWin, { command: 'cmd.exe', args: ['/d', '/s', '/c', 'java -jar custom.jar'] });
});

test('browserInvocation returns appropriate launcher per platform', () => {
  const url = 'http://localhost:3000/admin';
  assert.deepEqual(browserInvocation(url, 'win32'), {
    command: 'cmd.exe',
    args: ['/c', 'start', '', url]
  });

  assert.deepEqual(browserInvocation(url, 'darwin'), {
    command: 'open',
    args: [url]
  });

  assert.deepEqual(browserInvocation(url, 'linux'), {
    command: 'xdg-open',
    args: [url]
  });
});

test('openBrowser honors disabled flag', () => {
  let spawned = false;
  const mockSpawn = () => {
    spawned = true;
    return { on: () => {}, unref: () => {} };
  };

  const result = openBrowser('http://localhost:3000/admin', {
    disabled: true,
    spawnProcess: mockSpawn
  });
  assert.equal(result, false);
  assert.equal(spawned, false);
});

test('formatServerBanner formats required operational status details', () => {
  const banner = formatServerBanner({
    serverName: 'LANDrop Server',
    status: 'Ready',
    lanIps: ['192.168.1.50'],
    httpPort: 3000,
    wsPort: 3001,
    adminUrl: 'http://localhost:3000/admin',
    lanApiUrl: 'http://192.168.1.50:3000/api',
    protocolVersion: 1,
    environment: 'production'
  });

  assert.match(banner, /LANDrop Server is ready\./);
  assert.match(banner, /Status:\s+Ready/);
  assert.match(banner, /Server Name:\s+LANDrop Server/);
  assert.match(banner, /LAN IP:\s+192\.168\.1\.50/);
  assert.match(banner, /API Port:\s+3000/);
  assert.match(banner, /WebSocket Port:\s+3001/);
  assert.match(banner, /Admin UI:\s+http:\/\/localhost:3000\/admin/);
  assert.match(banner, /LAN API:\s+http:\/\/192\.168\.1\.50:3000\/api/);
  assert.match(banner, /Protocol Version:\s+v1/);
  assert.match(banner, /Environment:\s+production/);
});

test('waitForHealth verifies successful /health endpoint response', async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok', serverVersion: '1.0.0' }));
    } else {
      res.writeHead(404);
      res.end();
    }
  });

  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  try {
    const report = await waitForHealth(`http://127.0.0.1:${port}/health`, {
      timeoutMs: 3000,
      intervalMs: 50
    });
    assert.equal(report.status, 'ok');
    assert.equal(report.serverVersion, '1.0.0');
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});

test('waitForHealth aborts immediately when isAborted() signals child crash', async () => {
  await assert.rejects(
    async () => {
      await waitForHealth('http://127.0.0.1:59999/health', {
        timeoutMs: 5000,
        intervalMs: 50,
        isAborted: () => true
      });
    },
    /Server startup aborted/
  );
});

test('waitForAdminReady verifies successful Admin UI HTTP response', async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/admin') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<!DOCTYPE html><html><body>LANDrop Control Center</body></html>');
    } else {
      res.writeHead(404);
      res.end();
    }
  });

  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  try {
    const ready = await waitForAdminReady(`http://127.0.0.1:${port}/admin`, {
      timeoutMs: 3000,
      intervalMs: 50
    });
    assert.equal(ready, true);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});

test('createProcessManager terminates multiple child processes via taskkill on Windows', async () => {
  const backend = new EventEmitter();
  backend.pid = 1111;
  backend.exitCode = null;

  const admin = new EventEmitter();
  admin.pid = 2222;
  admin.exitCode = null;

  const killedTrees = [];
  const mockKillTree = (pid, platform, reason) => {
    killedTrees.push({ pid, platform, reason });
  };

  const manager = createProcessManager({
    platform: 'win32',
    spawnProcess: (cmd) => cmd.includes('backend') ? backend : admin,
    killTree: mockKillTree,
    wait: async () => {}
  });

  manager.launch('backend', { command: 'backend.exe', args: [] }, {});
  manager.launch('admin', { command: 'admin.exe', args: [] }, {});

  await manager.stopAll('test_shutdown');

  assert.deepEqual(killedTrees, [
    { pid: 1111, platform: 'win32', reason: 'test_shutdown' },
    { pid: 2222, platform: 'win32', reason: 'test_shutdown' }
  ]);
});

test('createProcessManager notifies on child crash', (t, done) => {
  const backend = new EventEmitter();
  backend.pid = 9999;
  backend.exitCode = null;

  const manager = createProcessManager({
    platform: 'win32',
    spawnProcess: () => backend,
    killTree: () => {},
    wait: async () => {}
  });

  manager.onChildExit((name, code) => {
    assert.equal(name, 'backend');
    assert.equal(code, 1);
    done();
  });

  manager.launch('backend', { command: 'test.exe', args: [] }, {});
  backend.emit('exit', 1, null);
});

test('runServer executes dry-run cleanly with validated configuration', async () => {
  const result = await runServer({ dryRun: true });
  assert.equal(result.success, true);
  assert.equal(result.dryRun, true);
  assert.equal(result.config.serverName, 'LANDrop Server');
});
