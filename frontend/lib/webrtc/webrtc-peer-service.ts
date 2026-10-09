/**
 * WebRTC Peer Service for LANDrop REALTIME-02B.
 *
 * Responsibilities:
 * - RTCPeerConnection and RTCDataChannel lifecycles.
 * - Single RealtimeClient envelope routing (strictly no secondary WebSocket).
 * - Exact backend protocol event adherence.
 * - Early ICE candidate queueing prior to remote description.
 * - Technical Ping/Pong round-trip data channel verification.
 * - Robust state machine and diagnostics without tangled booleans.
 * - Complete teardown and memory cleanup.
 */

import { RealtimeClient } from '../realtime/realtime-client';
import type { RealtimeEnvelope } from '../realtime/types';
import { getRtcConfiguration, WebRTCConfigurationOptions } from './config';
import type {
  IncomingTransferRequest,
  PeerTestResult,
  TechnicalDataMessage,
  WebRTCDiagnostics,
  WebRTCPeerState,
  WebRTCRole,
} from './types';

export type WebRTCStateListener = (state: WebRTCPeerState) => void;
export type DiagnosticsListener = (diagnostics: WebRTCDiagnostics) => void;
export type IncomingRequestListener = (request: IncomingTransferRequest | null) => void;
export type TestResultListener = (result: PeerTestResult) => void;

