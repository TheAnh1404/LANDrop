import { WebRTCPeerService } from '../webrtc/webrtc-peer-service';
import type { RealtimeClient } from '../realtime/realtime-client';
import type { RealtimeEnvelope } from '../realtime/types';
import type { OnlinePeerItem } from '../webrtc/types';
import { uuid } from './engine';
export * from './engine';

interface BrowserOptions {
  userId: string; name: string; serverUrl: string;
  send: (packet: unknown) => boolean; connectionId: () => string;
  onChat: (peer: OnlinePeerItem, message: unknown, workspace: string) => void;
  onTasks: (tasks: unknown[]) => void;
  onState: (peer: OnlinePeerItem, service: WebRTCPeerService, workspace: string) => void;
  onReceived: (peer: OnlinePeerItem, file: unknown, workspace: string) => void;
  onError: (message: string) => void;
}
interface BrowserEntry { peer: OnlinePeerItem; workspace: string; service: WebRTCPeerService; unsubs: (() => void)[]; stopped: boolean; attempts: number; lastAttempt: number; connecting?: Promise<void>; }
export class BrowserPeerManager {
  private listeners = new Set<(packet: RealtimeEnvelope) => void>();
  private entries = new Map<string, BrowserEntry>();
  private client: RealtimeClient;
  private current: BrowserEntry | null = null;
  private peers: OnlinePeerItem[] = [];
  constructor(private readonly options: BrowserOptions) {
    this.client = { onEnvelope: (listener: (packet: RealtimeEnvelope) => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener); }, sendEnvelope: options.send, getConnectionId: options.connectionId } as unknown as RealtimeClient;
  }
  private entry(peer: OnlinePeerItem, workspace: string): BrowserEntry {
    if (!peer.userId) throw new Error('Peer identity is unavailable. Refresh the device list.');
    const key = JSON.stringify([workspace, peer.userId, peer.deviceId || peer.userId]);
    const previous = this.entries.get(key);
    if (previous) { previous.peer = peer; return previous; }
    if (this.entries.size >= 2) {
      const inactive = [...this.entries.entries()].find(([, item]) => ['DISCONNECTED', 'FAILED', 'CANCELLED', 'REJECTED', 'TIMEOUT'].includes(item.service.getState()));
      if (!inactive) throw new Error('Two peer connections are active. Disconnect one before opening a third.');
      inactive[1].unsubs.forEach(unsubscribe => unsubscribe()); inactive[1].service.destroy(); this.entries.delete(inactive[0]);
    }
    const service = new WebRTCPeerService(this.client, { managed: true, localUserId: this.options.userId, localName: this.options.name, serverUrl: this.options.serverUrl });
    const entry: BrowserEntry = { peer, workspace, service, unsubs: [], stopped: false, attempts: 0, lastAttempt: 0 };
    this.entries.set(key, entry);
    entry.unsubs = [
      service.onChatMessage(message => this.options.onChat(entry.peer, message, entry.workspace)),
      service.onTasks(() => this.options.onTasks([...this.entries.values()].flatMap(item => item.service.getTasks().map(task => ({ ...task, peerName: item.peer.displayName }))))),
      service.onStateChange(state => {
        if (state === 'CONNECTED') entry.attempts = 0;
        this.options.onState(entry.peer, service, entry.workspace);
        if (state === 'DISCONNECTED' && !entry.stopped) this.options.send({ type: 'peer.list', requestId: `recover-${uuid()}` });
      }),
      service.onDiagnostics(diagnostics => { if (diagnostics.lastError) this.options.onError(diagnostics.lastError); }),
      service.onFileReceived(file => this.options.onReceived(entry.peer, file, entry.workspace)),
    ];
    return entry;
  }
  async select(peer: OnlinePeerItem, workspace: string) {
    const entry = this.entry(peer, workspace); this.current = entry; entry.stopped = false;
    this.options.onState(peer, entry.service, entry.workspace);
    if (entry.connecting) { await entry.connecting; return; }
    if (!['CONNECTED', 'REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(entry.service.getState())) {
      const connecting = entry.service.requestConnection({ workspaceId: workspace, receiverConnectionId: peer.connectionId, targetUserId: peer.userId || undefined, targetDeviceId: peer.deviceId || undefined, targetDeviceName: peer.deviceName || peer.displayName, fileName: 'p2p-chat-session', fileSize: 0 });
      entry.connecting = connecting;
      try { await connecting; } finally { if (entry.connecting === connecting) entry.connecting = undefined; }
    }
  }
  handle(packet: RealtimeEnvelope): boolean {
    const payload = packet.payload as Record<string, any> || {};
    if (packet.type === 'peer.list' || packet.type === 'peers.list') {
      this.peers = payload.peers || [];
      for (const entry of this.entries.values()) {
        if (entry.stopped || entry.attempts >= 5 || !['DISCONNECTED', 'TIMEOUT', 'CANCELLED'].includes(entry.service.getState())) continue;
        const peer = this.peers.find(item => item.userId === entry.peer.userId && item.deviceId === entry.peer.deviceId);
        if (!peer || Date.now() - entry.lastAttempt < Math.min(30000, 1000 * 2 ** entry.attempts)) continue;
        entry.lastAttempt = Date.now(); entry.attempts++;
        entry.peer = peer;
        void entry.service.requestConnection({ workspaceId: entry.workspace, receiverConnectionId: peer.connectionId, targetUserId: peer.userId || undefined, targetDeviceId: peer.deviceId || undefined, targetDeviceName: peer.deviceName || peer.displayName, fileName: 'p2p-chat-session', fileSize: 0 }).catch(error => this.options.onError(error.message));
      }
    }
    if (packet.type === 'transfer.request') {
      const peer = this.peers.find(item => item.connectionId === payload.senderConnectionId) || { connectionId: payload.senderConnectionId, userId: payload.senderUserId, deviceId: payload.senderDeviceId, displayName: payload.senderDeviceName || 'Peer', deviceName: payload.senderDeviceName || 'Peer', platform: 'browser' };
      try {
        const entry = this.entry(peer, payload.workspaceId);
        const state = entry.service.getState();
        if (state === 'CONNECTED' || (['REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(state) && this.options.connectionId() < peer.connectionId)) {
          this.options.send({ type: 'transfer.reject', requestId: `glare-${payload.transferId}-${uuid()}`, payload: { transferId: payload.transferId, reason: 'Use the existing peer session.' } }); return true;
        }
        if (['REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(state)) entry.service.cancelConnection('Resolve simultaneous connection requests');
        entry.service.receiveIncomingRequest(payload);
        if (!this.current) this.current = entry;
        void entry.service.acceptIncomingRequest(payload.transferId).catch(error => this.options.onError(error.message));
      } catch (error) { this.options.send({ type: 'transfer.reject', payload: { transferId: payload.transferId, reason: (error as Error).message } }); }
      return true;
    }
    this.listeners.forEach(listener => listener(packet));
    return packet.type.startsWith('transfer.') || packet.type.startsWith('webrtc.');
  }
  getService() { return this.current?.service || null; }
  async sendChat(text: string, peer?: OnlinePeerItem, workspace?: string) {
    const entry = peer && workspace ? this.entry(peer, workspace) : this.current;
    if (!entry) throw new Error('Select a peer first.');
    if (peer && workspace) await this.select(peer, workspace);
    return entry.service.sendChatMessage(text);
  }
  async sendFile(file: File, peer?: OnlinePeerItem, workspace?: string) {
    const entry = peer && workspace ? this.entry(peer, workspace) : this.current;
    if (!entry) throw new Error('Select a peer first.');
    if (peer && workspace) await this.select(peer, workspace);
    await entry.service.sendFile(file);
  }
  task(id: string) { const entry = [...this.entries.values()].find(item => item.service.getTasks().some(task => task.id === id)); const core = entry?.service.getReliable(); if (!core) throw new Error('Transfer not found.'); return core; }
  suspend() { for (const entry of this.entries.values()) entry.service.suspend(); }
  disconnect() { if (this.current) { this.current.stopped = true; this.current.service.cancelConnection(); } }
  dispose() { for (const entry of this.entries.values()) { entry.unsubs.forEach(unsubscribe => unsubscribe()); entry.service.destroy(); } this.entries.clear(); this.current = null; }
}
