/**
 * WebRTC Peer Connection and Diagnostics types for LANDrop REALTIME-02B.
 */

export type WebRTCPeerState =
  | 'IDLE'
  | 'REQUESTING'
  | 'WAITING_ACCEPT'
  | 'SIGNALING'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'FAILED'
  | 'DISCONNECTED'
  | 'TIMEOUT';

export type WebRTCRole = 'offerer' | 'answerer';

export interface WebRTCDiagnostics {
  transferId: string | null;
  role: WebRTCRole | null;
  targetUserId: string | null;
  targetConnectionId: string | null;
  targetDeviceName: string | null;
  signalingState: WebRTCPeerState;
  rtcSignalingState: RTCSignalingState | 'none';
  iceConnectionState: RTCIceConnectionState | 'none';
  peerConnectionState: RTCPeerConnectionState | 'none';
  dataChannelState: RTCDataChannelState | 'none';
  roundTripLatencyMs: number | null;
  lastError: string | null;
  lastUpdated: number;
}

export interface TechnicalPingMessage {
  type: 'PING';
  timestamp: number;
}

export interface TechnicalPongMessage {
  type: 'PONG';
  clientTimestamp: number;
  timestamp: number;
}

export type TechnicalDataMessage = TechnicalPingMessage | TechnicalPongMessage;

export interface IncomingTransferRequest {
  transferId: string;
  workspaceId: string;
  senderUserId: string;
  senderConnectionId: string;
  senderDeviceName?: string;
  fileName: string;
  fileSize: number;
  receivedAt: number;
}

export interface PeerTestResult {
  passed: boolean;
  roundTripMs: number;
  completedAt: number;
}
