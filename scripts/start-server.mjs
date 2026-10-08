#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
export const repositoryRoot = resolve(scriptDirectory, '..');

export const SERVER_ENVIRONMENT_KEYS = new Set([
  'PORT', 'AUTH_PORT', 'HTTP_PORT',
  'WS_PORT', 'REALTIME_PORT',
  'WS_ALLOWED_ORIGINS', 'WS_AUTH_TIMEOUT_MS',
  'HOST', 'BIND_HOST',
  'PROTOCOL_VERSION',
  'NODE_ENV', 'ENVIRONMENT',
  'DATABASE_URL',
  'ADMIN_PORT', 'ADMIN_DEV_COMMAND', 'ADMIN_UI_URL',
  'LANDROP_JAR_PATH', 'SERVER_BACKEND_COMMAND',
  'LANDROP_NO_BROWSER', 'NO_OPEN'
]);

/**
 * Parses simple KEY=VALUE format; ignores comments and empty lines.
 */
export function parseDotEnv(contents) {
  const values = {};
  if (!contents) return values;
  for (const rawLine of contents.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    if (separator < 1) continue;
    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  }
  return values;
}

/**
 * Loads server configuration from .env files and process.env.
 * Priority: process.env > .env.local > .env > default values.
 */
export function loadServerConfig(root = repositoryRoot, inherited = process.env) {
  const fromFiles = {};
  for (const name of ['.env', '.env.local']) {
    const file = resolve(root, name);
    if (existsSync(file)) {
      Object.assign(fromFiles, parseDotEnv(readFileSync(file, 'utf8')));
    }
  }

  const env = { ...fromFiles, ...inherited };

  const rawHttpPort = env.PORT || env.AUTH_PORT || env.HTTP_PORT || '3000';
  const httpPort = parseInt(rawHttpPort, 10);

  const rawWsPort = env.WS_PORT || env.REALTIME_PORT || '3001';
  const wsPort = parseInt(rawWsPort, 10);

  const host = env.HOST || env.BIND_HOST || '0.0.0.0';
  const protocolVersion = parseInt(env.PROTOCOL_VERSION || '1', 10);
  const environment = env.NODE_ENV || env.ENVIRONMENT || 'development';
  const databaseUrl = env.DATABASE_URL || null;

  const rawAdminPort = env.ADMIN_PORT || String(httpPort);
  const adminPort = parseInt(rawAdminPort, 10);

  const adminDevCommand = env.ADMIN_DEV_COMMAND || null;
  const jarPath = env.LANDROP_JAR_PATH ? resolve(root, env.LANDROP_JAR_PATH) : null;
  const backendCommand = env.SERVER_BACKEND_COMMAND || null;

  return {
    serverName: 'LANDrop Server',
    host,
    httpPort,
    wsPort,
    adminPort,
    adminPath: '/admin',
    protocolVersion: Number.isNaN(protocolVersion) ? 1 : protocolVersion,
    environment,
    databaseUrl,
    adminDevCommand,
    jarPath,
    backendCommand,
    raw: env
  };
}

/** Pass resolved file configuration to Java, with the same ports used by preflight. */
export function backendEnvironment(config) {
  return {
    ...config.raw,
    PORT: String(config.httpPort),
    HTTP_PORT: String(config.httpPort),
    AUTH_PORT: String(config.httpPort),
    WS_PORT: String(config.wsPort),
    REALTIME_PORT: String(config.wsPort),
    HOST: config.host,
    BIND_HOST: config.host,
    NODE_ENV: config.environment,
  };
}

/**
 * Validates configuration values for runtime safety.
 */
export function validateServerConfig(config) {
  const errors = [];

  if (Number.isNaN(config.httpPort) || config.httpPort < 1024 || config.httpPort > 65535) {
    errors.push(`Invalid HTTP port: ${config.httpPort}. Port must be an integer between 1024 and 65535.`);
  }

  if (Number.isNaN(config.wsPort) || config.wsPort < 1024 || config.wsPort > 65535) {
    errors.push(`Invalid WebSocket port: ${config.wsPort}. Port must be an integer between 1024 and 65535.`);
  }

  if (!Number.isNaN(config.httpPort) && !Number.isNaN(config.wsPort) && config.httpPort === config.wsPort) {
    errors.push(`Port conflict: HTTP port (${config.httpPort}) and WebSocket port (${config.wsPort}) cannot be identical.`);
  }

  if (Number.isNaN(config.adminPort) || config.adminPort < 1024 || config.adminPort > 65535) {
    errors.push(`Invalid Admin UI port: ${config.adminPort}. Port must be an integer between 1024 and 65535.`);
  }

  return {
    valid: errors.length === 0,
    errors
  };
}

