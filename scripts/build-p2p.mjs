import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs/promises';
import path from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(path.join(root, 'frontend/package.json'));
const { build } = require('esbuild');
const check = process.argv.includes('--check');
const entries = [
  ['frontend/lib/p2p/browser-adapter.ts', 'engine.js', 'LANDropP2P'],
  ['frontend/lib/p2p/file-worker.js', 'file-worker.js', undefined],
];
let stale = false;
for (const [entry, name, globalName] of entries) {
  const result = await build({ absWorkingDir: root, entryPoints: [entry], bundle: true, write: false, format: 'iife', globalName, target: 'es2022', minify: true, legalComments: 'inline' });
  const bytes = result.outputFiles[0].contents;
  for (const folder of ['frontend/public/p2p', 'backend/src/main/resources/client/p2p']) {
    const destination = path.join(root, folder, name);
    if (check) {
      const current = await fs.readFile(destination).catch(() => Buffer.alloc(0));
      if (!Buffer.from(bytes).equals(current)) { console.error(`Stale P2P bundle: ${folder}/${name}`); stale = true; }
    } else { await fs.mkdir(path.dirname(destination), { recursive: true }); await fs.writeFile(destination, bytes); }
  }
}
if (stale) process.exitCode = 1;
else console.log(check ? 'Shared P2P bundles match their source.' : 'Built shared P2P engine and worker for both clients.');
