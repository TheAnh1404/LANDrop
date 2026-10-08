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
