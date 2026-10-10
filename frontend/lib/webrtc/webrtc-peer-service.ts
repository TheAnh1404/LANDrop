/**
 * WebRTC Peer Service for LANDrop REALTIME-02B.
 *
 * Responsibilities:
 * - RTCPeerConnection and RTCDataChannel lifecycles.
 * - Single RealtimeClient envelope routing (strictly no secondary WebSocket).
 * - Exact backend protocol event adherence.
 * - Early ICE candidate queueing prior to remote description.
 * - Technical Ping/Pong round-trip data channel verification.
 * - Real P2P Binary File Transfer over RTCDataChannel with backpressure control.
 * - Quick Text / Clipboard Drop messaging.
 * - Realtime Peer Discovery (peer.list).
 * - Complete teardown and memory cleanup.
 */

import { RealtimeClient } from '../realtime/realtime-client';
import type { RealtimeEnvelope } from '../realtime/types';
import { getRtcConfiguration, WebRTCConfigurationOptions } from './config';
import { ReliablePeer, P2PTask, uuid } from '../p2p/engine';
import type {
  ChatAckMessage,
  ChatDataMessage,
  ChatTypingMessage,
  FileCancelMessage,
  FileEndMessage,
  FileStartMessage,
  FileTransferProgress,
  IncomingTransferRequest,
  OnlinePeerItem,
  P2PChatMessage,
  PeerTestResult,
  QuickTextDataMessage,
  QuickTextMessage,
  ReceivedFileItem,
  TechnicalDataMessage,
  WebRTCDiagnostics,
  WebRTCPeerState,
  WebRTCRole,
} from './types';

export type WebRTCStateListener = (state: WebRTCPeerState) => void;
export type DiagnosticsListener = (diagnostics: WebRTCDiagnostics) => void;
export type IncomingRequestListener = (request: IncomingTransferRequest | null) => void;
export type TestResultListener = (result: PeerTestResult) => void;
export type TransferProgressListener = (progress: FileTransferProgress | null) => void;
export type FileReceivedListener = (file: ReceivedFileItem) => void;
export type QuickTextListener = (msg: QuickTextMessage) => void;
export type OnlinePeersListener = (peers: OnlinePeerItem[]) => void;
export type ChatMessageListener = (msg: P2PChatMessage) => void;
export type ChatTypingListener = (typing: { isTyping: boolean; senderName: string }) => void;

const SIGNALING_TIMEOUT_MS = 30_000;
const PING_TIMEOUT_MS = 10_000;
const PING_INTERVAL_MS = 10_000; // Continuous keep-alive heartbeat interval
const CHUNK_SIZE = 65_536; // 64 KB optimal binary chunks
const BUFFER_HIGH_WATER_MARK = 1_048_576; // 1 MB high-water backpressure threshold
const BUFFERED_AMOUNT_LOW_THRESHOLD = 262_144; // 256 KB low-water drain threshold

export class WebRTCPeerService {
  private reliable: ReliablePeer | null = null;
  private reliableScope = '';
  private fileChannel: RTCDataChannel | null = null;
  private chatChannel: RTCDataChannel | null = null;
  private chatReceiveRttMs?: number;
  private chatDurableRttMs?: number;
  private resourceEpoch = 0;
  private targetDeviceId: string | null = null;
  private generation = 0;
  private coordinatedConnected = false;
  private leaseDeadline = 0;
  private iceRecoveryTimer: ReturnType<typeof setTimeout> | null = null;
  private iceRestartPending = false;
  private readonly taskListeners = new Set<(tasks: P2PTask[]) => void>();
  private tasks: P2PTask[] = [];

  public onTasks(listener: (tasks: P2PTask[]) => void): () => void { this.taskListeners.add(listener); listener(this.tasks); return () => this.taskListeners.delete(listener); }
  public getTasks(): P2PTask[] { return this.tasks; }
  public clearQuickMessages(): void { this.quickMessages = []; }
  private updateLease(expiresAt: unknown, serverTimestamp?: string): void {
    if (typeof expiresAt !== 'string') return;
    const deadline = Date.parse(expiresAt), serverTime = serverTimestamp ? Date.parse(serverTimestamp) : NaN;
    if (Number.isFinite(deadline)) this.leaseDeadline = Number.isFinite(serverTime) ? Date.now() + Math.max(0, deadline - serverTime) : deadline;
  }
  public getReliable(): ReliablePeer | null { return this.reliable; }
  public getDataChannel(): RTCDataChannel | null { return this.dataChannel; }
  public getPeerConnection(): RTCPeerConnection | null { return this.pc; }
  public receiveIncomingRequest(payload: Record<string, any>): void { this.handleTransferRequestReceived(payload); }
  public suspend(): void { this.setState('DISCONNECTED'); this.cleanupPeerResources(); }

  private ensureReliable(): ReliablePeer | null {
    if (!this.configOptions?.localUserId || !this.targetUserId || !this.workspaceId) return null;
    const scope = JSON.stringify([this.configOptions.serverUrl, this.configOptions.localUserId, this.workspaceId, this.targetUserId, this.targetDeviceId || this.targetUserId]);
    if (scope === this.reliableScope && this.reliable) return this.reliable;
    this.reliable?.dispose(); this.reliableScope = scope; this.chatMessages = []; this.tasks = [];
    this.reliable = new ReliablePeer({
      scope, localName: this.configOptions.localName, remoteName: this.targetDeviceName || 'Peer',
      maxMessageSize: this.pc?.sctp?.maxMessageSize || 65536,
      onChat: message => { if (message.direction === 'received') this.handleChatTyping({ type: 'CHAT_TYPING', isTyping: false }); const index = this.chatMessages.findIndex(item => item.id === message.id); if (index < 0) this.chatMessages.push(message); else this.chatMessages[index] = message; this.emitChatMessage(message); },
      onTasks: tasks => { this.tasks = tasks; this.receivedFiles = this.receivedFiles.filter(file => tasks.some(task => task.id === file.id)); this.taskListeners.forEach(listener => listener(tasks)); },
      onError: message => { this.lastError = message; this.emitDiagnostics(); },
      onChatTiming: timing => { if (timing.stage === 'received') this.chatReceiveRttMs = timing.roundTripMs; else this.chatDurableRttMs = timing.roundTripMs; this.emitDiagnostics(); },
      onReceived: task => {
        const item: ReceivedFileItem = { id: task.id, fileName: task.name, fileSize: task.size, mimeType: task.mime, blobUrl: task.blobUrl, receivedAt: Date.now(), senderName: this.targetDeviceName || 'Peer' };
        this.receivedFiles = [item, ...this.receivedFiles]; this.emitFileReceived(item);
      },
    });
    return this.reliable;
  }
  private pc: RTCPeerConnection | null = null;
  private dataChannel: RTCDataChannel | null = null;
  private state: WebRTCPeerState = 'IDLE';
  private role: WebRTCRole | null = null;

  private transferId: string | null = null;
  private workspaceId: string | null = null;
  private targetUserId: string | null = null;
  private targetConnectionId: string | null = null;
  private targetDeviceName: string | null = null;

  private earlyCandidatesQueue: RTCIceCandidateInit[] = [];
  private incomingRequest: IncomingTransferRequest | null = null;
  private testResult: PeerTestResult | null = null;

  private signalingTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
  private pingIntervalTimer: ReturnType<typeof setInterval> | null = null;
  private chatTypingTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly unackedRetries = new Map<string, { packet: ChatDataMessage; retries: number; timer: ReturnType<typeof setTimeout> }>();
  private pendingChatQueue: { id: string; text: string; replyToId?: string; timestamp: number }[] = [];

  private lastError: string | null = null;
  private lastUpdated: number = Date.now();

  // Listeners
  private readonly stateListeners = new Set<WebRTCStateListener>();
  private readonly diagnosticsListeners = new Set<DiagnosticsListener>();
  private readonly incomingRequestListeners = new Set<IncomingRequestListener>();
  private readonly testResultListeners = new Set<TestResultListener>();
  private readonly progressListeners = new Set<TransferProgressListener>();
  private readonly fileReceivedListeners = new Set<FileReceivedListener>();
  private readonly quickTextListeners = new Set<QuickTextListener>();
  private readonly onlinePeersListeners = new Set<OnlinePeersListener>();
  private readonly chatMessageListeners = new Set<ChatMessageListener>();
  private readonly chatTypingListeners = new Set<ChatTypingListener>();