/**
 * Discovers real LAN IPv4 addresses on the host system.
 */
export function resolveLanIpv4Addresses(interfaces = os.networkInterfaces()) {
  const addresses = [];
  for (const [name, nets] of Object.entries(interfaces)) {
    if (!nets) continue;
    for (const net of nets) {
      const isIpv4 = net.family === 'IPv4' || net.family === 4;
      if (isIpv4 && !net.internal) {
        // Exclude loopback and link-local (169.254.x.x)
        if (!net.address.startsWith('127.') && !net.address.startsWith('169.254.')) {
          addresses.push(net.address);
        }
      }
    }
  }
  return addresses;
}

/**
 * Checks if a TCP port is currently listening / in use.
 * Tests both connection attempt (catches active servers on 0.0.0.0 or localhost)
 * and socket bind attempt (catches bound ports across interfaces).
 */
export function checkPortInUse(port, host = '0.0.0.0') {
  return new Promise(resolvePromise => {
    // 1. Try active TCP connection first
    const client = net.createConnection({ port, host: '127.0.0.1' });
    let resolved = false;

    const finalize = inUse => {
      if (resolved) return;
      resolved = true;
      resolvePromise(inUse);
    };

    client.once('connect', () => {
      client.destroy();
      finalize(true);
    });

    client.once('error', () => {
      // 2. If connection refused on localhost, attempt binding to verify availability
      const tester = net.createServer();
      tester.once('error', err => {
        finalize(err.code === 'EADDRINUSE');
      });
      tester.once('listening', () => {
        tester.close(() => finalize(false));
      });
      tester.listen(port, host);
    });
  });
}

/**
 * Generates Maven or executable invocation for starting LANDrop backend.
 */
export function mavenServerInvocation(platform, phase = 'run') {
  const argumentsForPhase = phase === 'prepare'
    ? ['-q', '-pl', 'backend', 'compile', '-Dmaven.test.skip=true']
    : ['-pl', 'backend', 'exec:java', '-Dexec.mainClass=com.landrop.server.LandropServerMain'];

  if (platform === 'win32') {
    return {
      command: 'cmd.exe',
      args: ['/d', '/s', '/c', `call mvnw.cmd ${argumentsForPhase.join(' ')} < nul`]
    };
  }
  return { command: './mvnw', args: argumentsForPhase };
}

/**
 * Resolves the backend launch command, allowing built JAR or custom command overrides.
 */
export function resolveBackendInvocation(platform, config = {}, phase = 'run') {
  if (config.backendCommand) {
    if (platform === 'win32') {
      return { command: 'cmd.exe', args: ['/d', '/s', '/c', config.backendCommand] };
    }
    return { command: '/bin/sh', args: ['-c', config.backendCommand] };
  }
  if (config.jarPath && existsSync(config.jarPath)) {
    return { command: 'java', args: ['-jar', config.jarPath] };
  }
  return mavenServerInvocation(platform, phase);
}

/**
 * Terminates a process and its child tree cleanly across operating systems.
 */
export function defaultKillTree(pid, platform) {
  if (!pid) return;
  if (platform === 'win32') {
    spawnSync('taskkill.exe', ['/pid', String(pid), '/t', '/f'], { stdio: 'ignore', windowsHide: true });
  } else {
    try {
      process.kill(-pid, 'SIGTERM');
    } catch {
      try {
        process.kill(pid, 'SIGTERM');
      } catch {
        /* already dead */
      }
    }
  }
}

/**
 * Manages multiple background child processes with unified graceful shutdown.
 */
export function createProcessManager({
  platform = process.platform,
  spawnProcess = spawn,
  killTree = defaultKillTree,
  wait = ms => new Promise(r => setTimeout(r, ms))
} = {}) {
  const processes = new Map();
  let stopping = false;
  const exitListeners = new Set();

  function launch(name, invocation, options) {
    const child = spawnProcess(invocation.command, invocation.args, options);
    processes.set(name, child);

    child.once('exit', (code, signal) => {
      for (const listener of exitListeners) {
        listener(name, code, signal);
      }
    });

    return child;
  }

  async function stopAll(reason = 'shutdown') {
    if (stopping) return;
    stopping = true;

    // Send soft termination first on Unix
    for (const [, child] of processes.entries()) {
      if (!child?.pid || child.exitCode !== null) continue;
      if (platform !== 'win32') {
        try { child.kill('SIGINT'); } catch {}
      }
    }

    await wait(platform === 'win32' ? 500 : 1200);

    // Hard kill tree to guarantee no port conflicts remain
    for (const [, child] of processes.entries()) {
      if (!child?.pid || child.exitCode !== null) continue;
      killTree(child.pid, platform, reason);
    }
  }

  function getProcess(name) {
    return processes.get(name);
  }

  function onChildExit(callback) {
    exitListeners.add(callback);
    return () => exitListeners.delete(callback);
  }

  return {
    launch,
    stopAll,
    getProcess,
    isStopping: () => stopping,
    onChildExit
  };
}

