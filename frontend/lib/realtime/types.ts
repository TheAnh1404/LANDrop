import type { WireMessage } from '../api/models';

export type ConnectionState =
  | 'IDLE'
  | 'CONNECTING'
  | 'HANDSHAKING'
  | 'AUTHENTICATING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'DISCONNECTED'
  | 'AUTH_REQUIRED'
  | 'SESSION_EXPIRED'
  | 'SESSION_REVOKED'
  | 'ACCOUNT_DISABLED';

export interface RealtimeEnvelope<T = unknown> {
  type: string;
  requestId?: string | null;
  timestamp?: string;
  payload?: T;
  error?: {
    code: string;
    message: string;
  } | null;
  connectionId?: string;
  code?: string;
  message?: string;
}

export interface WelcomePayload {
  connectionId?: string;
  serverName?: string;
  serverVersion?: string;
  protocolVersion?: number;
  heartbeatIntervalMs?: number;
  authenticated?: boolean;
  userId?: string;
  deviceId?: string;
  sessionId?: string;
}

export interface AuthAckPayload {
  authenticated: boolean;
  userId: string;
  username: string;
  email?: string;
  deviceId?: string;
  sessionId?: string;
}

export interface MessageCreatedPayload {
  type: 'message.created';
  channelId: string;
  message: WireMessage;
}

export interface MessageUpdatedPayload {
  type: 'message.updated';
  channelId: string;
  messageId: string;
  message: WireMessage;
}

export interface MessageDeletedPayload {
  type: 'message.deleted';
  channelId: string;
  messageId?: string;
  deletedAt?: string;
  message?: WireMessage;
}

export type RealtimeChatEvent =
  | { type: 'message.created'; payload: MessageCreatedPayload }
  | { type: 'message.updated'; payload: MessageUpdatedPayload }
  | { type: 'message.deleted'; payload: MessageDeletedPayload };

export interface RealtimeConnectOptions {
  serverUrl: string;
  accessToken?: string | null;
  token?: string | null;
  deviceId?: string | null;
  clientInstanceId?: string | null;
  deviceName?: string | null;
}

export interface HelloPayload {
  type: 'hello';
  protocolVersion: number;
  clientInstanceId: string;
  deviceName?: string;
  platform?: string;
  clientVersion?: string;
}

export interface AuthCommandPayload {
  accessToken: string;
  deviceId?: string;
}

export interface TransferRequestPayload {
  transferId?: string;
  workspaceId: string;
  receiverConnectionId?: string;
  targetConnectionId?: string;
  senderConnectionId?: string;
  senderDeviceName?: string;
  senderUserId?: string;
  fileName: string;
  fileSize: number;
  sha256?: string;
  status?: string;
}

export interface TransferAcceptPayload {
  transferId: string;
  receiverConnectionId?: string;
}

export interface TransferRejectPayload {
  transferId: string;
  reason?: string;
}

export interface TransferCancelPayload {
  transferId: string;
  reason?: string;
}

export interface TransferStatusPayload {
  transferId: string;
  status: string;
  fileName?: string;
  fileSize?: number;
  reason?: string;
  workspaceId?: string;
  senderConnectionId?: string;
  receiverConnectionId?: string;
  senderUserId?: string;
  receiverUserId?: string;
  createdAt?: string;
  expiresAt?: string;
}

export interface WebRTCOfferPayload {
  transferId: string;
  sdp: {
    type: 'offer';
    sdp: string;
  };
  workspaceId?: string;
  toConnectionId?: string;
  fromConnectionId?: string;
  fromUserId?: string;
  fromDeviceName?: string;
}

export interface WebRTCAnswerPayload {
  transferId: string;
  sdp: {
    type: 'answer';
    sdp: string;
  };
  workspaceId?: string;
  toConnectionId?: string;
  fromConnectionId?: string;
  fromUserId?: string;
  fromDeviceName?: string;
}

export interface WebRTCIceCandidatePayload {
  transferId: string;
  candidate: {
    candidate: string;
    sdpMid?: string | null;
    sdpMLineIndex?: number | null;
    usernameFragment?: string | null;
  } | null;
  workspaceId?: string;
  toConnectionId?: string;
  fromConnectionId?: string;
}

export interface WebRTCConnectionStatusPayload {
  transferId: string;
  status: 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';
  fromConnectionId?: string;
}