  // Transfer & Presence State
  private currentTransferProgress: FileTransferProgress | null = null;
  private receivedFiles: ReceivedFileItem[] = [];
  private quickMessages: QuickTextMessage[] = [];
  private chatMessages: P2PChatMessage[] = [];
  private sendingFile = false;
  private onlinePeers: OnlinePeerItem[] = [];
  private incomingFileState: {
    transferId: string;
    fileId: string;
    fileName: string;
    fileSize: number;
    mimeType: string;
    totalChunks: number;
    chunks: (ArrayBuffer | Blob)[];
    receivedBytes: number;
    startTime: number;
    lastProgressEmitTime: number;
  } | null = null;

  private unsubscribeEnvelope: (() => void) | null = null;
  private readonly configOptions?: WebRTCConfigurationOptions;

  constructor(
    private readonly realtimeClient: RealtimeClient,
    configOptions?: WebRTCConfigurationOptions
  ) {
    this.configOptions = configOptions;
    this.bindRealtimeSignaling();
  }

  // =========================================================================
  // 1. Lifecycle and Subscriptions
  // =========================================================================

  private bindRealtimeSignaling() {
    this.unsubscribeEnvelope = this.realtimeClient.onEnvelope(envelope => {
      this.handleIncomingEnvelope(envelope);
    });
  }

  public destroy() {
    if (this.unsubscribeEnvelope) {
      this.unsubscribeEnvelope();
      this.unsubscribeEnvelope = null;
    }
    this.cleanupPeerResources();
    this.reliable?.dispose(); this.reliable = null; this.taskListeners.clear();
    this.stateListeners.clear();
    this.diagnosticsListeners.clear();
    this.incomingRequestListeners.clear();
    this.testResultListeners.clear();
    this.progressListeners.clear();
    this.fileReceivedListeners.clear();
    this.quickTextListeners.clear();
    this.onlinePeersListeners.clear();
    this.chatMessageListeners.clear();
    this.chatTypingListeners.clear();
    this.chatMessages = [];
    this.receivedFiles.forEach(f => {
      try { URL.revokeObjectURL(f.blobUrl); } catch { /* ignore */ }
    });
  }

  public getState(): WebRTCPeerState {
    return this.state;
  }

  public getRole(): WebRTCRole | null {
    return this.role;
  }

  public getIncomingRequest(): IncomingTransferRequest | null {
    return this.incomingRequest;
  }

  public getTestResult(): PeerTestResult | null {
    return this.testResult;
  }

  public getTransferProgress(): FileTransferProgress | null {
    return this.currentTransferProgress;
  }

  public getReceivedFiles(): ReceivedFileItem[] {
    return this.receivedFiles;
  }

  public getQuickMessages(): QuickTextMessage[] {
    return this.quickMessages;
  }

  public getOnlinePeers(): OnlinePeerItem[] {
    return this.onlinePeers;
  }

  public getDiagnostics(): WebRTCDiagnostics {
    return {
      transferId: this.transferId,
      role: this.role,
      targetUserId: this.targetUserId,
      targetConnectionId: this.targetConnectionId,
      targetDeviceName: this.targetDeviceName,
      signalingState: this.state,
      rtcSignalingState: this.pc?.signalingState ?? 'none',
      iceConnectionState: this.pc?.iceConnectionState ?? 'none',
      peerConnectionState: this.pc?.connectionState ?? 'none',
      dataChannelState: this.dataChannel?.readyState ?? 'none',
      roundTripLatencyMs: this.testResult?.roundTripMs ?? null,
      chatReceiveRttMs: this.chatReceiveRttMs,
      chatDurableRttMs: this.chatDurableRttMs,
      lastError: this.lastError,
      lastUpdated: this.lastUpdated,
    };
  }

  private setState(next: WebRTCPeerState, error?: string | null) {
    if (error !== undefined) {
      this.lastError = error;
    }
    this.state = next;
    this.lastUpdated = Date.now();
    this.emitState();
    this.emitDiagnostics();
  }

  private emitState() {
    this.stateListeners.forEach(fn => {
      try { fn(this.state); } catch { /* ignore listener error */ }
    });
  }

  private emitDiagnostics() {
    const diag = this.getDiagnostics();
    this.diagnosticsListeners.forEach(fn => {
      try { fn(diag); } catch { /* ignore listener error */ }
    });
  }

  private emitIncomingRequest() {
    this.incomingRequestListeners.forEach(fn => {
      try { fn(this.incomingRequest); } catch { /* ignore listener error */ }
    });
  }

  private emitTestResult(result: PeerTestResult) {
    this.testResultListeners.forEach(fn => {
      try { fn(result); } catch { /* ignore listener error */ }
    });
  }

  private emitProgress() {
    this.progressListeners.forEach(fn => {
      try { fn(this.currentTransferProgress); } catch { /* ignore listener error */ }
    });
  }

  private emitFileReceived(file: ReceivedFileItem) {
    this.fileReceivedListeners.forEach(fn => {
      try { fn(file); } catch { /* ignore listener error */ }
    });
  }

  private emitQuickText(msg: QuickTextMessage) {
    this.quickTextListeners.forEach(fn => {
      try { fn(msg); } catch { /* ignore listener error */ }
    });
  }

  private emitOnlinePeers() {
    this.onlinePeersListeners.forEach(fn => {
      try { fn(this.onlinePeers); } catch { /* ignore listener error */ }
    });
  }

  private emitChatMessage(msg: P2PChatMessage) {
    this.chatMessageListeners.forEach(fn => {
      try { fn(msg); } catch { /* ignore listener error */ }
    });
  }

  public onStateChange(listener: WebRTCStateListener): () => void {
    this.stateListeners.add(listener);
    listener(this.state);
    return () => this.stateListeners.delete(listener);
  }

  public onDiagnostics(listener: DiagnosticsListener): () => void {
    this.diagnosticsListeners.add(listener);
    listener(this.getDiagnostics());
    return () => this.diagnosticsListeners.delete(listener);
  }

  public onIncomingRequest(listener: IncomingRequestListener): () => void {
    this.incomingRequestListeners.add(listener);
    listener(this.incomingRequest);
    return () => this.incomingRequestListeners.delete(listener);
  }

  public onTestResult(listener: TestResultListener): () => void {
    this.testResultListeners.add(listener);
    if (this.testResult) listener(this.testResult);
    return () => this.testResultListeners.delete(listener);
  }

  public onTransferProgress(listener: TransferProgressListener): () => void {
    this.progressListeners.add(listener);
    listener(this.currentTransferProgress);
    return () => this.progressListeners.delete(listener);
  }

  public onFileReceived(listener: FileReceivedListener): () => void {
    this.fileReceivedListeners.add(listener);
    return () => this.fileReceivedListeners.delete(listener);
  }

  public onQuickText(listener: QuickTextListener): () => void {
    this.quickTextListeners.add(listener);
    return () => this.quickTextListeners.delete(listener);
  }

  public onOnlinePeers(listener: OnlinePeersListener): () => void {
    this.onlinePeersListeners.add(listener);
    listener(this.onlinePeers);
    return () => this.onlinePeersListeners.delete(listener);
  }

  public onChatMessage(listener: ChatMessageListener): () => void {
    this.chatMessageListeners.add(listener);
    return () => this.chatMessageListeners.delete(listener);
  }

  public onChatTyping(listener: ChatTypingListener): () => void {
    this.chatTypingListeners.add(listener);
    return () => this.chatTypingListeners.delete(listener);
  }

  // =========================================================================
  // 2. Offerer (Sender) Flow
  // =========================================================================