/**
 * Polls the backend /health endpoint until it reports 200 OK.
 */
export async function waitForHealth(healthUrl, {
  timeoutMs = 30000,
  intervalMs = 300,
  isAborted = () => false,
  fetchFn = globalThis.fetch
} = {}) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (isAborted()) {
      throw new Error('Server startup aborted: backend process exited prematurely.');
    }
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2000);
      const res = await fetchFn(healthUrl, { signal: controller.signal });
      clearTimeout(timer);
      if (res.status === 200) {
        const body = await res.json().catch(() => null);
        return body || { status: 'ok' };
      }
    } catch {
      // Backend not yet ready
    }
    await new Promise(r => setTimeout(r, intervalMs));
  }
  throw new Error(`LANDrop backend health check timed out after ${timeoutMs}ms at ${healthUrl}`);
}

/**
 * Verifies that the Admin UI endpoint is listening and responding.
 */
export async function waitForAdminReady(adminUrl, {
  timeoutMs = 15000,
  intervalMs = 300,
  isAborted = () => false,
  fetchFn = globalThis.fetch
} = {}) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (isAborted()) {
      throw new Error('Server startup aborted: process exited prematurely.');
    }
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2000);
      const res = await fetchFn(adminUrl, { signal: controller.signal });
      clearTimeout(timer);
      if (res.status === 200 || res.status === 304) {
        return true;
      }
    } catch {
      // Admin UI not yet ready
    }
    await new Promise(r => setTimeout(r, intervalMs));
  }
  throw new Error(`LANDrop Admin UI readiness check timed out after ${timeoutMs}ms at ${adminUrl}`);
}

/**
 * Formats the cross-platform command to launch the system default browser.
 */
export function browserInvocation(url, platform = process.platform) {
  if (platform === 'win32') {
    return { command: 'cmd.exe', args: ['/c', 'start', '', url] };
  }
  if (platform === 'darwin') {
    return { command: 'open', args: [url] };
  }
  return { command: 'xdg-open', args: [url] };
}

/**
 * Opens default browser in a non-blocking, detached manner.
 */
export function openBrowser(url, { platform = process.platform, spawnProcess = spawn, disabled = false } = {}) {
  if (disabled) return false;
  const invocation = browserInvocation(url, platform);
  try {
    const child = spawnProcess(invocation.command, invocation.args, {
      stdio: 'ignore',
      windowsHide: true,
      detached: true
    });
    child.on('error', () => {});
    if (child.unref) child.unref();
    return true;
  } catch {
    return false;
  }
}

/**
 * Formats the rich terminal startup banner.
 */
export function formatServerBanner({
  serverName = 'LANDrop Server',
  status = 'Ready',
  lanIps = [],
  httpPort = 3000,
  wsPort = 3001,
  adminUrl = 'http://localhost:3000/admin',
  lanApiUrl = 'http://localhost:3000/api',
  protocolVersion = 1,
  environment = 'development'
} = {}) {
  const ipDisplay = lanIps.length > 0 ? lanIps.join(', ') : '127.0.0.1';
  return [
    '==================================================',
    'LANDrop Server is ready.',
    '==================================================',
    `Status:           ${status}`,
    `Server Name:      ${serverName}`,
    `LAN IP:           ${ipDisplay}`,
    `API Port:         ${httpPort}`,
    `WebSocket Port:   ${wsPort}`,
    `Admin UI:         ${adminUrl}`,
    `LAN API:          ${lanApiUrl}`,
    `Protocol Version: v${protocolVersion}`,
    `Environment:      ${environment}`,
    '=================================================='
  ].join('\n');
}

/**
 * Main orchestration function for starting LANDrop Server.
 */
