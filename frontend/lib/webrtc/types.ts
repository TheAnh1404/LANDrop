/**
 * WebRTC Peer Connection, DataChannel Transfer and Diagnostics types for LANDrop.
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

export interface FileStartMessage {
  type: 'FILE_START';
  transferId: string;
  fileId: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  chunkSize: number;
  totalChunks: number;
}

export interface FileEndMessage {
  type: 'FILE_END';
  transferId: string;
  fileId: string;
}

export interface FileCancelMessage {
  type: 'FILE_CANCEL';
  transferId: string;
  reason?: string;
}

export interface QuickTextDataMessage {
  type: 'TEXT_MESSAGE';
  id: string;
  text: string;
  senderName: string;
  timestamp: number;
}

export interface ChatDataMessage {
  type: 'CHAT_MESSAGE';
  id: string;
  text: string;
  senderName: string;
  timestamp: number;
  replyToId?: string;
}

export interface ChatTypingMessage {
  type: 'CHAT_TYPING';
  isTyping: boolean;
  senderName?: string;
}

export interface ChatAckMessage {
  type: 'CHAT_ACK';
  messageId: string;
  status: 'delivered';
}

export interface P2PChatMessage {
  id: string;
  text: string;
  senderName: string;
  timestamp: number;
  replyToId?: string;
  direction: 'sent' | 'received';
  type: 'text' | 'file';
  fileMeta?: {
    name: string;
    size: number;
    blobUrl?: string;
  };
  ack?: boolean;
  pending?: boolean;
  status?: string;
}

export type TechnicalDataMessage =
  | TechnicalPingMessage
  | TechnicalPongMessage
  | FileStartMessage
  | FileEndMessage
  | FileCancelMessage
  | QuickTextDataMessage
  | ChatDataMessage
  | ChatTypingMessage
  | ChatAckMessage;

export interface IncomingTransferRequest {
  transferId: string;
  workspaceId: string;
  senderUserId: string;
  senderConnectionId: string;
  senderDeviceName?: string;
  senderDeviceId?: string;
  fileName: string;
  fileSize: number;
  receivedAt: number;
}

export interface PeerTestResult {
  passed: boolean;
  roundTripMs: number;
  completedAt: number;
}

export interface FileTransferProgress {
  transferId: string;
  fileId: string;
  fileName: string;
  fileSize: number;
  bytesTransferred: number;
  percent: number;
  speedBytesPerSec: number;
  etaSeconds: number;
  direction: 'sending' | 'receiving';
  status: 'transferring' | 'completed' | 'cancelled' | 'failed';
}

export interface ReceivedFileItem {
  id: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  blobUrl: string;
  receivedAt: number;
  senderName: string;
}

export interface QuickTextMessage {
  id: string;
  text: string;
  senderName: string;
  timestamp: number;
  direction: 'sent' | 'received';
}

export interface OnlinePeerItem {
  connectionId: string;
  userId?: string | null;
  deviceId?: string | null;
  displayName: string;
  deviceName: string;
  platform: string;
}