  public async requestConnection(params: {
    workspaceId: string;
    receiverConnectionId: string;
    targetUserId?: string;
    targetDeviceId?: string;
    targetDeviceName?: string;
    fileName?: string;
    fileSize?: number;
  }): Promise<void> {
    if (this.state !== 'IDLE' && this.state !== 'CANCELLED' && this.state !== 'REJECTED' && this.state !== 'FAILED' && this.state !== 'DISCONNECTED' && this.state !== 'TIMEOUT') {
      throw new Error(`Cannot initiate connection from state: ${this.state}`);
    }

    this.cleanupPeerResources();
    this.role = 'offerer';
    this.workspaceId = params.workspaceId;
    this.targetConnectionId = params.receiverConnectionId;
    this.targetUserId = params.targetUserId || null;
    const selected = this.onlinePeers.find(peer => peer.connectionId === params.receiverConnectionId);
    this.targetUserId = params.targetUserId || selected?.userId || null;
    this.targetDeviceId = params.targetDeviceId || selected?.deviceId || null;
    this.targetDeviceName = params.targetDeviceName || 'Peer Browser';
    this.lastError = null;
    this.testResult = null;
    this.loadChatHistory();
    this.setState('REQUESTING');
    const resourceEpoch = this.resourceEpoch;
    const reliable = this.ensureReliable(); if (reliable) await reliable.initialized;
    if (this.resourceEpoch !== resourceEpoch || this.getState() !== 'REQUESTING') return;
    this.startSignalingTimeout();

    const sent = this.realtimeClient.sendEnvelope({
      type: 'transfer.request',
      requestId: `req-${uuid()}`,
      payload: {
        protocolVersion: 2,
        workspaceId: params.workspaceId,
        receiverConnectionId: params.receiverConnectionId,
        fileName: params.fileName || 'landrop-p2p.dat',
        fileSize: params.fileSize ?? 1024,
      },
    });

    if (!sent) {
      this.clearSignalingTimeout();
      this.setState('FAILED', 'WebSocket gateway is not connected.');
    }
  }

  // =========================================================================
  // 3. Answerer (Receiver) Flow
  // =========================================================================

  public async acceptIncomingRequest(transferId?: string): Promise<void> {
    const targetId = transferId || this.incomingRequest?.transferId;
    if (!targetId) {
      throw new Error('No active incoming request to accept.');
    }

    if (!this.incomingRequest || this.incomingRequest.transferId !== targetId) {
      throw new Error(`Incoming request not found for transfer ${targetId}`);
    }

    this.cleanupPeerResources();
    this.role = 'answerer';
    this.transferId = targetId;
    this.workspaceId = this.incomingRequest.workspaceId;
    this.targetConnectionId = this.incomingRequest.senderConnectionId;
    this.targetUserId = this.incomingRequest.senderUserId;
    this.targetDeviceId = this.incomingRequest.senderDeviceId || null;
    this.targetDeviceName = this.incomingRequest.senderDeviceName || 'Peer Browser';
    this.incomingRequest = null;
    this.loadChatHistory();
    const reliable = this.ensureReliable(); if (reliable) await reliable.initialized;
    this.emitIncomingRequest();

    this.setState('SIGNALING');
    this.startSignalingTimeout();

    this.initializePeerConnection();

    this.realtimeClient.sendEnvelope({
      type: 'transfer.accept',
      requestId: `acc-${Date.now()}`,
      payload: {
        transferId: targetId,
      },
    });
  }

  public rejectIncomingRequest(reason = 'Rejected by user', transferId?: string): void {
    const targetId = transferId || this.incomingRequest?.transferId;
    if (!targetId) return;

    this.realtimeClient.sendEnvelope({
      type: 'transfer.reject',
      requestId: `rej-${Date.now()}`,
      payload: {
        transferId: targetId,
        reason,
      },
    });

    if (this.incomingRequest?.transferId === targetId) {
      this.incomingRequest = null;
      this.emitIncomingRequest();
    }

    if (this.transferId === targetId) {
      this.setState('REJECTED', reason);
      this.cleanupPeerResources();
    }
  }

  // =========================================================================
  // 4. Cancellation & Teardown Flow
  // =========================================================================

  public cancelConnection(reason = 'Cancelled by user'): void {
    if (this.transferId) {
      this.realtimeClient.sendEnvelope({
        type: 'transfer.cancel',
        requestId: `can-${Date.now()}`,
        payload: {
          transferId: this.transferId,
          reason,
        },
      });
    }

    if (this.dataChannel && this.dataChannel.readyState === 'open') {
      try {
        const cancelMsg: FileCancelMessage = {
          type: 'FILE_CANCEL',
          transferId: this.transferId || '',
          reason,
        };
        this.dataChannel.send(JSON.stringify(cancelMsg));
      } catch { /* ignore */ }
    }

    if (this.currentTransferProgress && this.currentTransferProgress.status === 'transferring') {
      this.currentTransferProgress = {
        ...this.currentTransferProgress,
        status: 'cancelled',
      };
      this.emitProgress();
    }

    this.setState('CANCELLED', reason);
    this.cleanupPeerResources();
  }

  public resetToIdle(): void {
    this.cleanupPeerResources();
    this.role = null;
    this.transferId = null;
    this.workspaceId = null;
    this.targetUserId = null;
    this.targetConnectionId = null;
    this.targetDeviceName = null;
    this.lastError = null;
    this.testResult = null;
    this.currentTransferProgress = null;
    this.emitProgress();
    this.setState('IDLE');
  }

  // =========================================================================
  // 5. Envelope Routing & Protocol Contract
  // =========================================================================

  private handleIncomingEnvelope(envelope: RealtimeEnvelope) {
    const type = envelope.type;
    const payload = (envelope.payload || {}) as Record<string, any>;
    if (type === 'transfer.renew_ack') {
      if (payload.transferId === this.transferId) this.updateLease(payload.expiresAt, envelope.timestamp);
      return;
    }
    if (type === 'error' && this.transferId && (envelope.requestId?.startsWith(`renew-${this.transferId}-`) || envelope.requestId?.startsWith(`restart-${this.transferId}-`))) {
      this.setState('FAILED', envelope.error?.message || 'Peer authorization could not be renewed.'); this.cleanupPeerResources(); return;
    }
    if (type.startsWith('webrtc.')
        && (payload.transferId !== this.transferId || (payload.generation ?? 0) !== this.generation)
        && !['webrtc.restart', 'webrtc.restart_ack'].includes(type)) return;

    switch (type) {
      case 'peer.list':
      case 'peers.list': {
        this.handlePeerListReceived(payload);
        break;
      }

      case 'transfer.status': {
        this.handleTransferStatus(payload, envelope.timestamp);
        break;
      }

      case 'transfer.request': {
        if (this.configOptions?.managed) break;
        this.handleTransferRequestReceived(payload);
        break;
      }

      case 'transfer.accept': {
        this.handleTransferAcceptReceived(payload);
        break;
      }

      case 'transfer.reject': {
        this.handleTransferRejectReceived(payload);
        break;
      }

      case 'transfer.cancel': {
        this.handleTransferCancelReceived(payload);
        break;
      }

      case 'webrtc.offer': {
        void this.handleWebRTCOfferReceived(payload);
        break;
      }

      case 'webrtc.answer': {
        void this.handleWebRTCAnswerReceived(payload);
        break;
      }

      case 'webrtc.ice_candidate':
      case 'webrtc.candidate': {
        void this.handleIceCandidateReceived(payload);
        break;
      }

      case 'webrtc.connection-status': {
        this.handlePeerConnectionStatusReceived(payload);
        break;
      }
      case 'webrtc.restart': {
        if (payload.transferId === this.transferId && this.role === 'answerer' && payload.generation === this.generation + 1) {
          this.generation = payload.generation; this.earlyCandidatesQueue = []; this.coordinatedConnected = false; this.startSignalingTimeout();
          this.updateLease(payload.expiresAt, envelope.timestamp);
        }
        break;
      }
      case 'webrtc.restart_ack': {
        if (payload.transferId === this.transferId && this.role === 'offerer' && payload.generation === this.generation + 1) {
          this.generation = payload.generation; this.earlyCandidatesQueue = []; this.coordinatedConnected = false;
          this.iceRestartPending = false; this.updateLease(payload.expiresAt, envelope.timestamp);
          void this.startOffererNegotiation(true);
        }
        break;
      }

      default:
        break;
    }
  }

  public fetchOnlinePeers(): void {
    this.realtimeClient.sendEnvelope({
      type: 'peer.list',
      requestId: `peers-${Date.now()}`,
    });
  }

  private handlePeerListReceived(payload: Record<string, any>) {
    if (Array.isArray(payload?.peers)) {
      this.onlinePeers = payload.peers as OnlinePeerItem[];
      this.emitOnlinePeers();
    }
  }

