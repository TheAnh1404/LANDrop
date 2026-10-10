import { BLOCK_SIZE, hashBytes, hashFile, validHash } from './protocol.js';

const handles = new Map();
let chain = Promise.resolve();
async function execute(command) {
  const { op, key } = command;
  if (op === 'hash') return hashFile(command.file);
  if (op === 'blockHash') return hashBytes(new Uint8Array(command.bytes));
  const root = await navigator.storage.getDirectory();
  if (op === 'open') {
    handles.get(key)?.close();
    const file = await root.getFileHandle(key, { create: true });
    const access = await file.createSyncAccessHandle();
    if (command.offset > access.getSize()) { access.close(); throw new Error('Checkpoint bytes are missing.'); }
    access.truncate(command.offset); access.flush(); handles.set(key, access);
    return command.offset;
  }
  if (op === 'write') {
    const bytes = new Uint8Array(command.bytes);
    if (!validHash(command.hash) || hashBytes(bytes) !== command.hash) throw new Error('Block checksum mismatch.');
    const handle = handles.get(key);
    if (!handle || handle.getSize() !== command.offset || bytes.length > BLOCK_SIZE) throw new Error('Checkpoint offset mismatch.');
    if (handle.write(bytes, { at: command.offset }) !== bytes.length) throw new Error('Incomplete disk write.');
    handle.flush(); // Commit bytes BEFORE the main thread stores or acknowledges a checkpoint.
    return command.offset + bytes.length;
  }
  if (op === 'close' || op === 'finish' || op === 'drop') {
    const access = handles.get(key);
    if (access) { access.flush(); access.close(); handles.delete(key); }
    if (op === 'finish') return hashFile(await (await root.getFileHandle(key)).getFile());
    if (op === 'drop') await root.removeEntry(key).catch(error => { if (error.name !== 'NotFoundError') throw error; });
    return null;
  }
  throw new Error('Unknown file worker operation.');
}
self.onmessage = ({ data }) => {
  chain = chain.then(async () => {
    try { self.postMessage({ requestId: data.requestId, result: await execute(data) }); }
    catch (error) { self.postMessage({ requestId: data.requestId, error: error.message }); }
  });
};
