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
import type {
  FileCancelMessage,
  FileEndMessage,
  FileStartMessage,
  FileTransferProgress,
  IncomingTransferRequest,
  OnlinePeerItem,
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

const SIGNALING_TIMEOUT_MS = 30_000;
const PING_TIMEOUT_MS = 10_000;
const CHUNK_SIZE = 16_384; // 16 KB binary chunks
const BUFFERED_AMOUNT_LOW_THRESHOLD = 65_536; // 64 KB

export class WebRTCPeerService {
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

  // Transfer & Presence State
  private currentTransferProgress: FileTransferProgress | null = null;
  private receivedFiles: ReceivedFileItem[] = [];
  private quickMessages: QuickTextMessage[] = [];
  private onlinePeers: OnlinePeerItem[] = [];
  private incomingFileState: {
    transferId: string;
    fileId: string;
    fileName: string;
    fileSize: number;
    mimeType: string;
    totalChunks: number;
    chunks: ArrayBuffer[];
    receivedBytes: number;
    startTime: number;
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
    this.stateListeners.clear();
    this.diagnosticsListeners.clear();
    this.incomingRequestListeners.clear();
    this.testResultListeners.clear();
    this.progressListeners.clear();
    this.fileReceivedListeners.clear();
    this.quickTextListeners.clear();
    this.onlinePeersListeners.clear();
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

  // =========================================================================
  // 2. Offerer (Sender) Flow
  // =========================================================================

  public async requestConnection(params: {
    workspaceId: string;
    receiverConnectionId: string;
    targetUserId?: string;
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
    this.targetDeviceName = params.targetDeviceName || 'Peer Browser';
    this.lastError = null;
    this.testResult = null;

    this.setState('REQUESTING');
    this.startSignalingTimeout();

    const sent = this.realtimeClient.sendEnvelope({
      type: 'transfer.request',
      requestId: `req-${Date.now()}`,
      payload: {
        workspaceId: params.workspaceId,
        receiverConnectionId: params.receiverConnectionId,
        fileName: params.fileName || 'landrop-p2p.dat',
        fileSize: params.fileSize || 1024,
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
    this.targetDeviceName = this.incomingRequest.senderDeviceName || 'Peer Browser';
    this.incomingRequest = null;
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

    switch (type) {
      case 'peer.list':
      case 'peers.list': {
        this.handlePeerListReceived(payload);
        break;
      }

      case 'transfer.status': {
        this.handleTransferStatus(payload);
        break;
      }

      case 'transfer.request': {
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

  private handleTransferStatus(payload: Record<string, any>) {
    const id = payload.transferId;
    const status = payload.status;

    if (!id) return;

    if (this.role === 'offerer' && this.state === 'REQUESTING') {
      this.transferId = id;
      if (status === 'REQUESTED' || status === 'PENDING') {
        this.setState('WAITING_ACCEPT');
      }
    }

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
      fileName: payload.fileName || 'landrop-p2p.dat',
      fileSize: payload.fileSize || 1024,
      receivedAt: Date.now(),
    };

    this.incomingRequest = req;
    this.emitIncomingRequest();
  }

  private handleTransferAcceptReceived(payload: Record<string, any>) {
    const id = payload.transferId;
    if (this.role === 'offerer' && (this.transferId === id || !this.transferId)) {
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

    try {
      const pc = this.initializePeerConnection();
      await pc.setRemoteDescription(new RTCSessionDescription({
        type: 'offer',
        sdp: payload.sdp.sdp,
      }));

      await this.drainEarlyCandidates();

      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);

      this.realtimeClient.sendEnvelope({
        type: 'webrtc.answer',
        requestId: `ans-${Date.now()}`,
        payload: {
          transferId: this.transferId,
          toConnectionId: this.targetConnectionId,
          sdp: {
            type: answer.type,
            sdp: answer.sdp,
          },
        },
      });

      this.startSignalingTimeout();
    } catch (err: any) {
      this.setState('FAILED', `Lỗi xử lý Offer: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private async handleWebRTCAnswerReceived(payload: Record<string, any>) {
    if (this.role !== 'offerer' || !this.pc) return;
    if (!payload.sdp || payload.sdp.type !== 'answer') return;

    this.clearSignalingTimeout();
    this.setState('CONNECTING');

    try {
      await this.pc.setRemoteDescription(new RTCSessionDescription({
        type: 'answer',
        sdp: payload.sdp.sdp,
      }));

      await this.drainEarlyCandidates();
    } catch (err: any) {
      this.setState('FAILED', `Lỗi xử lý Answer: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private async handleIceCandidateReceived(payload: Record<string, any>) {
    const candidateInit = payload.candidate as RTCIceCandidateInit | null;
    if (!candidateInit) return;

    if (!this.pc || !this.pc.remoteDescription) {
      this.earlyCandidatesQueue.push(candidateInit);
      return;
    }

    try {
      await this.pc.addIceCandidate(new RTCIceCandidate(candidateInit));
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
      if (event.candidate && this.transferId && this.targetConnectionId) {
        this.realtimeClient.sendEnvelope({
          type: 'webrtc.candidate',
          payload: {
            transferId: this.transferId,
            toConnectionId: this.targetConnectionId,
            candidate: {
              candidate: event.candidate.candidate,
              sdpMid: event.candidate.sdpMid,
              sdpMLineIndex: event.candidate.sdpMLineIndex,
              usernameFragment: event.candidate.usernameFragment,
            },
          },
        });
      }
    };

    pc.oniceconnectionstatechange = () => {
      this.emitDiagnostics();
      if (pc.iceConnectionState === 'failed') {
        this.setState('FAILED', 'ICE kết nối thất bại (Direct LAN / STUN không thể thiết lập).');
        this.notifyConnectionStatus('failed');
      }
    };

    pc.onconnectionstatechange = () => {
      this.emitDiagnostics();
      if (pc.connectionState === 'connected') {
        this.notifyConnectionStatus('connected');
      } else if (pc.connectionState === 'failed') {
        this.setState('FAILED', 'Kết nối ngang hàng WebRTC thất bại.');
        this.notifyConnectionStatus('failed');
      } else if (pc.connectionState === 'disconnected') {
        this.setState('DISCONNECTED', 'Kết nối ngang hàng bị ngắt.');
        this.notifyConnectionStatus('disconnected');
      }
    };

    pc.ondatachannel = event => {
      this.dataChannel = event.channel;
      this.setupDataChannel(event.channel);
    };

    return pc;
  }

  private async startOffererNegotiation() {
    try {
      const pc = this.initializePeerConnection();

      this.dataChannel = pc.createDataChannel('landrop-p2p', {
        ordered: true,
      });
      this.setupDataChannel(this.dataChannel);

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      this.realtimeClient.sendEnvelope({
        type: 'webrtc.offer',
        requestId: `off-${Date.now()}`,
        payload: {
          transferId: this.transferId,
          toConnectionId: this.targetConnectionId,
          sdp: {
            type: offer.type,
            sdp: offer.sdp,
          },
        },
      });

      this.startSignalingTimeout();
    } catch (err: any) {
      this.setState('FAILED', `Khởi tạo Offer thất bại: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private setupDataChannel(channel: RTCDataChannel) {
    channel.binaryType = 'arraybuffer';
    channel.bufferedAmountLowThreshold = BUFFERED_AMOUNT_LOW_THRESHOLD;

    channel.onopen = () => {
      this.clearSignalingTimeout();
      this.emitDiagnostics();

      if (this.role === 'offerer') {
        this.sendTechnicalPing();
      }
    };

    channel.onmessage = event => {
      if (typeof event.data === 'string') {
        try {
          const msg = JSON.parse(event.data) as TechnicalDataMessage;
          this.handleDataChannelMessage(msg);
        } catch (err) {
          console.warn('[WebRTCPeerService] Non-JSON technical data channel message received', err);
        }
      } else if (event.data instanceof ArrayBuffer) {
        this.handleBinaryChunk(event.data);
      }
    };

    channel.onclose = () => {
      this.emitDiagnostics();
      if (this.state === 'CONNECTED' || this.state === 'CONNECTING') {
        this.setState('DISCONNECTED', 'Data Channel đã đóng.');
      }
    };

    channel.onerror = () => {
      this.emitDiagnostics();
      this.setState('FAILED', 'Data Channel gặp lỗi truyền dữ liệu.');
    };
  }

  private async drainEarlyCandidates() {
    if (!this.pc || !this.pc.remoteDescription) return;
    const queued = [...this.earlyCandidatesQueue];
    this.earlyCandidatesQueue = [];

    for (const cand of queued) {
      try {
        await this.pc.addIceCandidate(new RTCIceCandidate(cand));
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
    } else if (msg.type === 'TEXT_MESSAGE') {
      this.handleQuickTextMessage(msg);
    }
  }

  private handleFileStart(msg: FileStartMessage) {
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

    const now = Date.now();
    const elapsedSec = Math.max(0.1, (now - this.incomingFileState.startTime) / 1000);
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

  private handleFileEnd(msg: FileEndMessage) {
    if (!this.incomingFileState) return;

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

  public async sendFile(file: File): Promise<void> {
    if (!this.dataChannel || this.dataChannel.readyState !== 'open') {
      throw new Error('Kênh dữ liệu WebRTC chưa mở. Vui lòng kết nối với thiết bị trước.');
    }

    const transferId = this.transferId || `tx-${Date.now()}`;
    const fileId = `file-${Date.now()}`;
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE) || 1;

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

    this.dataChannel.send(JSON.stringify(startMsg));

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

    for (let offset = 0; offset < file.size; offset += CHUNK_SIZE) {
      if (!this.dataChannel || this.dataChannel.readyState !== 'open') {
        throw new Error('Kênh dữ liệu bị ngắt trong quá trình truyền.');
      }

      const slice = file.slice(offset, Math.min(file.size, offset + CHUNK_SIZE));
      const buffer = await slice.arrayBuffer();

      // Backpressure: pause if buffer is full (> 64KB)
      if (this.dataChannel.bufferedAmount > BUFFERED_AMOUNT_LOW_THRESHOLD) {
        await new Promise<void>(resolve => {
          if (!this.dataChannel) return resolve();
          const onLow = () => {
            if (this.dataChannel) this.dataChannel.onbufferedamountlow = null;
            resolve();
          };
          this.dataChannel.onbufferedamountlow = onLow;
          setTimeout(resolve, 200);
        });
      }

      this.dataChannel.send(buffer);
      bytesSent += buffer.byteLength;

      const now = Date.now();
      const elapsedSec = Math.max(0.1, (now - startTime) / 1000);
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

    const endMsg: FileEndMessage = {
      type: 'FILE_END',
      transferId,
      fileId,
    };
    this.dataChannel.send(JSON.stringify(endMsg));

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

  private notifyConnectionStatus(status: 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed') {
    if (this.transferId) {
      this.realtimeClient.sendEnvelope({
        type: 'webrtc.connection-status',
        payload: {
          transferId: this.transferId,
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
    this.clearSignalingTimeout();
    this.clearPingTimeout();

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
