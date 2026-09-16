import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { clientConfigSummary, createProcessManager, loadClientConfig, mavenInvocation, parseDotEnv } from './start-client.mjs';

test('parses client configuration without evaluating shell text', () => {
  const parsed = parseDotEnv('LANDROP_API_URL="http://192.168.1.8:3000/api"\nIGNORED=$(command)\n# comment');
  assert.equal(parsed.LANDROP_API_URL, 'http://192.168.1.8:3000/api');
  assert.equal(parsed.IGNORED, '$(command)');
});

test('only forwards client configuration and keeps explicit environment precedence', () => {
  const root = new URL('.', import.meta.url).pathname;
  const environment = loadClientConfig(root, { LANDROP_API_URL: 'http://explicit/api', DATABASE_URL: 'not-forwarded-by-file' });
  assert.equal(environment.LANDROP_API_URL, 'http://explicit/api');
  assert.equal(clientConfigSummary(environment).apiUrl, 'http://explicit/api');
});

test('uses the Maven wrapper on each supported platform', () => {
  assert.deepEqual(mavenInvocation('win32', 'ui'), {
    command: 'cmd.exe', args: ['/d', '/s', '/c', 'call mvnw.cmd -q -pl frontend javafx:run']
  });
  assert.deepEqual(mavenInvocation('linux', 'prepare'), {
    command: './mvnw', args: ['-q', '-pl', 'frontend', '-am', 'package', '-Dmaven.test.skip=true']
  });
});

test('process manager sends a signal then cleans the child tree', async () => {
  const child = new EventEmitter();
  child.pid = 2468;
  child.exitCode = null;
  const signals = [];
  child.kill = signal => signals.push(signal);
  const trees = [];
  const manager = createProcessManager({
    platform: 'linux',
    spawnProcess: () => child,
    killTree: (pid, platform, reason) => trees.push({ pid, platform, reason }),
    wait: async () => {}
  });
  manager.launch({ command: 'mvn', args: [] }, {});
  await manager.stop('test');
  assert.deepEqual(signals, ['SIGINT']);
  assert.deepEqual(trees, [{ pid: 2468, platform: 'linux', reason: 'test' }]);
});

test('Windows cleanup owns the child tree without sending a Unix signal', async () => {
  const child = new EventEmitter();
  child.pid = 1357;
  child.exitCode = null;
  const signals = [];
  child.kill = signal => signals.push(signal);
  const trees = [];
  const manager = createProcessManager({
    platform: 'win32', spawnProcess: () => child,
    killTree: (pid, platform, reason) => trees.push({ pid, platform, reason }), wait: async () => {}
  });
  manager.launch({ command: 'cmd.exe', args: [] }, {});
  await manager.stop('Ctrl+C');
  assert.deepEqual(signals, []);
  assert.deepEqual(trees, [{ pid: 1357, platform: 'win32', reason: 'Ctrl+C' }]);
});
