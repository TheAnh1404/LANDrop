import { LocalStore } from './store.js';
import { FileIO } from './files.js';
import { VERSION, BLOCK_SIZE, FRAME_SIZE, MEMORY_LIMIT, MAX_FILE_SIZE, decodeFrame, encodeFrame, hashBytes, uuid, validId, validHash, validateOffer, delay } from './protocol.js';

export { LocalStore, FileIO, MEMORY_LIMIT, VERSION };
export { uuid } from './protocol.js';
const TERMINAL = new Set(['completed', 'cancelled', 'rejected']);
const BUSY = new Set(['preparing', 'waiting', 'transferring', 'verifying']);

/** Browser-independent reliable protocol. Both shipped clients use this implementation. */
export class ReliablePeer {
  constructor(options) {
    if (!options.scope) throw new Error('An authenticated conversation scope is required.');
    this.options = options; this.scope = options.scope;
    this.store = options.store || new LocalStore(); this.io = options.io || new FileIO(this.scope, options.workerUrl);
    this.control = null; this.files = null; this.capable = false; this.disposed = false;
    this.tasks = new Map(); this.sources = new Map(); this.incoming = new Map(); this.destinations = new Map();
    this.waiters = new Map(); this.retryTimers = new Map(); this.attempts = new Map(); this.running = false;
    this.messages = new Map(); this.queue = []; this.inbound = Promise.resolve(); this.chatInbound = Promise.resolve(); this.queuedBytes = 0;
    this.maxMessageSize = options.maxMessageSize || 65536; this.activeIncoming = null;
    this.initialized = this.restore();
  }
  key(type, id = '') { return `${this.scope}|${type}|${id}`; }
  report(error) { this.options.onError?.(error instanceof Error ? error.message : String(error)); }
  emitTasks() { this.options.onTasks?.([...this.tasks.values()].map(task => ({ ...task }))); }
  emitChat(message) { this.options.onChat?.({ ...message }); }
  async restore() {
    const messages = await this.store.list(this.key('chat'));
    for (const { value } of messages.sort((a, b) => a.value.timestamp - b.value.timestamp).slice(-1000)) { this.messages.set(value.id, value); this.emitChat(value); }
    const tasks = await this.store.list(this.key('task'));
    for (const { value } of tasks) {
      if (!TERMINAL.has(value.status)) value.status = 'interrupted';
      this.tasks.set(value.id, value);
    }
    this.emitTasks();
  }
  attach(control, files = null) {
    this.detach(); this.control = control; this.files = files; this.capable = false; this.disposed = false;
    this.initialized.then(() => {
      if (this.control !== control || control.readyState !== 'open') return;
      this.send('HELLO', { version: VERSION, blockSize: BLOCK_SIZE, maxMessageSize: this.maxMessageSize, durableChat: !this.store.memory, disk: this.io.disk });
    }).catch(error => this.report(error));
  }
  setFileChannel(channel) { this.files = channel; }
  send(type, payload = {}, lane = 'control') {
    const channel = lane === 'file' ? this.files || this.control : this.control;
    if (!channel || channel.readyState !== 'open') throw new Error('Peer disconnected. Reconnect to continue.');
    const value = JSON.stringify({ v: VERSION, type, ...payload });
    if (new TextEncoder().encode(value).length > 65536) throw new Error('Control message too large.');
    channel.send(value);
  }
  async ready() {
    await this.initialized;
    const deadline = Date.now() + (this.options.timeoutMs || 30000);
    while (!this.capable) {
      if (!this.control || this.control.readyState !== 'open' || this.disposed) throw new Error('Peer disconnected.');
      if (Date.now() > deadline) throw new Error('Peer does not support verified transfers v2. Update both clients.');
      await delay(20);
    }
  }
  receive(data) {
    let packet;
    if (typeof data === 'string') {
      if (data.length > 65536) { this.report('Control frame exceeds limit.'); return true; }
      try { packet = JSON.parse(data); } catch { return false; }
      if (packet?.v !== VERSION) return false;
    } else {
      if (!(data instanceof ArrayBuffer) || new Uint8Array(data)[0] !== 76 || new Uint8Array(data)[1] !== 68) return false;
      try { packet = { type: 'BINARY', ...decodeFrame(data) }; } catch (error) { this.report(error); return true; }
    }
    const size = typeof data === 'string' ? data.length * 2 : data.byteLength;
    this.queuedBytes += size;
    if (this.queuedBytes > 2 * BLOCK_SIZE + 131072) {
      this.queuedBytes -= size; this.report('Peer exceeded the receiver buffer window.'); this.detach(); return true;
    }
    // Control/chat are processed separately so a disk flush cannot block chat ACKs.
    const filePacket = ['BLOCK', 'BINARY', 'FINISH', 'CANCEL', 'PAUSE'].includes(packet.type);
    const operation = async () => {
      try { await this.initialized; await this.handle(packet); }
      catch (error) {
        if (validId(packet.id) && this.incoming.has(packet.id) && !TERMINAL.has(this.tasks.get(packet.id)?.status)) {
          const task = this.tasks.get(packet.id); task.status = 'failed'; task.error = error.message;
          await this.io.close(task, task.disk).catch(() => {}); this.incoming.delete(packet.id); this.activeIncoming = null;
          await this.persistTask(task).catch(() => {});
          try { this.send('ERROR', { id: packet.id, reason: error.message }); } catch { /* disconnected */ }
        }
        this.report(error);
      } finally { this.queuedBytes -= size; }
    };
    if (filePacket) this.inbound = this.inbound.then(operation);
    else if (['CHAT', 'CHAT_ACK'].includes(packet.type)) this.chatInbound = this.chatInbound.then(operation);
    else void operation();
    return true;
  }
  async handle(packet) {
    switch (packet.type) {
      case 'HELLO':
        if (packet.version !== VERSION || !Number.isSafeInteger(packet.maxMessageSize) || packet.maxMessageSize < 1024) throw new Error('Unsupported peer capabilities.');
        this.capable = true; this.remote = packet; this.options.onReady?.(packet);
        this.send('READY', { version: VERSION }); await this.flushOutbox(); void this.pump(); return;
      case 'READY':
        if (packet.version !== VERSION) throw new Error('Unsupported peer version.');
        this.capable = true; await this.flushOutbox(); void this.pump(); return;
      case 'CHAT': return this.receiveChat(packet);
      case 'CHAT_ACK': return this.ackChat(packet.id);
      case 'OFFER': return this.receiveOffer(packet);
      case 'ACCEPT': case 'PROGRESS': case 'COMPLETE': case 'PAUSE_ACK': case 'REJECT': case 'ERROR':
        return this.reply(packet);
      case 'BLOCK': return this.beginBlock(packet);
      case 'BINARY': return this.receiveBytes(packet);
      case 'FINISH': return this.finishIncoming(packet);
      case 'PAUSE': {
        const task = this.tasks.get(packet.id);
        if (task && !TERMINAL.has(task.status)) { task.status = 'paused'; this.rejectWaiters(task.id, new Error('Recipient paused the transfer.')); await this.persistTask(task); }
        this.send('PAUSE_ACK', { id: packet.id, offset: task?.offset || 0 });
        return;
      }
      case 'CANCEL': {
        const task = this.tasks.get(packet.id);
        if (task?.direction === 'sending' && !TERMINAL.has(task.status)) {
          task.status = 'cancelled'; this.rejectWaiters(task.id, new Error('Recipient cancelled the transfer.'));
          this.sources.delete(task.id); this.queue = this.queue.filter(id => id !== task.id); await this.persistTask(task);
        } else await this.cancelIncoming(packet.id);
        return;
      }
      default: throw new Error('Unsupported P2P v2 message.');
    }
  }
  async sendChat(text, replyToId) {
    await this.initialized;
    text = text.trim();
    if (!text || new TextEncoder().encode(text).length > 16000) throw new Error('Message must contain 1–16000 UTF-8 bytes.');
    const outbox = await this.store.list(this.key('outbox'));
    if (outbox.length >= 1000) throw new Error('Outbox is full. Retry or remove pending messages first.');
    const message = { id: uuid(), text, replyToId, senderName: this.options.localName || 'Me', timestamp: Date.now(), direction: 'sent', type: 'text', ack: false, pending: true, status: 'queued' };
    await this.store.batch([[this.key('chat', message.id), message], [this.key('outbox', message.id), message]]);
    this.messages.set(message.id, message); this.emitChat(message); await this.transmitChat(message); return message;
  }
  async transmitChat(message) {
    if (!this.capable || this.control?.readyState !== 'open' || this.disposed) return;
    try {
      this.send('CHAT', { id: message.id, text: message.text, replyToId: message.replyToId, timestamp: message.timestamp });
      message.status = 'sending'; message.pending = false; this.emitChat(message);
      clearTimeout(this.retryTimers.get(message.id));
      const attempt = (this.attempts.get(message.id) || 0) + 1; this.attempts.set(message.id, attempt);
      this.retryTimers.set(message.id, setTimeout(async () => {
        this.retryTimers.delete(message.id);
        if (this.disposed || message.ack) return;
        if (attempt >= 5) {
          message.status = 'failed'; message.pending = true; await this.store.put(this.key('chat', message.id), message).catch(error => this.report(error)); this.emitChat(message);
        } else void this.transmitChat(message);
      }, Math.min(15000, 1500 * 2 ** (attempt - 1))));
    } catch (error) { message.pending = true; message.status = 'queued'; this.emitChat(message); this.report(error); }
  }
  async flushOutbox() {
    for (const { value } of await this.store.list(this.key('outbox'))) {
      const message = this.messages.get(value.id) || value;
      if (!this.retryTimers.has(value.id)) await this.transmitChat(message);
    }
  }
  async retryChat(id) { this.attempts.delete(id); const message = await this.store.get(this.key('outbox', id)); if (message) await this.transmitChat(this.messages.get(id) || message); }
  async cancelChat(id) {
    clearTimeout(this.retryTimers.get(id)); this.retryTimers.delete(id);
    const message = this.messages.get(id);
    if (message && !message.ack) { message.status = 'cancelled'; message.pending = false; await this.store.batch([[this.key('outbox', id), undefined], [this.key('chat', id), message]]); this.emitChat(message); }
  }
  async receiveChat(packet) {
    if (!validId(packet.id) || typeof packet.text !== 'string' || !packet.text.trim() || new TextEncoder().encode(packet.text).length > 16000
        || !Number.isSafeInteger(packet.timestamp) || (packet.replyToId !== undefined && !validId(packet.replyToId))) throw new Error('Invalid chat packet.');
    const key = this.key('chat', packet.id), previous = await this.store.get(key);
    if (previous && (previous.text !== packet.text || previous.direction !== 'received')) throw new Error('Message ID conflicts with saved content.');
    if (!previous) {
      const message = { id: packet.id, text: packet.text, replyToId: packet.replyToId, timestamp: packet.timestamp, senderName: this.options.remoteName || 'Peer', direction: 'received', type: 'text', ack: true, status: 'delivered' };
      await this.store.put(key, message); this.messages.set(packet.id, message); this.emitChat(message);
    }
    this.send('CHAT_ACK', { id: packet.id });
  }
  async ackChat(id) {
    if (!validId(id)) throw new Error('Invalid chat acknowledgement.');
    const message = this.messages.get(id);
    if (!message || message.direction !== 'sent' || message.status === 'cancelled') return;
    const saved = { ...message, ack: true, pending: false, status: 'delivered' };
    await this.store.batch([[this.key('chat', id), saved], [this.key('outbox', id), undefined]]);
    Object.assign(message, saved);
    clearTimeout(this.retryTimers.get(id)); this.retryTimers.delete(id); this.attempts.delete(id); this.emitChat(message);
  }
  async clearChat() {
    for (const timer of this.retryTimers.values()) clearTimeout(timer); this.retryTimers.clear();
    const records = [...await this.store.list(this.key('chat')), ...await this.store.list(this.key('outbox'))];
    await this.store.batch(records.map(record => [record.key, undefined])); this.messages.clear();
  }
  async persistTask(task) {
    task.updatedAt = Date.now(); const saved = { ...task }; delete saved.blobUrl;
    await this.store.put(this.key('task', task.id), saved); this.emitTasks();
  }
  async enqueueFile(file, resumeId) {
    await this.initialized;
    if (this.queue.length >= 20) throw new Error('File queue is full (20 files).');
    if (!file) throw new Error('Select the original source file to resume.');
    const existing = resumeId ? this.tasks.get(resumeId) : null;
    if (resumeId && (!existing || existing.direction !== 'sending' || TERMINAL.has(existing.status))) throw new Error('Resume task not found.');
    if (resumeId && this.sources.has(resumeId) && BUSY.has(existing.status)) throw new Error('This file is already running.');
    const id = resumeId || uuid();
    const task = { ...existing, id, name: file.name, size: file.size, mime: file.type || 'application/octet-stream', direction: 'sending', status: 'queued', offset: existing?.offset || 0, createdAt: existing?.createdAt || Date.now() };
    if (!Number.isSafeInteger(file.size) || file.size < 0 || file.size > MAX_FILE_SIZE || !file.name.trim() || file.name.length > 255 || /[\x00-\x1f/\\]/.test(file.name)) throw new Error('Invalid source file.');
    this.tasks.set(id, task); this.sources.set(id, file); if (!this.queue.includes(id)) this.queue.push(id);
    await this.persistTask(task); void this.pump(); return id;
  }
  async pump() {
    if (this.running || !this.capable || this.disposed || this.control?.readyState !== 'open') return;
    this.running = true;
    try {
      while (this.queue.length && !this.disposed && this.control?.readyState === 'open') {
        const id = this.queue.shift(), task = this.tasks.get(id);
        if (!task || task.status === 'cancelled') continue;
        try { await this.sendFile(task, this.sources.get(id)); }
        catch (error) {
          if (task.status === 'paused' || task.status === 'cancelled') continue;
          if (!TERMINAL.has(task.status) && task.status !== 'paused') task.status = this.control?.readyState === 'open' ? 'failed' : 'interrupted';
          task.error = error.message; await this.persistTask(task).catch(failure => this.report(failure)); this.report(error);
        }
      }
    } finally { this.running = false; }
  }
  waitReply(id, type, timeout = this.options.timeoutMs || 30000) {
    const key = `${id}:${type}`;
    if (this.waiters.has(key)) throw new Error('Duplicate pending transfer operation.');
    const result = new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.waiters.delete(key); reject(new Error('Peer acknowledgement timed out. Retry to resume.')); }, timeout);
      this.waiters.set(key, { resolve, reject, timer });
    });
    // A transport can fail during the send loop, before its ACK is awaited.
    void result.catch(() => {});
    return result;
  }
  rejectWaiters(id, error) {
    for (const [key, waiter] of this.waiters) if (key.startsWith(id + ':')) { clearTimeout(waiter.timer); waiter.reject(error); this.waiters.delete(key); }
  }
  reply(packet) {
    if (!validId(packet.id)) throw new Error('Invalid transfer acknowledgement.');
    const task = this.tasks.get(packet.id);
    if (!task || task.direction !== 'sending') return;
    if (['ERROR', 'REJECT'].includes(packet.type)) {
      if (packet.type === 'REJECT') task.status = 'rejected';
      this.rejectWaiters(packet.id, new Error(String(packet.reason || 'File rejected.').slice(0, 500))); return;
    }
    const key = `${packet.id}:${packet.type}`, waiter = this.waiters.get(key);
    if (!waiter) return;
    clearTimeout(waiter.timer); this.waiters.delete(key); waiter.resolve(packet);
  }
  async drain(channel) {
    const deadline = Date.now() + 30000;
    while (channel.bufferedAmount > 262144) {
      if (channel.readyState !== 'open' || this.disposed) throw new Error('File channel disconnected.');
      if (Date.now() > deadline) throw new Error('File channel stalled.');
      await delay(5);
    }
    if (channel.readyState !== 'open' || this.disposed) throw new Error('File channel disconnected.');
  }
  async sendFile(task, file) {
    await this.ready();
    if (!file) throw new Error('Select the original source file to resume.');
    task.status = 'preparing'; task.error = ''; await this.persistTask(task);
    const hash = await this.io.hash(file);
    if (TERMINAL.has(task.status) || task.status === 'paused' || this.disposed) return;
    if (task.hash && task.hash !== hash) throw new Error('Source file changed. Resume requires identical content.');
    task.hash = hash; validateOffer(task); task.status = 'waiting'; await this.persistTask(task);
    const acceptance = this.waitReply(task.id, 'ACCEPT', this.options.offerTimeoutMs || 120000);
    this.send('OFFER', { id: task.id, name: task.name, size: task.size, mime: task.mime, hash });
    const reply = await acceptance;
    if (!Number.isSafeInteger(reply.offset) || reply.offset < 0 || reply.offset > task.size || (reply.offset !== task.size && reply.offset % BLOCK_SIZE !== 0)) throw new Error('Invalid resume checkpoint.');
    task.offset = reply.offset; task.status = 'transferring'; task.startedAt = Date.now();
    const initialOffset = task.offset; await this.persistTask(task);
    // The second channel may still be opening when control capability exchange finishes.
    const fileDeadline = Date.now() + (this.options.timeoutMs || 30000);
    while (this.files && this.files.readyState === 'connecting') {
      if (Date.now() > fileDeadline || this.control?.readyState !== 'open') throw new Error('File channel did not open.');
      await delay(10);
    }
    const channel = this.files || this.control;
    for (; task.offset < task.size;) {
      if (task.status === 'paused' || TERMINAL.has(task.status)) return;
      if (this.disposed || this.control?.readyState !== 'open') throw new Error('Peer disconnected.');
      const offset = task.offset;
      const bytes = new Uint8Array(await file.slice(offset, offset + BLOCK_SIZE).arrayBuffer());
      const blockHash = hashBytes(bytes);
      const acknowledgement = this.waitReply(task.id, 'PROGRESS');
      // BLOCK and its binary frames share the same ordered channel.
      this.send('BLOCK', { id: task.id, offset, size: bytes.length, hash: blockHash }, 'file');
      const framePayload = Math.min(FRAME_SIZE, this.maxMessageSize - 32, (this.remote?.maxMessageSize || 65536) - 32);
      for (let index = 0; index < bytes.length; index += framePayload) {
        await this.drain(channel); channel.send(encodeFrame(task.id, offset + index, bytes.subarray(index, index + framePayload)));
      }
      const result = await acknowledgement;
      if (result.offset !== offset + bytes.length || result.hash !== blockHash) throw new Error('Invalid block acknowledgement.');
      task.offset = result.offset;
      task.speed = Math.round((task.offset - initialOffset) / Math.max(0.1, (Date.now() - task.startedAt) / 1000));
      await this.persistTask(task);
    }
    if (task.status === 'paused' || TERMINAL.has(task.status)) return;
    task.status = 'verifying'; await this.persistTask(task);
    // Duplicate FINISH replays the saved completion receipt, including after reconnect.
    for (let attempt = 0; attempt < 3; attempt++) {
      const completed = this.waitReply(task.id, 'COMPLETE', this.options.finishTimeoutMs || 120000);
      this.send('FINISH', { id: task.id, hash, size: task.size }, 'file');
      try {
        const result = await completed;
        if (result.hash !== hash || result.size !== task.size) throw new Error('Invalid completion receipt.');
        task.status = 'completed'; task.verified = true; task.offset = task.size; task.savedTo = result.savedTo;
        await this.persistTask(task); this.sources.delete(task.id); return;
      } catch (error) { if (attempt === 2 || this.disposed || this.control?.readyState !== 'open' || task.status === 'cancelled') throw error; }
    }
  }
  async receiveOffer(packet) {
    validateOffer(packet);
    let task = this.tasks.get(packet.id);
    if (task && (task.hash !== packet.hash || task.size !== packet.size || task.direction !== 'receiving')) throw new Error('Transfer ID conflicts with saved content.');
    if (task?.status === 'completed') { this.send('ACCEPT', { id: packet.id, offset: task.size }); return; }
    if (task?.status === 'cancelled') { this.send('REJECT', { id: packet.id, reason: 'This transfer was cancelled.' }); return; }
    if (this.incoming.has(packet.id)) { this.send('ACCEPT', { id: packet.id, offset: task.offset }); return; }
    if ([...this.tasks.values()].filter(row => row.direction === 'receiving' && !TERMINAL.has(row.status) && row.status !== 'interrupted' && row.status !== 'failed').length >= 8) {
      this.send('REJECT', { id: packet.id, reason: 'Incoming queue is full.' }); return;
    }
    task = { ...task, id: packet.id, name: packet.name, size: packet.size, mime: packet.mime, hash: packet.hash, direction: 'receiving', status: 'offered', offset: task?.disk ? task.offset : 0, createdAt: task?.createdAt || Date.now() };
    this.tasks.set(task.id, task); await this.persistTask(task); this.options.onOffer?.({ ...task });
    if (this.options.autoAccept) await this.acceptFile(task.id);
  }
  async acceptFile(id, destination) {
    await this.initialized;
    const task = this.tasks.get(id);
    if (!task || task.direction !== 'receiving' || !['offered', 'paused', 'interrupted', 'failed'].includes(task.status)) throw new Error('Incoming file is not waiting for acceptance.');
    if (this.activeIncoming && this.activeIncoming !== id) throw new Error('Another incoming file is still running.');
    if (!this.io.disk && task.size > MEMORY_LIMIT) throw new Error('Use HTTPS and a browser with disk storage for files larger than 64 MiB.');
    if (this.io.disk && globalThis.navigator?.storage?.estimate) {
      const { quota, usage } = await navigator.storage.estimate();
      if (quota && quota - (usage || 0) < task.size - task.offset + BLOCK_SIZE) throw new Error('Insufficient browser disk quota. Free storage or choose a smaller file.');
    }
    task.disk = this.io.disk;
    try { await this.io.open(task, task.offset, task.disk); }
    catch (error) {
      // An evicted/missing checkpoint restarts at zero, never advertises nonexistent bytes.
      if (task.offset) { task.offset = 0; await this.io.open(task, 0, task.disk); } else throw error;
    }
    this.destinations.set(id, destination); this.incoming.set(id, { task, block: null }); this.activeIncoming = id;
    task.status = 'transferring'; task.startedAt = Date.now(); await this.persistTask(task);
    this.send('ACCEPT', { id, offset: task.offset });
  }
  async rejectFile(id) {
    const task = this.tasks.get(id); if (!task || task.direction !== 'receiving') return;
    task.status = 'rejected'; await this.persistTask(task); this.send('REJECT', { id, reason: 'Declined by recipient.' });
  }
  beginBlock(packet) {
    const state = this.incoming.get(packet.id);
    if (!state || state.block || packet.offset !== state.task.offset || !Number.isSafeInteger(packet.size)
        || packet.size <= 0 || packet.size > BLOCK_SIZE || packet.offset + packet.size > state.task.size || !validHash(packet.hash)) throw new Error('Invalid block or receive window.');
    if (packet.size !== Math.min(BLOCK_SIZE, state.task.size - packet.offset)) throw new Error('Invalid block boundary.');
    state.block = { offset: packet.offset, hash: packet.hash, bytes: new Uint8Array(packet.size), received: 0 };
  }
  async receiveBytes(packet) {
    const state = this.incoming.get(packet.id), block = state?.block;
    if (!block || !packet.bytes.length || packet.offset !== block.offset + block.received || block.received + packet.bytes.length > block.bytes.length) throw new Error('Unexpected file offset.');
    block.bytes.set(packet.bytes, block.received); block.received += packet.bytes.length;
    if (block.received !== block.bytes.length) return;
    const end = await this.io.write(state.task, block.offset, block.bytes, block.hash, state.task.disk);
    state.task.offset = end; state.task.speed = Math.round(end / Math.max(0.1, (Date.now() - state.task.startedAt) / 1000));
    state.block = null; await this.persistTask(state.task);
    this.send('PROGRESS', { id: packet.id, offset: end, hash: block.hash });
  }
  async finishIncoming(packet) {
    const task = this.tasks.get(packet.id);
    if (task?.direction !== 'receiving' || packet.hash !== task.hash || packet.size !== task.size) throw new Error('Unexpected file completion.');
    if (task.status === 'completed') { this.send('COMPLETE', { id: task.id, size: task.size, hash: task.hash, savedTo: task.savedTo }); return; }
    const state = this.incoming.get(packet.id);
    if (!state || state.block || task.offset !== task.size) throw new Error('File is incomplete.');
    task.status = 'verifying'; await this.persistTask(task);
    const result = await this.io.finish(task, task.disk, this.destinations.get(task.id));
    if (task.status === 'cancelled' || this.disposed) return;
    task.status = 'completed'; task.verified = true; task.savedTo = result.savedTo;
    await this.persistTask(task); this.incoming.delete(task.id); this.activeIncoming = null;
    this.options.onReceived?.({ ...task, ...result });
    this.send('COMPLETE', { id: task.id, size: task.size, hash: result.hash, savedTo: result.savedTo });
  }
  async pause(id) {
    const task = this.tasks.get(id); if (!task || !BUSY.has(task.status)) return;
    task.status = 'paused'; await this.persistTask(task);
    this.rejectWaiters(id, new Error('Transfer paused.'));
    try {
      const receipt = task.direction === 'sending' ? this.waitReply(id, 'PAUSE_ACK') : null;
      this.send('PAUSE', { id }, 'file');
      if (receipt) await receipt;
    } catch { /* keep checkpoint; reconnect/re-offer reconciles disk state */ }
  }
  async resume(id, file) {
    const task = this.tasks.get(id); if (!task || !['paused', 'interrupted', 'failed'].includes(task.status)) throw new Error('Task cannot be resumed in this state.');
    if (task.direction === 'receiving') throw new Error('Ask the sender to resume, then accept the new offer.');
    if (this.running && this.sources.has(id)) throw new Error('The current block is still being saved. Try again shortly.');
    return this.enqueueFile(file || this.sources.get(id), id);
  }
  async cancelIncoming(id) {
    const task = this.tasks.get(id);
    if (!task || task.direction !== 'receiving' || TERMINAL.has(task.status)) return;
    task.status = 'cancelled'; await this.io.close(task, task.disk, true); await this.persistTask(task);
    this.incoming.delete(id); this.destinations.delete(id); if (this.activeIncoming === id) this.activeIncoming = null;
  }
  async cancel(id) {
    const task = this.tasks.get(id); if (!task || TERMINAL.has(task.status)) return;
    if (task.direction === 'receiving') await this.cancelIncoming(id);
    else { task.status = 'cancelled'; this.rejectWaiters(id, new Error('Transfer cancelled.')); this.sources.delete(id); this.queue = this.queue.filter(value => value !== id); await this.persistTask(task); }
    try { this.send('CANCEL', { id }, 'file'); } catch { /* local cancellation persists */ }
  }
  async removeTask(id) {
    const task = this.tasks.get(id); if (!task || BUSY.has(task.status)) throw new Error('Stop this transfer before removing it.');
    if (task.direction === 'receiving') await this.io.close(task, task.disk, true);
    await this.store.remove(this.key('task', id)); this.tasks.delete(id); this.emitTasks();
  }
  async savedFile(id) {
    await this.initialized;
    const task = this.tasks.get(id);
    if (!task || task.direction !== 'receiving' || !task.verified || task.status !== 'completed') throw new Error('Verified received file not found.');
    if (task.disk) return this.io.finish(task, true);
    const blobUrl = this.io.urls.get(id);
    if (!blobUrl) throw new Error('This temporary file expired after reload. Ask the sender to send it again.');
    return { blobUrl, hash: task.hash, savedTo: task.savedTo };
  }
  detach() {
    this.capable = false; this.control = null; this.files = null;
    for (const timer of this.retryTimers.values()) clearTimeout(timer); this.retryTimers.clear(); this.attempts.clear();
    for (const [key, waiter] of this.waiters) { clearTimeout(waiter.timer); waiter.reject(new Error('Peer disconnected.')); this.waiters.delete(key); }
    for (const task of this.tasks.values()) if (BUSY.has(task.status)) { task.status = 'interrupted'; void this.persistTask(task).catch(error => this.report(error)); }
    for (const { task } of this.incoming.values()) void this.io.close(task, task.disk).catch(error => this.report(error));
    this.incoming.clear(); this.activeIncoming = null;
  }
  dispose() { this.detach(); this.disposed = true; this.io.dispose(); }
}