  private handleTransferStatus(payload: Record<string, any>, serverTimestamp?: string) {
    const id = payload.transferId;
    const status = payload.status;

    if (!id) return;

    if (this.role === 'offerer' && this.state === 'REQUESTING' && (!this.targetConnectionId || !payload.receiverConnectionId || payload.receiverConnectionId === this.targetConnectionId)) {
      this.transferId = id;
      if (status === 'REQUESTED' || status === 'PENDING') {
        this.setState('WAITING_ACCEPT');
      }
    }
    if (id === this.transferId && status === 'P2P_CONNECTED') this.coordinatedConnected = true;
    if (id === this.transferId) this.updateLease(payload.expiresAt, serverTimestamp);
    if (id === this.transferId && status === 'EXPIRED') { this.setState('TIMEOUT', 'Phiên kết nối hết hạn. Kết nối lại để tiếp tục.'); this.cleanupPeerResources(); }

    if (status === 'REJECTED') {
      if (this.transferId === id) {
        this.clearSignalingTimeout();
        this.setState('REJECTED', payload.reason || 'Yêu cầu kết nối bị từ chối.');
        this.cleanupPeerResources();
      }
    } else if (status === 'CANCELLED') {
      if (this.transferId === id) {
        this.clearSignalingTimeout();
        this.setState('CANCELLED', payload.reason || 'Kết nối đã bị hủy.');
        this.cleanupPeerResources();
      }
    } else if (status === 'FAILED') {
      if (this.transferId === id) {
        this.clearSignalingTimeout();
        this.setState('FAILED', payload.reason || 'Điều phối transfer thất bại.');
        this.cleanupPeerResources();
      }
    }
  }

  private handleTransferRequestReceived(payload: Record<string, any>) {
    const req: IncomingTransferRequest = {
      transferId: payload.transferId,
      workspaceId: payload.workspaceId,
      senderUserId: payload.senderUserId,
      senderConnectionId: payload.senderConnectionId,
      senderDeviceName: payload.senderDeviceName || 'Peer Browser',
      senderDeviceId: payload.senderDeviceId,
      fileName: payload.fileName || 'landrop-p2p.dat',
      fileSize: payload.fileSize ?? 1024,
      receivedAt: Date.now(),
    };

    this.incomingRequest = req;
    this.emitIncomingRequest();
  }

  private handleTransferAcceptReceived(payload: Record<string, any>) {
    const id = payload.transferId;
    if (this.role === 'offerer' && (this.transferId === id || (!this.transferId && payload.receiverConnectionId === this.targetConnectionId))) {
      this.transferId = id;
      this.clearSignalingTimeout();
      this.setState('SIGNALING');
      void this.startOffererNegotiation();
    }
  }

  private handleTransferRejectReceived(payload: Record<string, any>) {
    const id = payload.transferId;
    if (this.transferId === id) {
      this.clearSignalingTimeout();
      this.setState('REJECTED', payload.reason || 'Đối phương đã từ chối yêu cầu.');
      this.cleanupPeerResources();
    }
  }

  private handleTransferCancelReceived(payload: Record<string, any>) {
    const id = payload.transferId;
    if (this.transferId === id) {
      this.clearSignalingTimeout();
      this.setState('CANCELLED', payload.reason || 'Đối tác đã hủy kết nối.');
      this.cleanupPeerResources();
    }
  }

