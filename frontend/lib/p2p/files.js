import { hashBytes, hashFile, MEMORY_LIMIT, diskKey } from './protocol.js';

export class FileIO {
  constructor(scope, workerUrl = '/p2p/file-worker.js') {
    this.scope = scope; this.workerUrl = workerUrl; this.worker = null; this.hashWorker = null; this.calls = new Map(); this.sequence = 0;
    this.memory = new Map(); this.retained = new Map(); this.urls = new Map();
    this.disk = typeof Worker !== 'undefined' && globalThis.isSecureContext === true && !!globalThis.navigator?.storage?.getDirectory;
  }
  call(op, parameters = {}, transfers = []) {
    // Source hashing must not hold up incoming checkpoint writes in a bidirectional session.
    const workerKey = ['hash', 'blockHash'].includes(op) ? 'hashWorker' : 'worker';
    if (!this[workerKey]) {
      const worker = this[workerKey] = new Worker(this.workerUrl);
      worker.onmessage = ({ data }) => {
        const pending = this.calls.get(data.requestId);
        if (!pending) return;
        this.calls.delete(data.requestId); data.error ? pending.reject(new Error(data.error)) : pending.resolve(data.result);
      };
      worker.onerror = () => {
        for (const [id, pending] of this.calls) if (pending.worker === worker) { pending.reject(new Error('File worker failed.')); this.calls.delete(id); }
        worker.terminate(); if (this[workerKey] === worker) this[workerKey] = null;
      };
    }
    const worker = this[workerKey];
    const requestId = ++this.sequence;
    return new Promise((resolve, reject) => {
      this.calls.set(requestId, { resolve, reject, worker });
      try { worker.postMessage({ requestId, op, ...parameters }, transfers); }
      catch (error) { this.calls.delete(requestId); reject(error); }
    });
  }
  hash(file) { return typeof Worker !== 'undefined' ? this.call('hash', { file }) : hashFile(file); }
  blockHash(bytes) { return typeof Worker !== 'undefined' ? this.call('blockHash', { bytes: bytes.buffer }) : Promise.resolve(hashBytes(bytes)); }
  async open(offer, offset, disk = this.disk) {
    if (disk) return this.call('open', { key: diskKey(this.scope, offer.id), offset });
    if (offer.size > MEMORY_LIMIT) throw new Error('Large files require HTTPS and persistent browser file storage.');
    const used = [...this.retained.entries()].reduce((sum, [id, size]) => id === offer.id ? sum : sum + size, 0);
    if (used + offer.size > MEMORY_LIMIT) throw new Error('The 64 MiB memory budget is full. Remove finished files or use HTTPS disk storage.');
    if (offset && !this.memory.has(offer.id)) throw new Error('This browser cannot resume after reload.');
    if (!offset) this.memory.set(offer.id, []);
    this.retained.set(offer.id, offer.size);
    return offset;
  }
  async write(offer, offset, bytes, hash, disk) {
    if (disk) return this.call('write', { key: diskKey(this.scope, offer.id), offset, hash, bytes: bytes.buffer }, [bytes.buffer]);
    if (hashBytes(bytes) !== hash) throw new Error('Block checksum mismatch.');
    this.memory.get(offer.id).push(bytes); return offset + bytes.length;
  }
  async finish(offer, disk, destination) {
    let file, hash;
    if (disk) {
      hash = await this.call('finish', { key: diskKey(this.scope, offer.id) });
      const root = await navigator.storage.getDirectory();
      file = await (await root.getFileHandle(diskKey(this.scope, offer.id))).getFile();
    } else { file = new Blob(this.memory.get(offer.id), { type: offer.mime }); hash = await hashFile(file); }
    if (hash !== offer.hash) throw new Error('File checksum mismatch. Select the original source file to resume.');
    if (destination) { const stream = await destination.createWritable(); await file.stream().pipeTo(stream); }
    const previous = this.urls.get(offer.id); if (previous) URL.revokeObjectURL(previous);
    const blobUrl = URL.createObjectURL(file); this.urls.set(offer.id, blobUrl);
    if (!disk) this.memory.delete(offer.id);
    return { blobUrl, hash, savedTo: destination ? 'disk' : disk ? 'browser' : 'memory' };
  }
  async close(offer, disk, discard = false) {
    if (disk) await this.call(discard ? 'drop' : 'close', { key: diskKey(this.scope, offer.id) });
    else if (discard) { this.memory.delete(offer.id); this.retained.delete(offer.id); }
    if (discard) { const url = this.urls.get(offer.id); if (url) URL.revokeObjectURL(url); this.urls.delete(offer.id); }
  }
  cancelHash() {
    const worker = this.hashWorker; if (!worker) return;
    worker.terminate(); this.hashWorker = null;
    for (const [id, pending] of this.calls) if (pending.worker === worker) { pending.reject(new Error('Source hashing paused.')); this.calls.delete(id); }
  }
  dispose() { this.worker?.terminate(); this.hashWorker?.terminate(); this.worker = null; this.hashWorker = null; for (const pending of this.calls.values()) pending.reject(new Error('File worker stopped.')); this.calls.clear(); for (const url of this.urls.values()) URL.revokeObjectURL(url); this.urls.clear(); this.memory.clear(); this.retained.clear(); }
}
