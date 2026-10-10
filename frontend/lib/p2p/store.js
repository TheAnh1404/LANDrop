/** One transaction for message + outbox changes; I/O failures are never acknowledged. */
export class LocalStore {
  constructor(name = 'landrop-p2p-v2', memory = false) {
    this.memory = memory || typeof indexedDB === 'undefined';
    this.records = new Map();
    this.database = null;
    this.name = name;
  }
  async db() {
    if (!this.database) this.database = new Promise((resolve, reject) => {
      const request = indexedDB.open(this.name, 1);
      request.onupgradeneeded = () => request.result.createObjectStore('records');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => { this.database = null; reject(request.error); };
      request.onblocked = () => reject(new Error('Close older LANDrop tabs to unlock local storage.'));
    });
    return this.database;
  }
  async get(key) {
    if (this.memory) return structuredClone(this.records.get(key));
    const db = await this.db();
    return new Promise((resolve, reject) => {
      const request = db.transaction('records').objectStore('records').get(key);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  async batch(changes) {
    if (this.memory) {
      for (const [key, value] of changes) value === undefined ? this.records.delete(key) : this.records.set(key, structuredClone(value));
      return;
    }
    const db = await this.db();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction('records', 'readwrite', { durability: 'strict' });
      const records = transaction.objectStore('records');
      for (const [key, value] of changes) value === undefined ? records.delete(key) : records.put(value, key);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error || new Error('Local storage transaction aborted.'));
    });
  }
  put(key, value) { return this.batch([[key, value]]); }
  remove(key) { return this.batch([[key, undefined]]); }
  async list(prefix) {
    if (this.memory) return [...this.records].filter(([key]) => key.startsWith(prefix)).map(([key, value]) => ({ key, value: structuredClone(value) }));
    const db = await this.db();
    return new Promise((resolve, reject) => {
      const items = [];
      const request = db.transaction('records').objectStore('records').openCursor(IDBKeyRange.bound(prefix, prefix + '\uffff'));
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) { resolve(items); return; }
        items.push({ key: cursor.key, value: cursor.value }); cursor.continue();
      };
      request.onerror = () => reject(request.error);
    });
  }
}
