#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
export const repositoryRoot = resolve(scriptDirectory, '..');
const CLIENT_ENVIRONMENT_KEYS = new Set([
  'LANDROP_API_URL', 'LANDROP_WS_URL', 'LANDROP_SESSION_PROFILE',
  'LANDROP_CLIENT_API_URL', 'LANDROP_CLIENT_WS_URL', 'LANDROP_CLIENT_SESSION_PROFILE',
  'API_URL', 'WS_URL', 'VITE_API_URL', 'VITE_WS_URL'
]);

/** Parses only simple KEY=VALUE lines; no shell evaluation is performed. */
export function parseDotEnv(contents) {
  const values = {};
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

export function loadClientConfig(root, inherited = process.env) {
  const fromFiles = {};
  for (const name of ['.env', '.env.local']) {
    const file = resolve(root, name);
    if (existsSync(file)) Object.assign(fromFiles, parseDotEnv(readFileSync(file, 'utf8')));
  }
  const environment = { ...inherited };
  for (const key of CLIENT_ENVIRONMENT_KEYS) {
    if (fromFiles[key] !== undefined && environment[key] === undefined) environment[key] = fromFiles[key];
  }
  // CLIENT_* names are convenient in local config while Java consumes the existing LANDROP_* names.
  if (environment.LANDROP_API_URL === undefined && environment.LANDROP_CLIENT_API_URL !== undefined) {
    environment.LANDROP_API_URL = environment.LANDROP_CLIENT_API_URL;
  }
  if (environment.LANDROP_WS_URL === undefined && environment.LANDROP_CLIENT_WS_URL !== undefined) {
    environment.LANDROP_WS_URL = environment.LANDROP_CLIENT_WS_URL;
  }
  if (environment.LANDROP_SESSION_PROFILE === undefined && environment.LANDROP_CLIENT_SESSION_PROFILE !== undefined) {
    environment.LANDROP_SESSION_PROFILE = environment.LANDROP_CLIENT_SESSION_PROFILE;
  }
  return environment;
}

export function clientConfigSummary(environment) {
  return {
    apiUrl: environment.LANDROP_API_URL || 'saved client preference or http://localhost:3000/api',
    wsUrl: environment.LANDROP_WS_URL || 'derived by the client',
    sessionProfile: environment.LANDROP_SESSION_PROFILE || 'default'
  };
}

export function mavenInvocation(platform, phase) {
  const argumentsForPhase = phase === 'prepare'
    ? ['-q', '-pl', 'frontend', '-am', 'package', '-Dmaven.test.skip=true']
    : ['-q', '-pl', 'frontend', 'javafx:run'];
  if (platform === 'win32') {
    return { command: 'cmd.exe', args: ['/d', '/s', '/c', `call mvnw.cmd ${argumentsForPhase.join(' ')}`] };
  }
  return { command: './mvnw', args: argumentsForPhase };
}

export function createProcessManager({ platform = process.platform, spawnProcess = spawn, killTree = defaultKillTree,
  wait = milliseconds => new Promise(resolveDelay => setTimeout(resolveDelay, milliseconds)) } = {}) {
  let activeChild;
  let stopping = false;

  function launch(invocation, options) {
    activeChild = spawnProcess(invocation.command, invocation.args, options);
    return activeChild;
  }

  async function stop(reason = 'shutdown') {
    if (stopping) return;
    stopping = true;
    const child = activeChild;
    if (!child?.pid || child.exitCode !== null) return;

    // Windows may terminate the npm wrapper immediately after Ctrl+C, so own the
    // complete Maven/JVM tree before awaiting anything. POSIX gets a short grace
    // period for JavaFX shutdown hooks before the group is force-cleaned.
    if (platform === 'win32') {
      killTree(child.pid, platform, reason);
      return;
    }
    child.kill('SIGINT');
    await wait(1200);
    if (child.exitCode === null) killTree(child.pid, platform, reason);
  }

  return { launch, stop, active: () => activeChild, isStopping: () => stopping };
}

export function defaultKillTree(pid, platform) {
  if (platform === 'win32') {
    spawnSync('taskkill.exe', ['/pid', String(pid), '/t', '/f'], { stdio: 'ignore', windowsHide: true });
  } else {
    try { process.kill(-pid, 'SIGTERM'); } catch { try { process.kill(pid, 'SIGTERM'); } catch { /* already gone */ } }
  }
}

function printStartup(config, uiOnly) {
  console.log('\nLANDrop Client');
  console.log('Client UI: Native JavaFX window');
  console.log('Client Runtime: Embedded in the JavaFX client');
  console.log('Client Instance: managed by the client installation');
  console.log(`Server: ${config.apiUrl}`);
  console.log(`Session profile: ${config.sessionProfile}`);
  console.log(`Status: ${uiOnly ? 'Starting UI...' : 'Preparing client UI...'}`);
  console.log('Server availability is checked inside the client; an offline server does not stop startup.\n');
}

function runMaven(manager, invocation, environment) {
  return new Promise((resolveRun, rejectRun) => {
    const child = manager.launch(invocation, {
      cwd: repositoryRoot,
      env: environment,
      stdio: 'inherit',
      // A separate process group lets the Node parent coordinate Ctrl+C and terminate the complete Maven/JVM tree.
      detached: true,
      windowsHide: false
    });
    child.once('error', rejectRun);
      child.once('exit', code => {
        // A forced tree cleanup after Ctrl+C is an intentional shutdown, not a startup failure.
        if (code === 0 || manager.isStopping()) resolveRun();
        else rejectRun(new Error(`Maven exited with code ${code ?? 'unknown'}.`));
      });
  });
}

export async function runClient({ uiOnly = false, dryRun = false } = {}) {
  const environment = loadClientConfig(repositoryRoot);
  const config = clientConfigSummary(environment);
  printStartup(config, uiOnly);
  if (dryRun) return { config, prepared: !uiOnly, launched: false };

  const manager = createProcessManager();
  let shuttingDown = false;
  const shutdown = async signal => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`\nLANDrop Client: ${signal}; closing UI and child processes...`);
    await manager.stop(signal);
    process.exitCode = 0;
  };
  process.once('SIGINT', () => { void shutdown('Ctrl+C'); });
  process.once('SIGTERM', () => { void shutdown('termination signal'); });

  try {
    if (!uiOnly) await runMaven(manager, mavenInvocation(process.platform, 'prepare'), environment);
    if (shuttingDown) return;
    console.log('Status: Opening LANDrop Client native window...');
    await runMaven(manager, mavenInvocation(process.platform, 'ui'), environment);
  } catch (error) {
    if (!shuttingDown) {
      console.error(`LANDrop Client could not start: ${error.message}`);
      console.error('Check Java/Maven availability and resolve the reported build or JavaFX launch error.');
      process.exitCode = 1;
    }
  } finally {
    await manager.stop('launcher finished');
  }
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const argumentsSet = new Set(process.argv.slice(2));
  void runClient({ uiOnly: argumentsSet.has('--ui-only'), dryRun: argumentsSet.has('--dry-run') });
}