  private async handleWebRTCOfferReceived(payload: Record<string, any>) {
    if (this.role !== 'answerer') return;
    if (!payload.sdp || payload.sdp.type !== 'offer') return;

    this.clearSignalingTimeout();
    this.setState('CONNECTING');

    let pc: RTCPeerConnection | null = null;
    const transferId = this.transferId, generation = this.generation;
    const current = () => this.pc === pc && this.transferId === transferId && this.generation === generation;
    try {
      pc = this.initializePeerConnection();
      await pc.setRemoteDescription(new RTCSessionDescription({
        type: 'offer',
        sdp: payload.sdp.sdp,
      }));

      await this.drainEarlyCandidates();
      if (!current()) return;

      const answer = await pc.createAnswer();
      if (!current()) return;
      await pc.setLocalDescription(answer);
      if (!current()) return;

      this.realtimeClient.sendEnvelope({
        type: 'webrtc.answer',
        requestId: `ans-${Date.now()}`,
        payload: {
          transferId: this.transferId,
          generation: this.generation,
          toConnectionId: this.targetConnectionId,
          sdp: {
            type: answer.type,
            sdp: answer.sdp,
          },
        },
      });

      this.startSignalingTimeout();
    } catch (err: any) {
      if (!current()) return;
      this.setState('FAILED', `Lỗi xử lý Offer: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private async handleWebRTCAnswerReceived(payload: Record<string, any>) {
    if (this.role !== 'offerer' || !this.pc) return;
    if (!payload.sdp || payload.sdp.type !== 'answer') return;

    this.clearSignalingTimeout();
    this.setState('CONNECTING');

    const pc = this.pc, transferId = this.transferId, generation = this.generation;
    const current = () => this.pc === pc && this.transferId === transferId && this.generation === generation;
    try {
      await pc.setRemoteDescription(new RTCSessionDescription({
        type: 'answer',
        sdp: payload.sdp.sdp,
      }));
      if (!current()) return;

      await this.drainEarlyCandidates();
    } catch (err: any) {
      if (!current()) return;
      this.setState('FAILED', `Lỗi xử lý Answer: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private async handleIceCandidateReceived(payload: Record<string, any>) {
    const candidateInit = payload.candidate as RTCIceCandidateInit | null;
    if (!Object.prototype.hasOwnProperty.call(payload, 'candidate')) return;

    if (!this.pc || !this.pc.remoteDescription) {
      this.earlyCandidatesQueue.push(candidateInit as RTCIceCandidateInit);
      return;
    }

    try {
      await this.pc.addIceCandidate(candidateInit === null ? undefined : new RTCIceCandidate(candidateInit));
    } catch (err) {
      console.warn('[WebRTCPeerService] Bỏ qua candidate không hợp lệ', err);
    }
  }

  private handlePeerConnectionStatusReceived(payload: Record<string, any>) {
    if (payload.status === 'failed') {
      this.setState('FAILED', 'Đối tác báo trạng thái kết nối WebRTC thất bại.');
    } else if (payload.status === 'disconnected') {
      if (this.state === 'CONNECTED') {
        this.setState('DISCONNECTED', 'Đối tác báo trạng thái ngắt kết nối.');
      }
    }
  }

  // =========================================================================
  // 6. RTCPeerConnection and Signaling Handlers
  // =========================================================================

  private initializePeerConnection(): RTCPeerConnection {
    if (this.pc) return this.pc;

    const rtcConfig = getRtcConfiguration(this.configOptions);
    const pc = new RTCPeerConnection(rtcConfig);
    this.pc = pc;

    pc.onicecandidate = event => {
      if (this.transferId && this.targetConnectionId) {
        this.realtimeClient.sendEnvelope({
          type: 'webrtc.candidate',
          payload: {
            transferId: this.transferId,
            generation: this.generation,
            toConnectionId: this.targetConnectionId,
            candidate: event.candidate ? {
              candidate: event.candidate.candidate,
              sdpMid: event.candidate.sdpMid,
              sdpMLineIndex: event.candidate.sdpMLineIndex,
              usernameFragment: event.candidate.usernameFragment,
            } : null,
          },
        });
      }
    };

    pc.oniceconnectionstatechange = () => {
      this.emitDiagnostics();
      if (pc.iceConnectionState === 'failed') {
        if (this.configOptions?.localUserId) { this.recoverIce(); return; }
        this.setState('FAILED', 'ICE kết nối thất bại (Direct LAN / STUN không thể thiết lập).');
        this.notifyConnectionStatus('failed');
      }
    };

    pc.onconnectionstatechange = () => {
      this.emitDiagnostics();
      if (pc.connectionState === 'connected') {
        if (this.iceRecoveryTimer) clearTimeout(this.iceRecoveryTimer); this.iceRecoveryTimer = null;
        this.notifyConnectionStatus('connected');
      } else if (pc.connectionState === 'failed') {
        if (this.configOptions?.localUserId) { this.recoverIce(); return; }
        this.setState('FAILED', 'Kết nối ngang hàng WebRTC thất bại.');
        this.notifyConnectionStatus('failed');
      } else if (pc.connectionState === 'disconnected') {
        if (this.configOptions?.localUserId) { this.recoverIce(); return; }
        this.setState('DISCONNECTED', 'Kết nối ngang hàng bị ngắt.');
        this.notifyConnectionStatus('disconnected');
      }
    };

    pc.ondatachannel = event => {
      if (event.channel.label === 'landrop-file-v2') { this.setupFileChannel(event.channel); return; }
      if (event.channel.label === 'landrop-chat-v2') { this.setupChatChannel(event.channel); return; }
      if (this.configOptions?.localUserId && event.channel.label !== 'landrop-p2p') { event.channel.close(); return; }
      this.dataChannel = event.channel;
      this.setupDataChannel(event.channel);
    };

    return pc;
  }

  private async startOffererNegotiation(restart = false) {
    let pc: RTCPeerConnection | null = null;
    const transferId = this.transferId, generation = this.generation;
    const current = () => this.pc === pc && this.transferId === transferId && this.generation === generation;
    try {
      pc = this.initializePeerConnection();

      if (!restart) {
      this.dataChannel = pc.createDataChannel('landrop-p2p', {
        ordered: true,
      });
      this.setupDataChannel(this.dataChannel);
      if (this.configOptions?.localUserId) {
        this.setupFileChannel(pc.createDataChannel('landrop-file-v2', { ordered: true }));
        // Keep chat ordering and unlimited transport retries, on a separate
        // stream from file frames and transfer control packets.
        this.setupChatChannel(pc.createDataChannel('landrop-chat-v2', { ordered: true }));
      }
      }

      const offer = await pc.createOffer(restart ? { iceRestart: true } : undefined);
      if (!current()) return;
      await pc.setLocalDescription(offer);
      if (!current()) return;

      this.realtimeClient.sendEnvelope({
        type: 'webrtc.offer',
        requestId: `off-${Date.now()}`,
        payload: {
          transferId: this.transferId,
          generation: this.generation,
          toConnectionId: this.targetConnectionId,
          sdp: {
            type: offer.type,
            sdp: offer.sdp,
          },
        },
      });

      this.startSignalingTimeout();
    } catch (err: any) {
      if (!current()) return;
      this.setState('FAILED', `Khởi tạo Offer thất bại: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private setupDataChannel(channel: RTCDataChannel) {
    channel.binaryType = 'arraybuffer';
    channel.bufferedAmountLowThreshold = BUFFERED_AMOUNT_LOW_THRESHOLD;

    channel.onopen = () => {
      this.ensureReliable()?.attach(channel, this.fileChannel, this.chatChannel);
      this.clearSignalingTimeout();
      this.emitDiagnostics();

      if (this.role === 'offerer') {
        this.sendTechnicalPing();
      }
      this.startHeartbeat();
      this.drainPendingChatQueue();
    };

    channel.onmessage = event => {
      if (this.reliable?.receive(event.data)) return;
      if (typeof event.data === 'string') {
        try {
          const msg = JSON.parse(event.data) as TechnicalDataMessage;
          if (this.reliable && ['FILE_START', 'FILE_END', 'FILE_CANCEL', 'CHAT_MESSAGE', 'CHAT_ACK'].includes(msg.type)) {
            this.lastError = 'Peer uses an older P2P protocol. Update both clients.'; this.emitDiagnostics(); return;
          }
          this.handleDataChannelMessage(msg);
        } catch (err) {
          console.warn('[WebRTCPeerService] Non-JSON technical data channel message received', err);
        }
      } else if (event.data instanceof ArrayBuffer) {
        if (this.reliable) return;
        this.handleBinaryChunk(event.data);
      }
    };

    channel.onclose = () => {
      this.reliable?.detach();
      this.stopHeartbeat();
      this.handleChatTyping({ type: 'CHAT_TYPING', isTyping: false });
      this.emitDiagnostics();
      if (this.state === 'CONNECTED' || this.state === 'CONNECTING') {
        this.setState('DISCONNECTED', 'Data Channel đã đóng.');
      }
    };

    channel.onerror = () => {
      this.stopHeartbeat();
      this.handleChatTyping({ type: 'CHAT_TYPING', isTyping: false });
      this.emitDiagnostics();
      this.setState(this.configOptions?.localUserId ? 'DISCONNECTED' : 'FAILED', 'Data Channel gặp lỗi truyền dữ liệu.');
      if (this.configOptions?.localUserId) this.cleanupPeerResources();
    };
  }

  private setupFileChannel(channel: RTCDataChannel) {
    this.fileChannel = channel; channel.binaryType = 'arraybuffer';
    channel.onopen = () => this.ensureReliable()?.setFileChannel(channel);
    channel.onmessage = event => { this.reliable?.receive(event.data); };
    // Detaching only the core while leaving the service CONNECTED would strand
    // all future chats in the outbox with no HELLO or reconnect to flush them.
    channel.onclose = channel.onerror = () => { this.setState('DISCONNECTED', 'File channel closed. Reconnecting peer.'); this.cleanupPeerResources(); };
  }

  private setupChatChannel(channel: RTCDataChannel) {
    this.chatChannel = channel; channel.binaryType = 'arraybuffer';
    channel.onopen = () => this.ensureReliable()?.setChatChannel(channel);
    channel.onmessage = event => { this.reliable?.receive(event.data); };
    // Control remains a compatible reliable fallback if just this lane closes.
    channel.onclose = channel.onerror = () => { if (this.chatChannel === channel) { this.chatChannel = null; this.reliable?.setChatChannel(null); } };
  }

  public restartIce(): void {
    if (this.transferId && this.role === 'offerer' && !this.iceRestartPending) {
      this.iceRestartPending = true;
      this.realtimeClient.sendEnvelope({ type: 'webrtc.restart', requestId: `restart-${this.transferId}-${Date.now()}`, payload: { transferId: this.transferId, generation: this.generation + 1 } });
    }
  }

  private recoverIce(): void {
    if (this.iceRecoveryTimer || this.iceRestartPending || !this.transferId) return;
    this.setState('CONNECTING', 'Network interrupted. Recovering the peer connection.');
    this.notifyConnectionStatus('disconnected'); this.startSignalingTimeout();
    if (this.role === 'offerer') this.iceRecoveryTimer = setTimeout(() => { this.iceRecoveryTimer = null; this.restartIce(); }, 3000);
  }

  private async drainEarlyCandidates() {
    if (!this.pc || !this.pc.remoteDescription) return;
    const queued = [...this.earlyCandidatesQueue];
    this.earlyCandidatesQueue = [];

    for (const cand of queued) {
      try {
        await this.pc.addIceCandidate(cand === null ? undefined : new RTCIceCandidate(cand));
      } catch (err) {
        console.warn('[WebRTCPeerService] Lỗi thêm candidate trong hàng đợi', err);
      }
    }
  }

  // =========================================================================
  // 7. Data Channel Verification, Binary File Transfer & Quick Text
  // =========================================================================

  private sendTechnicalPing() {
    if (!this.dataChannel || this.dataChannel.readyState !== 'open') return;

    this.startPingTimeout();

    const ping: TechnicalDataMessage = {
      type: 'PING',
      timestamp: Date.now(),
    };

    this.dataChannel.send(JSON.stringify(ping));
  }

  private handleDataChannelMessage(msg: TechnicalDataMessage) {
    if (msg.type === 'PING') {
      if (this.dataChannel && this.dataChannel.readyState === 'open') {
        const pong: TechnicalDataMessage = {
          type: 'PONG',
          clientTimestamp: msg.timestamp,
          timestamp: Date.now(),
        };
        this.dataChannel.send(JSON.stringify(pong));
      }
      this.setState('CONNECTED');
    } else if (msg.type === 'PONG') {
      this.clearPingTimeout();
      const roundTripMs = Math.max(1, Date.now() - msg.clientTimestamp);
      const result: PeerTestResult = {
        passed: true,
        roundTripMs,
        completedAt: Date.now(),
      };
      this.testResult = result;
      this.setState('CONNECTED');
      this.emitTestResult(result);
    } else if (msg.type === 'FILE_START') {
      this.handleFileStart(msg);
    } else if (msg.type === 'FILE_END') {
      this.handleFileEnd(msg);
    } else if (msg.type === 'FILE_CANCEL') {
      this.handleFileCancel(msg);
    } else if (msg.type === 'CHAT_MESSAGE') {
      this.handleChatMessage(msg);
    } else if (msg.type === 'CHAT_TYPING') {
      this.handleChatTyping(msg);
    } else if (msg.type === 'CHAT_ACK') {
      this.handleChatAck(msg);
    } else if (msg.type === 'TEXT_MESSAGE') {
      this.handleQuickTextMessage(msg);
    }
  }

  private handleFileStart(msg: FileStartMessage) {
    if (this.incomingFileState || typeof msg.fileId !== 'string' || !msg.fileId || typeof msg.fileName !== 'string'
        || !Number.isSafeInteger(msg.fileSize) || msg.fileSize < 0) return;
    this.incomingFileState = {
      transferId: msg.transferId,
      fileId: msg.fileId,
      fileName: msg.fileName,
      fileSize: msg.fileSize,
      mimeType: msg.mimeType,
      totalChunks: msg.totalChunks,
      chunks: [],
      receivedBytes: 0,
      startTime: Date.now(),
      lastProgressEmitTime: 0,
    };

    this.currentTransferProgress = {
      transferId: msg.transferId,
      fileId: msg.fileId,
      fileName: msg.fileName,
      fileSize: msg.fileSize,
      bytesTransferred: 0,
      percent: 0,
      speedBytesPerSec: 0,
      etaSeconds: 0,
      direction: 'receiving',
      status: 'transferring',
    };
    this.emitProgress();
  }

  private handleBinaryChunk(chunk: ArrayBuffer) {
    if (!this.incomingFileState) return;

    this.incomingFileState.chunks.push(chunk);
    this.incomingFileState.receivedBytes += chunk.byteLength;
    if (this.incomingFileState.receivedBytes > this.incomingFileState.fileSize) {
      this.failIncomingFile();
      return;
    }

    // Coalesce intermediate chunks into Blobs to keep RAM footprint bounded during large transfers
    if (this.incomingFileState.chunks.length > 256) {
      const intermediateBlob = new Blob(this.incomingFileState.chunks);
      this.incomingFileState.chunks = [intermediateBlob];
    }

    const now = Date.now();
    // Throttle progress updates to at most 10 Hz or on final byte
    if (now - this.incomingFileState.lastProgressEmitTime >= 100 || this.incomingFileState.receivedBytes >= this.incomingFileState.fileSize) {
      this.incomingFileState.lastProgressEmitTime = now;
      const elapsedSec = Math.max(0.05, (now - this.incomingFileState.startTime) / 1000);
      const speed = this.incomingFileState.receivedBytes / elapsedSec;
      const remainingBytes = Math.max(0, this.incomingFileState.fileSize - this.incomingFileState.receivedBytes);
      const eta = speed > 0 ? Math.ceil(remainingBytes / speed) : 0;
      const percent = Math.min(100, Math.round((this.incomingFileState.receivedBytes / Math.max(1, this.incomingFileState.fileSize)) * 100));

      this.currentTransferProgress = {
        transferId: this.incomingFileState.transferId,
        fileId: this.incomingFileState.fileId,
        fileName: this.incomingFileState.fileName,
        fileSize: this.incomingFileState.fileSize,
        bytesTransferred: this.incomingFileState.receivedBytes,
        percent,
        speedBytesPerSec: Math.round(speed),
        etaSeconds: eta,
        direction: 'receiving',
        status: 'transferring',
      };
      this.emitProgress();
    }
  }

  private handleFileEnd(msg: FileEndMessage) {
    if (!this.incomingFileState) return;
    if (msg.fileId !== this.incomingFileState.fileId || msg.transferId !== this.incomingFileState.transferId) return;
    if (this.incomingFileState.receivedBytes !== this.incomingFileState.fileSize) {
      this.failIncomingFile();
      return;
    }

    const blob = new Blob(this.incomingFileState.chunks, {
      type: this.incomingFileState.mimeType || 'application/octet-stream',
    });
    const blobUrl = URL.createObjectURL(blob);

    const receivedItem: ReceivedFileItem = {
      id: this.incomingFileState.fileId,
      fileName: this.incomingFileState.fileName,
      fileSize: this.incomingFileState.fileSize,
      mimeType: this.incomingFileState.mimeType,
      blobUrl,
      receivedAt: Date.now(),
      senderName: this.targetDeviceName || 'Peer',
    };

    this.receivedFiles = [receivedItem, ...this.receivedFiles];
    this.emitFileReceived(receivedItem);

    this.currentTransferProgress = {
      transferId: this.incomingFileState.transferId,
      fileId: this.incomingFileState.fileId,
      fileName: this.incomingFileState.fileName,
      fileSize: this.incomingFileState.fileSize,
      bytesTransferred: this.incomingFileState.fileSize,
      percent: 100,
      speedBytesPerSec: 0,
      etaSeconds: 0,
      direction: 'receiving',
      status: 'completed',
    };
    this.emitProgress();

    const fileChatMessage: P2PChatMessage = {
      id: receivedItem.id,
      text: `Đã nhận tệp: ${receivedItem.fileName}`,
      senderName: receivedItem.senderName,
      timestamp: receivedItem.receivedAt,
      direction: 'received',
      type: 'file',
      fileMeta: {
        name: receivedItem.fileName,
        size: receivedItem.fileSize,
        blobUrl: receivedItem.blobUrl,
      },
      ack: true,
    };
    this.chatMessages = [...this.chatMessages, fileChatMessage];
    this.emitChatMessage(fileChatMessage);

    if (typeof document !== 'undefined' && document.createElement) {
      try {
        const a = document.createElement('a');
        a.href = blobUrl;
        a.download = receivedItem.fileName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      } catch (err) {
        console.warn('Auto download failed:', err);
      }
    }

    this.incomingFileState = null;
  }

  private handleFileCancel(msg: FileCancelMessage) {
    if (this.currentTransferProgress) {
      this.currentTransferProgress = {
        ...this.currentTransferProgress,
        status: 'cancelled',
      };
      this.emitProgress();
    }
    this.incomingFileState = null;
  }

  private handleQuickTextMessage(msg: QuickTextDataMessage) {
    const item: QuickTextMessage = {
      id: msg.id,
      text: msg.text,
      senderName: msg.senderName || this.targetDeviceName || 'Peer',
      timestamp: msg.timestamp || Date.now(),
      direction: 'received',
    };
    this.quickMessages = [item, ...this.quickMessages];
    this.emitQuickText(item);
  }

  private handleChatMessage(msg: ChatDataMessage) {
    if (typeof msg.id !== 'string' || !msg.id || typeof msg.text !== 'string' || !msg.text.trim()) return;
    if (this.dataChannel && this.dataChannel.readyState === 'open') {
      try {
        const ack: ChatAckMessage = {
          type: 'CHAT_ACK',
          messageId: msg.id,
          status: 'delivered',
        };
        this.dataChannel.send(JSON.stringify(ack));
      } catch { /* ignore */ }
    }

    if (this.chatMessages.some(item => item.id === msg.id && item.direction === 'received')) return;
    this.handleChatTyping({ type: 'CHAT_TYPING', isTyping: false });
    const item: P2PChatMessage = {
      id: msg.id,
      text: msg.text,
      senderName: msg.senderName && msg.senderName !== 'Me' ? msg.senderName : this.targetDeviceName || 'Peer',
      timestamp: Number.isFinite(msg.timestamp) ? msg.timestamp : Date.now(),
      replyToId: typeof msg.replyToId === 'string' ? msg.replyToId : undefined,
      direction: 'received',
      type: 'text',
    };
    this.chatMessages = [...this.chatMessages, item];
    this.emitChatMessage(item);
  }

  private handleChatTyping(msg: ChatTypingMessage) {
    if (typeof msg.isTyping !== 'boolean') return;
    if (this.chatTypingTimer) clearTimeout(this.chatTypingTimer);
    this.chatTypingTimer = null;
    this.chatTypingListeners.forEach(fn => {
      try { fn({ isTyping: msg.isTyping, senderName: msg.senderName || this.targetDeviceName || 'Peer' }); } catch { /* ignore */ }
    });
    if (msg.isTyping) {
      this.chatTypingTimer = setTimeout(() => {
        this.handleChatTyping({ type: 'CHAT_TYPING', isTyping: false });
      }, 3000);
    }
  }

  private handleChatAck(msg: ChatAckMessage) {
    if (msg.status !== 'delivered') return;
    const entry = this.unackedRetries.get(msg.messageId);
    if (entry) {
      clearTimeout(entry.timer);
      this.unackedRetries.delete(msg.messageId);
    }
    const index = this.chatMessages.findIndex(item => item.id === msg.messageId && item.direction === 'sent');
    if (index === -1 || this.chatMessages[index].ack) return;
    const item = { ...this.chatMessages[index], ack: true, pending: false };
    this.chatMessages = this.chatMessages.map((message, i) => i === index ? item : message);
    this.saveChatHistory();
    this.emitChatMessage(item);
  }

  private failIncomingFile() {
    this.incomingFileState = null;
    if (this.currentTransferProgress?.direction === 'receiving') {
      this.currentTransferProgress = { ...this.currentTransferProgress, status: 'failed' };
      this.emitProgress();
    }
  }

  private async waitForChannelBuffer(channel: RTCDataChannel): Promise<void> {
    if (!channel.bufferedAmount || channel.bufferedAmount <= BUFFER_HIGH_WATER_MARK) {
      if (this.dataChannel !== channel || channel.readyState !== 'open') {
        throw new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.');
      }
      return;
    }

    const deadline = Date.now() + 30_000;
    await new Promise<void>((resolve, reject) => {
      let timer: any = null;
      const onLow = () => {
        cleanup();
        resolve();
      };
      const cleanup = () => {
        if (timer) clearTimeout(timer);
        if (typeof channel.removeEventListener === 'function') {
          channel.removeEventListener('bufferedamountlow', onLow);
        }
      };
      if (typeof channel.addEventListener === 'function') {
        channel.addEventListener('bufferedamountlow', onLow, { once: true });
      } else {
        const check = () => {
          if (this.dataChannel !== channel || channel.readyState !== 'open') {
            cleanup();
            reject(new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.'));
          } else if (!channel.bufferedAmount || channel.bufferedAmount <= BUFFER_HIGH_WATER_MARK) {
            cleanup();
            resolve();
          } else if (Date.now() >= deadline) {
            cleanup();
            reject(new Error('Thiết bị nhận không phản hồi. Hãy gửi lại tệp.'));
          } else {
            timer = setTimeout(check, 10);
          }
        };
        timer = setTimeout(check, 10);
        return;
      }
      timer = setTimeout(() => {
        cleanup();
        if (this.dataChannel !== channel || channel.readyState !== 'open') {
          reject(new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.'));
        } else if (channel.bufferedAmount > BUFFER_HIGH_WATER_MARK) {
          reject(new Error('Thiết bị nhận không phản hồi. Hãy gửi lại tệp.'));
        } else {
          resolve();
        }
      }, 30_000);
    });

    if (this.dataChannel !== channel || channel.readyState !== 'open') {
      throw new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.');
    }
  }

  public async sendFile(file: File): Promise<void> {
    const reliable = this.ensureReliable();
    if (reliable) { await reliable.enqueueFile(file); return; }
    if (!this.dataChannel || this.dataChannel.readyState !== 'open') {
      throw new Error('Kênh dữ liệu WebRTC chưa mở. Vui lòng kết nối với thiết bị trước.');
    }
    if (this.sendingFile) throw new Error('Đang gửi một tệp. Vui lòng chờ tệp này hoàn tất.');
    this.sendingFile = true;
    const channel = this.dataChannel;
    try {
      const transferId = this.transferId || `tx-${Date.now()}`;
      const fileId = `file-${Date.now()}`;
      const totalChunks = file.size === 0 ? 0 : Math.ceil(file.size / CHUNK_SIZE);

      const startMsg: FileStartMessage = {
        type: 'FILE_START',
        transferId,
        fileId,
        fileName: file.name,
        fileSize: file.size,
        mimeType: file.type || 'application/octet-stream',
        chunkSize: CHUNK_SIZE,
        totalChunks,
      };

      channel.send(JSON.stringify(startMsg));

      this.currentTransferProgress = {
        transferId,
        fileId,
        fileName: file.name,
        fileSize: file.size,
        bytesTransferred: 0,
        percent: 0,
        speedBytesPerSec: 0,
        etaSeconds: 0,
        direction: 'sending',
        status: 'transferring',
      };
      this.emitProgress();

      const startTime = Date.now();
      let bytesSent = 0;
      let lastProgressEmit = 0;
      const BLOCK_SIZE = 1024 * 1024; // 1 MB chunk reading batch

      for (let blockOffset = 0; blockOffset < file.size; blockOffset += BLOCK_SIZE) {
        if (this.dataChannel !== channel || channel.readyState !== 'open') {
          throw new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.');
        }

        const blockSlice = file.slice(blockOffset, Math.min(file.size, blockOffset + BLOCK_SIZE));
        const blockBuffer = await blockSlice.arrayBuffer();

        for (let chunkOffset = 0; chunkOffset < blockBuffer.byteLength; chunkOffset += CHUNK_SIZE) {
          if (this.dataChannel !== channel || channel.readyState !== 'open') {
            throw new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.');
          }

          const chunk = blockBuffer.slice(chunkOffset, Math.min(blockBuffer.byteLength, chunkOffset + CHUNK_SIZE));

          await this.waitForChannelBuffer(channel);
          channel.send(chunk);
          bytesSent += chunk.byteLength;

          const now = Date.now();
          if (now - lastProgressEmit >= 100 || bytesSent >= file.size) {
            lastProgressEmit = now;
            const elapsedSec = Math.max(0.05, (now - startTime) / 1000);
            const speed = bytesSent / elapsedSec;
            const remainingBytes = Math.max(0, file.size - bytesSent);
            const eta = speed > 0 ? Math.ceil(remainingBytes / speed) : 0;
            const percent = Math.min(100, Math.round((bytesSent / Math.max(1, file.size)) * 100));

            this.currentTransferProgress = {
              transferId,
              fileId,
              fileName: file.name,
              fileSize: file.size,
              bytesTransferred: bytesSent,
              percent,
              speedBytesPerSec: Math.round(speed),
              etaSeconds: eta,
              direction: 'sending',
              status: 'transferring',
            };
            this.emitProgress();
          }
        }
      }

      const endMsg: FileEndMessage = {
        type: 'FILE_END',
        transferId,
        fileId,
      };
      await this.waitForChannelBuffer(channel);
      channel.send(JSON.stringify(endMsg));

      this.currentTransferProgress = {
        transferId,
        fileId,
        fileName: file.name,
        fileSize: file.size,
        bytesTransferred: file.size,
        percent: 100,
        speedBytesPerSec: 0,
        etaSeconds: 0,
        direction: 'sending',
        status: 'completed',
      };
      this.emitProgress();

      const sentFileChat: P2PChatMessage = {
        id: fileId,
        text: `Đã gửi tệp: ${file.name}`,
        senderName: 'Me',
        timestamp: Date.now(),
        direction: 'sent',
        type: 'file',
        fileMeta: {
          name: file.name,
          size: file.size,
        },
        ack: false,
      };
      this.chatMessages = [...this.chatMessages, sentFileChat];
      this.saveChatHistory();
      this.emitChatMessage(sentFileChat);
    } catch (error) {
      if (this.currentTransferProgress?.direction === 'sending' && this.currentTransferProgress.status === 'transferring') {
        this.currentTransferProgress = { ...this.currentTransferProgress, status: 'failed' };
        this.emitProgress();
      }
      if (channel.readyState === 'open') {
        try { channel.send(JSON.stringify({ type: 'FILE_CANCEL', transferId: this.transferId || '', reason: 'Transfer interrupted' })); } catch { /* channel closed */ }
      }
      throw error;
    } finally {
      this.sendingFile = false;
    }
  }

  public sendQuickText(text: string): void {
    if (!this.dataChannel || this.dataChannel.readyState !== 'open') {
      throw new Error('Kênh dữ liệu WebRTC chưa mở.');
    }

    const id = `txt-${Date.now()}`;
    const msg: QuickTextDataMessage = {
      type: 'TEXT_MESSAGE',
      id,
      text,
      senderName: 'Me',
      timestamp: Date.now(),
    };
    this.dataChannel.send(JSON.stringify(msg));

    const item: QuickTextMessage = {
      id,
      text,
      senderName: 'Me',
      timestamp: Date.now(),
      direction: 'sent',
    };
    this.quickMessages = [item, ...this.quickMessages];
    this.emitQuickText(item);
  }

  public sendChatMessage(text: string, replyToId?: string): P2PChatMessage | Promise<P2PChatMessage> {
    const reliable = this.ensureReliable(); if (reliable) { this.sendChatTyping(false); return reliable.sendChat(text, replyToId); }
    if (this.dataChannel && this.dataChannel.readyState === 'closed') {
      throw new Error('Kênh dữ liệu WebRTC đã đóng.');
    }
    if (!this.dataChannel || this.dataChannel.readyState !== 'open') {
      throw new Error('Kênh dữ liệu WebRTC chưa mở.');
    }
    text = text.trim();
    if (!text) throw new Error('Tin nhắn không được để trống.');

    const id = `msg-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    const timestamp = Date.now();
    const packet: ChatDataMessage = {
      type: 'CHAT_MESSAGE',
      id,
      text,
      senderName: 'Me',
      timestamp,
      replyToId,
    };
    this.dataChannel.send(JSON.stringify(packet));
    this.scheduleAckRetry(packet);

    const item: P2PChatMessage = {
      id,
      text,
      senderName: 'Me',
      timestamp,
      replyToId,
      direction: 'sent',
      type: 'text',
      ack: false,
    };
    this.chatMessages = [...this.chatMessages, item];
    this.saveChatHistory();
    this.emitChatMessage(item);
    return item;
  }

  private scheduleAckRetry(packet: ChatDataMessage) {
    if (this.unackedRetries.has(packet.id)) return;
    const attempt = (retries: number) => {
      const timer = setTimeout(() => {
        if (!this.unackedRetries.has(packet.id)) return;
        if (this.dataChannel && this.dataChannel.readyState === 'open' && retries < 3) {
          try {
            this.dataChannel.send(JSON.stringify(packet));
            attempt(retries + 1);
          } catch {
            this.unackedRetries.delete(packet.id);
          }
        } else {
          this.unackedRetries.delete(packet.id);
        }
      }, 2500);
      this.unackedRetries.set(packet.id, { packet, retries, timer });
    };
    attempt(0);
  }

  private clearPendingAckRetries() {
    for (const entry of this.unackedRetries.values()) {
      clearTimeout(entry.timer);
    }
    this.unackedRetries.clear();
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.pingIntervalTimer = setInterval(() => {
      if (this.leaseDeadline && Date.now() >= this.leaseDeadline) { this.setState('TIMEOUT', 'Peer authorization lease expired. Reconnect to continue.'); this.cleanupPeerResources(); return; }
      if (this.coordinatedConnected && this.transferId) this.realtimeClient.sendEnvelope({ type: 'transfer.renew', requestId: `renew-${this.transferId}-${Date.now()}`, payload: { transferId: this.transferId } });
      if (this.dataChannel && this.dataChannel.readyState === 'open') {
        this.sendTechnicalPing();
      }
    }, PING_INTERVAL_MS);
  }

  private stopHeartbeat() {
    if (this.pingIntervalTimer) {
      clearInterval(this.pingIntervalTimer);
      this.pingIntervalTimer = null;
    }
  }

  private drainPendingChatQueue() {
    if (!this.dataChannel || this.dataChannel.readyState !== 'open') return;
    const queue = [...this.pendingChatQueue];
    this.pendingChatQueue = [];
    for (const item of queue) {
      const packet: ChatDataMessage = {
        type: 'CHAT_MESSAGE',
        id: item.id,
        text: item.text,
        senderName: 'Me',
        timestamp: item.timestamp,
        replyToId: item.replyToId,
      };
      try {
        this.dataChannel.send(JSON.stringify(packet));
        this.scheduleAckRetry(packet);
        const idx = this.chatMessages.findIndex(m => m.id === item.id);
        if (idx !== -1) {
          this.chatMessages[idx] = { ...this.chatMessages[idx], pending: false };
        }
      } catch (err) {
        this.pendingChatQueue.push(item);
        break;
      }
    }
    this.saveChatHistory();
  }

  private getStorageKey(): string {
    const ws = this.workspaceId || 'default';
    const peer = this.targetConnectionId || this.targetUserId || 'peer';
    return `landrop_p2p_chat_${ws}_${peer}`;
  }

  private saveChatHistory() {
    if (this.configOptions?.localUserId) return;
    if (typeof localStorage === 'undefined') return;
    try {
      const key = this.getStorageKey();
      const toSave = this.chatMessages.slice(-100).map(m => {
        if (m.type === 'file' && m.fileMeta?.blobUrl) {
          return { ...m, fileMeta: { name: m.fileMeta.name, size: m.fileMeta.size } };
        }
        return m;
      });
      localStorage.setItem(key, JSON.stringify(toSave));
    } catch { /* storage quota or error */ }
  }

  public loadChatHistory(): void {
    if (this.configOptions?.localUserId) return;
    if (typeof localStorage === 'undefined') return;
    try {
      const key = this.getStorageKey();
      const raw = localStorage.getItem(key);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          this.chatMessages = parsed;
          parsed.forEach(m => this.emitChatMessage(m));
        }
      }
    } catch { /* parse error */ }
  }

  public sendChatTyping(isTyping: boolean): void {
    if (this.dataChannel && this.dataChannel.readyState === 'open') {
      try {
        const packet: ChatTypingMessage = {
          type: 'CHAT_TYPING',
          isTyping,
          senderName: 'Me',
        };
        this.dataChannel.send(JSON.stringify(packet));
      } catch { /* ignore */ }
    }
  }

  public getChatMessages(): P2PChatMessage[] {
    return this.chatMessages;
  }

  public clearChatMessages(): void {
    if (this.reliable) { void this.reliable.clearChat().catch(error => { this.lastError = String(error); this.emitDiagnostics(); }); }
    this.chatMessages = [];
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.removeItem(this.getStorageKey());
      } catch { /* ignore */ }
    }
  }

  private notifyConnectionStatus(status: 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed') {
    if (this.transferId) {
      this.realtimeClient.sendEnvelope({
        type: 'webrtc.connection-status',
        payload: {
          transferId: this.transferId,
          generation: this.generation,
          status,
        },
      });
    }
  }

  // =========================================================================
  // 8. Timeout & Cleanup Utilities
  // =========================================================================

  private startSignalingTimeout() {
    this.clearSignalingTimeout();
    this.signalingTimer = setTimeout(() => {
      if (this.state === 'REQUESTING' || this.state === 'WAITING_ACCEPT' || this.state === 'SIGNALING' || this.state === 'CONNECTING') {
        this.setState('TIMEOUT', 'Hết thời gian chờ bắt tay báo hiệu (Signaling Timeout).');
        this.cleanupPeerResources();
      }
    }, SIGNALING_TIMEOUT_MS);
  }

  private clearSignalingTimeout() {
    if (this.signalingTimer) {
      clearTimeout(this.signalingTimer);
      this.signalingTimer = null;
    }
  }

  private startPingTimeout() {
    this.clearPingTimeout();
    this.pingTimeoutTimer = setTimeout(() => {
      if (this.state === 'CONNECTING') {
        this.setState('FAILED', 'Không nhận được phản hồi PONG kỹ thuật qua Data Channel.');
      }
    }, PING_TIMEOUT_MS);
  }

  private clearPingTimeout() {
    if (this.pingTimeoutTimer) {
      clearTimeout(this.pingTimeoutTimer);
      this.pingTimeoutTimer = null;
    }
  }

  private cleanupPeerResources() {
    this.resourceEpoch++;
    if (this.iceRecoveryTimer) clearTimeout(this.iceRecoveryTimer); this.iceRecoveryTimer = null;
    this.iceRestartPending = false; this.leaseDeadline = 0;
    this.reliable?.detach(); this.generation = 0; this.coordinatedConnected = false;
    if (this.fileChannel) { this.fileChannel.onclose = null; this.fileChannel.onerror = null; this.fileChannel.onmessage = null; this.fileChannel.onopen = null; this.fileChannel.close(); this.fileChannel = null; }
    if (this.chatChannel) { this.chatChannel.onclose = null; this.chatChannel.onerror = null; this.chatChannel.onmessage = null; this.chatChannel.onopen = null; this.chatChannel.close(); this.chatChannel = null; }
    this.clearSignalingTimeout();
    this.clearPingTimeout();
    this.stopHeartbeat();
    this.clearPendingAckRetries();
    this.handleChatTyping({ type: 'CHAT_TYPING', isTyping: false });
    if (this.currentTransferProgress?.status === 'transferring') {
      this.currentTransferProgress = { ...this.currentTransferProgress, status: this.state === 'CANCELLED' ? 'cancelled' : 'failed' };
      this.emitProgress();
    }

    if (this.dataChannel) {
      this.dataChannel.onopen = null;
      this.dataChannel.onmessage = null;
      this.dataChannel.onclose = null;
      this.dataChannel.onerror = null;
      try {
        if (this.dataChannel.readyState === 'open' || this.dataChannel.readyState === 'connecting') {
          this.dataChannel.close();
        }
      } catch { /* ignore */ }
      this.dataChannel = null;
    }

    if (this.pc) {
      this.pc.onicecandidate = null;
      this.pc.oniceconnectionstatechange = null;
      this.pc.onconnectionstatechange = null;
      this.pc.ondatachannel = null;
      try {
        if (this.pc.signalingState !== 'closed') {
          this.pc.close();
        }
      } catch { /* ignore */ }
      this.pc = null;
    }

    this.earlyCandidatesQueue = [];
    this.incomingFileState = null;
    this.lastUpdated = Date.now();
  }
}