export async function runServer({
  backendOnly = false,
  adminOnly = false,
  dryRun = false,
  noOpen = false,
  timeoutMs = 35000,
  keepAlive = true,
  manager = createProcessManager()
} = {}) {
  const config = loadServerConfig(repositoryRoot);

  // 1. Validate configuration
  const validation = validateServerConfig(config);
  if (!validation.valid) {
    console.error('\nLANDrop Server Configuration Error:');
    for (const err of validation.errors) {
      console.error(` - ${err}`);
    }
    process.exitCode = 1;
    return { success: false, errors: validation.errors };
  }

  // 2. Resolve network addresses
  const lanIps = resolveLanIpv4Addresses();
  const primaryLanIp = lanIps.length > 0 ? lanIps[0] : (config.host === '0.0.0.0' ? '127.0.0.1' : config.host);
  const localAdminUrl = `http://localhost:${config.adminPort}${config.adminPath}`;
  const lanApiUrl = `http://${primaryLanIp}:${config.httpPort}/api`;
  const healthUrl = `http://127.0.0.1:${config.httpPort}/health`;
  const adminCheckUrl = `http://127.0.0.1:${config.adminPort}${config.adminPath}`;

  // 3. Admin-only mode: check and open existing Admin UI
  if (adminOnly) {
    const isRunning = await checkPortInUse(config.adminPort);
    if (isRunning) {
      console.log(`LANDrop Server is active. Opening Admin UI at ${localAdminUrl}...`);
      openBrowser(localAdminUrl);
      return { success: true, opened: true };
    }
    console.log(`LANDrop Server is not running on port ${config.adminPort}.`);
    console.log('To start both the Server and Admin UI, run:\n  npm run server\n');
    return { success: false, reason: 'server_not_running' };
  }

  // 4. Pre-flight check: ensure ports are not already occupied
  if (!dryRun) {
    const httpBusy = await checkPortInUse(config.httpPort);
    if (httpBusy) {
      console.error(`\nLANDrop Server Pre-flight Error: Port ${config.httpPort} is already in use.`);
      console.error(`Another process is listening on port ${config.httpPort}. Please stop that process or change PORT in .env.`);
      process.exitCode = 1;
      return { success: false, error: `Port ${config.httpPort} in use` };
    }

    const wsBusy = await checkPortInUse(config.wsPort);
    if (wsBusy) {
      console.error(`\nLANDrop Server Pre-flight Error: WebSocket Port ${config.wsPort} is already in use.`);
      console.error(`Another process is listening on port ${config.wsPort}. Please stop that process or change WS_PORT in .env.`);
      process.exitCode = 1;
      return { success: false, error: `Port ${config.wsPort} in use` };
    }
  }

  // 5. Dry run summary
  if (dryRun) {
    const banner = formatServerBanner({
      serverName: config.serverName,
      status: 'Ready (dry-run)',
      lanIps,
      httpPort: config.httpPort,
      wsPort: config.wsPort,
      adminUrl: localAdminUrl,
      lanApiUrl,
      protocolVersion: config.protocolVersion,
      environment: config.environment
    });
    console.log('\n' + banner + '\n');
    return { config, success: true, dryRun: true };
  }

  console.log('\nStarting LANDrop Server...');
  console.log(`Mode:             ${backendOnly ? 'Backend Only' : 'Unified Server & Admin Control Center'}`);
  console.log(`Host:             ${config.host}`);
  console.log(`HTTP Port:        ${config.httpPort}`);
  console.log(`WebSocket Port:   ${config.wsPort}`);
  console.log(`Environment:      ${config.environment}`);
  if (config.databaseUrl) {
    console.log('Database:         PostgreSQL (configured via DATABASE_URL)');
  } else {
    console.log('Database:         Standalone LAN in-memory mode (No DATABASE_URL configured)');
  }

  // 6. Graceful shutdown handler
  let shuttingDown = false;
  let shutdown = async signal => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`\nLANDrop Server: ${signal}; stopping all child processes cleanly...`);
    await manager.stopAll(signal);
    console.log('LANDrop Server stopped cleanly.');
    process.exitCode = 0;
  };

  process.once('SIGINT', () => { void shutdown('Ctrl+C'); });
  process.once('SIGTERM', () => { void shutdown('termination signal'); });

  // 7. Watch for unexpected process exits
  manager.onChildExit((name, code, signal) => {
    if (!manager.isStopping() && !shuttingDown) {
      console.error(`\n[ALERT] LANDrop ${name} process terminated unexpectedly (code: ${code ?? 'none'}, signal: ${signal ?? 'none'}).`);
      void shutdown(`${name} process crash`);
    }
  });

  try {
    const childEnvironment = backendEnvironment(config);
    // 8. Compile backend if needed (fast verification)
    const prepareInvocation = resolveBackendInvocation(process.platform, config, 'prepare');
    console.log('Verifying backend compilation...');
    const compileResult = spawnSync(prepareInvocation.command, prepareInvocation.args, {
      cwd: repositoryRoot,
      env: childEnvironment,
      stdio: ['ignore', 'inherit', 'inherit']
    });

    if (compileResult.status !== 0) {
      throw new Error(`Backend compilation failed with code ${compileResult.status}. Resolve compiler errors above.`);
    }

    if (shuttingDown) return { success: false };

    // 9. Launch LANDrop Server backend process
    console.log('Launching LANDrop Server backend...');
    const backendInvocation = resolveBackendInvocation(process.platform, config, 'run');
    manager.launch('backend', backendInvocation, {
      cwd: repositoryRoot,
      env: childEnvironment,
      stdio: ['ignore', 'inherit', 'inherit'],
      detached: true,
      windowsHide: true
    });

    // 10. Launch external Admin UI process if configured
    if (config.adminDevCommand) {
      console.log(`Launching external Admin UI (${config.adminDevCommand})...`);
      const adminInvocation = process.platform === 'win32'
        ? { command: 'cmd.exe', args: ['/d', '/s', '/c', `${config.adminDevCommand} < nul`] }
        : { command: '/bin/sh', args: ['-c', config.adminDevCommand] };
      manager.launch('admin', adminInvocation, {
        cwd: repositoryRoot,
        env: childEnvironment,
        stdio: ['ignore', 'inherit', 'inherit'],
        detached: true,
        windowsHide: true
      });
    }

    // 11. Wait for backend /health check
    console.log(`Waiting for backend health check at ${healthUrl}...`);
    await waitForHealth(healthUrl, {
      timeoutMs,
      intervalMs: 400,
      isAborted: () => manager.getProcess('backend')?.exitCode !== null || shuttingDown
    });

    // 12. Wait for Admin UI readiness
    if (!backendOnly) {
      console.log(`Verifying Admin UI readiness at ${adminCheckUrl}...`);
      await waitForAdminReady(adminCheckUrl, {
        timeoutMs: 15000,
        intervalMs: 400,
        isAborted: () => manager.getProcess('backend')?.exitCode !== null || shuttingDown
      });
    }

    if (shuttingDown) return { success: false };

    // 13. Display rich startup banner
    const banner = formatServerBanner({
      serverName: config.serverName,
      status: 'Ready',
      lanIps,
      httpPort: config.httpPort,
      wsPort: config.wsPort,
      adminUrl: localAdminUrl,
      lanApiUrl,
      protocolVersion: config.protocolVersion,
      environment: config.environment
    });
    console.log('\n' + banner + '\n');

    // 14. Automatically open default browser once ready
    const shouldOpen = !backendOnly && !noOpen && !process.env.LANDROP_NO_BROWSER && !process.env.CI;
    if (shouldOpen) {
      console.log(`Opening Admin UI in default browser: ${localAdminUrl}`);
      openBrowser(localAdminUrl);
    }

    // 15. Keep orchestrator active and monitor child processes until shutdown
    if (keepAlive) {
      await new Promise((resolveWait, rejectWait) => {
        const exitUnsub = manager.onChildExit((name, code, signal) => {
          if (shuttingDown) {
            resolveWait();
          } else {
            rejectWait(new Error(`${name} process terminated unexpectedly (code: ${code ?? 'none'}, signal: ${signal ?? 'none'}).`));
          }
        });

        const previousShutdown = shutdown;
        shutdown = async signal => {
          exitUnsub();
          await previousShutdown(signal);
          resolveWait();
        };
      });
    }

    return {
      success: true,
      config,
      localAdminUrl,
      lanApiUrl
    };
  } catch (error) {
    if (!shuttingDown) {
      console.error(`\nLANDrop Server failed to start: ${error.message}`);
      process.exitCode = 1;
      await manager.stopAll('startup failure');
      return { success: false, error: error.message };
    }
  }
}

// CLI entrypoint execution
const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const args = new Set(process.argv.slice(2));
  void runServer({
    backendOnly: args.has('--backend-only'),
    adminOnly: args.has('--admin-only'),
    dryRun: args.has('--dry-run'),
    noOpen: args.has('--no-open')
  });
}