const SIGNALING_TIMEOUT_MS = 30_000;
const PING_TIMEOUT_MS = 10_000;

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

  private readonly stateListeners = new Set<WebRTCStateListener>();
  private readonly diagnosticsListeners = new Set<DiagnosticsListener>();
  private readonly incomingRequestListeners = new Set<IncomingRequestListener>();
  private readonly testResultListeners = new Set<TestResultListener>();

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

  // =========================================================================
  // 2. Offerer (Sender) Flow
  // =========================================================================

  /**
   * Sender initiates P2P connection request targeting a workspace peer connection.
   */
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
        fileName: params.fileName || 'landrop-p2p-test.dat',
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

  /**
   * Receiver accepts an incoming transfer request.
   */
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

    // Prepare RTCPeerConnection to receive offer and data channel
    this.initializePeerConnection();

    this.realtimeClient.sendEnvelope({
      type: 'transfer.accept',
      requestId: `acc-${Date.now()}`,
      payload: {
        transferId: targetId,
      },
    });
  }

  /**
   * Receiver rejects an incoming transfer request.
   */
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

  /**
   * Cancel active transfer / connection at any point.
   */
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
    this.setState('IDLE');
  }

  // =========================================================================
  // 5. Envelope Routing & Protocol Contract
  // =========================================================================

  private handleIncomingEnvelope(envelope: RealtimeEnvelope) {
    const type = envelope.type;
    const payload = (envelope.payload || {}) as Record<string, any>;

    switch (type) {
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
      fileName: payload.fileName || 'landrop-p2p-test.dat',
      fileSize: payload.fileSize || 1024,
      receivedAt: Date.now(),
    };

    this.incomingRequest = req;
    this.emitIncomingRequest();
  }

  private handleTransferAcceptReceived(payload: Record<string, any>) {
    if (this.role !== 'offerer' || !this.transferId || payload.transferId !== this.transferId) {
      return;
    }

    if (payload.receiverConnectionId) {
      this.targetConnectionId = payload.receiverConnectionId;
    }

    this.clearSignalingTimeout();
    this.setState('SIGNALING');

    // Offerer creates PeerConnection, DataChannel and sends Offer
    void this.startOffererNegotiation();
  }

  private handleTransferRejectReceived(payload: Record<string, any>) {
    if (payload.transferId && this.transferId === payload.transferId) {
      this.clearSignalingTimeout();
      this.setState('REJECTED', payload.reason || 'Đối phương từ chối kết nối.');
      this.cleanupPeerResources();
    }
  }

  private handleTransferCancelReceived(payload: Record<string, any>) {
    if (payload.transferId && this.transferId === payload.transferId) {
      this.clearSignalingTimeout();
      this.setState('CANCELLED', payload.reason || 'Đối phương đã hủy kết nối.');
      this.cleanupPeerResources();
    }
  }

  private async handleWebRTCOfferReceived(payload: Record<string, any>) {
    if (!this.transferId || payload.transferId !== this.transferId) return;
    if (this.role !== 'answerer') return;

    const sdpInit = payload.sdp;
    if (!sdpInit || sdpInit.type !== 'offer' || !sdpInit.sdp) {
      this.setState('FAILED', 'Nhận Offer không hợp lệ từ máy chủ.');
      return;
    }

    try {
      if (!this.pc) {
        this.initializePeerConnection();
      }

      await this.pc!.setRemoteDescription(new RTCSessionDescription(sdpInit));
      await this.drainEarlyCandidates();

      const answer = await this.pc!.createAnswer();
      await this.pc!.setLocalDescription(answer);

      this.setState('CONNECTING');

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
    } catch (err: any) {
      this.setState('FAILED', `Thiết lập Answer thất bại: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private async handleWebRTCAnswerReceived(payload: Record<string, any>) {
    if (!this.transferId || payload.transferId !== this.transferId) return;
    if (this.role !== 'offerer') return;

    const sdpInit = payload.sdp;
    if (!sdpInit || sdpInit.type !== 'answer' || !sdpInit.sdp) {
      this.setState('FAILED', 'Nhận Answer không hợp lệ từ máy chủ.');
      return;
    }

    try {
      if (!this.pc) return;

      await this.pc.setRemoteDescription(new RTCSessionDescription(sdpInit));
      await this.drainEarlyCandidates();
      this.setState('CONNECTING');
    } catch (err: any) {
      this.setState('FAILED', `Thiết lập Remote Description (Answer) thất bại: ${err?.message || err}`);
      this.cleanupPeerResources();
    }
  }

  private async handleIceCandidateReceived(payload: Record<string, any>) {
    if (!this.transferId || payload.transferId !== this.transferId) return;
    const cand = payload.candidate;
    if (!cand || !cand.candidate) return;

    const candidateInit: RTCIceCandidateInit = {
      candidate: cand.candidate,
      sdpMid: cand.sdpMid ?? undefined,
      sdpMLineIndex: cand.sdpMLineIndex ?? undefined,
      usernameFragment: cand.usernameFragment ?? undefined,
    };

    if (!this.pc || !this.pc.remoteDescription) {
      // Early candidate arrival: push to queue
      this.earlyCandidatesQueue.push(candidateInit);
    } else {
      try {
        await this.pc.addIceCandidate(new RTCIceCandidate(candidateInit));
      } catch (err) {
        console.warn('[WebRTCPeerService] Error adding ICE candidate', err);
      }
    }
  }

  private handlePeerConnectionStatusReceived(payload: Record<string, any>) {
    if (!this.transferId || payload.transferId !== this.transferId) return;
    const status = payload.status;
    if (status === 'failed') {
      this.setState('FAILED', 'Đối phương báo lỗi kết nối P2P (failed).');
      this.cleanupPeerResources();
    } else if (status === 'closed') {
      this.setState('DISCONNECTED', 'Đối phương đã đóng kết nối P2P.');
      this.cleanupPeerResources();
    }
  }

  // =========================================================================
  // 6. WebRTC Core Logic & Data Channel Establishment
  // =========================================================================

  private initializePeerConnection(): RTCPeerConnection {
    if (typeof RTCPeerConnection === 'undefined') {
      throw new Error('Môi trường trình duyệt không hỗ trợ WebRTC (RTCPeerConnection is undefined).');
    }

    const config = getRtcConfiguration(this.configOptions);
    const pc = new RTCPeerConnection(config);
    this.pc = pc;

    pc.onicecandidate = event => {
      if (event.candidate && this.transferId) {
        this.realtimeClient.sendEnvelope({
          type: 'webrtc.ice_candidate',
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

      // Offerer creates DataChannel
      this.dataChannel = pc.createDataChannel('landrop-p2p-test', {
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
    channel.onopen = () => {
      this.clearSignalingTimeout();
      this.emitDiagnostics();

      if (this.role === 'offerer') {
        // Offerer initiates technical ping test
        this.sendTechnicalPing();
      }
    };

    channel.onmessage = event => {
      try {
        const msg = JSON.parse(event.data as string) as TechnicalDataMessage;
        this.handleDataChannelMessage(msg);
      } catch (err) {
        console.warn('[WebRTCPeerService] Non-JSON technical data channel message received', err);
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
  // 7. Technical Ping / Pong Data Channel Verification
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
      // Receiver replies with PONG preserving client timestamp
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
      // Sender receives PONG: calculate round-trip latency
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
    }
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
  // 8. Timers and Resource Cleanup
  // =========================================================================

  private startSignalingTimeout() {
    this.clearSignalingTimeout();
    this.signalingTimer = setTimeout(() => {
      if (this.state === 'REQUESTING' || this.state === 'WAITING_ACCEPT' || this.state === 'SIGNALING') {
        this.setState('TIMEOUT', 'Hết thời gian chờ phản hồi báo hiệu kết nối (Signaling timeout).');
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
        this.setState('TIMEOUT', 'Không nhận được phản hồi PONG qua Data Channel (Ping timeout).');
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
    this.lastUpdated = Date.now();
  }
}
