import { resolveWebSocketUrl } from './config';
import type {
  AuthAckPayload,
  ConnectionState,
  MessageCreatedPayload,
  MessageUpdatedPayload,
  MessageDeletedPayload,
  RealtimeChatEvent,
  RealtimeConnectOptions,
  RealtimeEnvelope,
  WelcomePayload,
} from './types';

export type StateListener = (state: ConnectionState) => void;
export type ChatEventListener = (event: RealtimeChatEvent) => void;
export type ReconnectListener = () => void;
export type ChannelJoinedListener = (channelId: string) => void;

const PROTOCOL_VERSION = 1;
const DEFAULT_HEARTBEAT_INTERVAL_MS = 15_000;
const DEFAULT_HEARTBEAT_TIMEOUT_MS = 45_000;
const MAX_RECONNECT_DELAY_MS = 15_000;

/**
 * Safe RFC4122 v4 UUID generator fallback for plain HTTP LAN environments
 * where native crypto.randomUUID may be undefined.
 */
export function generateSafeUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export class RealtimeClient {
  private ws: WebSocket | null = null;
  private state: ConnectionState = 'IDLE';
  private options: RealtimeConnectOptions | null = null;
  private clientInstanceId: string;
  private manualDisconnect = false;
  private lastPrecedingAuthError: string | null = null;

  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  private heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private lastHeartbeatAck = 0;

  private readonly subscribedChannels = new Set<string>();

  private readonly stateListeners = new Set<StateListener>();
  private readonly messageListeners = new Set<ChatEventListener>();
  private readonly reconnectListeners = new Set<ReconnectListener>();
  private readonly channelJoinedListeners = new Set<ChannelJoinedListener>();
  private readonly envelopeListeners = new Set<(envelope: RealtimeEnvelope) => void>();
  private connectionId: string | null = null;

  constructor() {
    this.clientInstanceId = this.resolveClientInstanceId();
  }

  private resolveClientInstanceId(userId?: string): string {
    if (typeof window !== 'undefined') {
      const key = userId ? `landrop.client_instance_id:${userId}` : 'landrop.client_instance_id';
      for (const name of ['localStorage', 'sessionStorage'] as const) {
        try {
          const storage = window[name]; if (!storage) continue;
          let id = storage.getItem(key);
          if (!id && name === 'localStorage') id = window.sessionStorage?.getItem(key) || null;
          if (!id) id = `browser-${generateSafeUuid()}`;
          storage.setItem(key, id);
          return id;
        } catch { /* blocked persistent storage falls back to the current tab */ }
      }
    }
    return `tab-${generateSafeUuid()}`;
  }

  public getClientInstanceId(userId?: string): string {
    return userId ? this.resolveClientInstanceId(userId) : this.clientInstanceId;
  }

  public getState(): ConnectionState {
    return this.state;
  }

  public getSubscribedChannels(): ReadonlySet<string> {
    return this.subscribedChannels;
  }

  private setState(next: ConnectionState) {
    if (this.state === next) return;
    const prev = this.state;
    this.state = next;
    this.logDebug(`State transition: ${prev} -> ${next}`);
    this.stateListeners.forEach(fn => {
      try { fn(next); } catch { /* ignore listener failures */ }
    });
  }

  private logDebug(tag: string, detail?: string) {
    // Structured operational logging without leaking credentials
    const extra = detail ? ` - ${detail}` : '';
    console.debug(`[RealtimeClient] ${tag}${extra}`);
  }

  public connect(options: RealtimeConnectOptions) {
    if (typeof window === 'undefined') return;

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    this.options = options;
    if (options.clientInstanceId) this.clientInstanceId = options.clientInstanceId;
    this.manualDisconnect = false;
    this.reconnectAttempts = 0;
    this.lastPrecedingAuthError = null;

    this.initiateSocketConnection();
  }

  private initiateSocketConnection() {
    if (!this.options) return;
    if (this.ws) {
      this.cleanupSocket();
    }

    const wsUrl = resolveWebSocketUrl(this.options.serverUrl);
    this.logDebug('WS_CONNECTING', `Connecting to gateway at ${wsUrl}`);
    this.setState('CONNECTING');

    try {
      // Connect to WebSocket gateway.
      // Contract: Server rejects ALL query strings at upgrade; do NOT append credentials to URL.
      this.ws = new WebSocket(wsUrl);
    } catch (error) {
      this.logDebug('WS_DISCONNECTED', `Failed creating WebSocket: ${error}`);
      this.handleConnectionLost(false);
      return;
    }

    this.ws.onopen = this.handleSocketOpen.bind(this);
    this.ws.onmessage = this.handleSocketMessage.bind(this);
    this.ws.onclose = this.handleSocketClose.bind(this);
    this.ws.onerror = this.handleSocketError.bind(this);
  }

  private handleSocketOpen() {
    this.logDebug('WS_CONNECTED', 'Transport open, starting handshake');
    this.setState('HANDSHAKING');

    // 1. Send protocol hello handshake.
    // Contract: Flat hello without token or query credentials.
    const helloPayload = {
      type: 'hello',
      protocolVersion: PROTOCOL_VERSION,
      clientInstanceId: this.clientInstanceId,
      deviceName: this.options?.deviceName || 'Browser',
      platform: 'web',
      clientVersion: '1.0.0',
    };

    this.sendJson(helloPayload);
  }

  private handleSocketMessage(event: MessageEvent) {
    let envelope: RealtimeEnvelope;
    try {
      envelope = JSON.parse(event.data as string) as RealtimeEnvelope;
    } catch {
      this.logDebug('PARSE_ERROR', 'Received malformed JSON message from server');
      return;
    }

    // Notify registered generic envelope listeners (e.g. WebRTC peer service)
    this.envelopeListeners.forEach(listener => {
      try {
        listener(envelope);
      } catch (err) {
        this.logDebug('LISTENER_ERROR', 'Envelope listener threw an unhandled error');
      }
    });

    const type = envelope.type;

    switch (type) {
      case 'welcome': {
        const welcome = (envelope.payload || envelope) as WelcomePayload;
        this.connectionId = welcome.connectionId || (envelope as any).connectionId || null;
        if (this.connectionId) {
          this.logDebug('WS_CONNECTION_ID', `Connection ID assigned: ${this.connectionId}`);
        }
        this.heartbeatIntervalMs = welcome.heartbeatIntervalMs || DEFAULT_HEARTBEAT_INTERVAL_MS;
        this.startHeartbeat();

        const token = this.options?.accessToken || this.options?.token;
        if (token) {
          this.logDebug('WS_AUTHENTICATING', 'Sending auth binding event with accessToken');
          this.setState('AUTHENTICATING');
          this.sendJson({
            type: 'auth',
            requestId: `auth-${Date.now()}`,
            payload: {
              accessToken: token,
              deviceId: this.options?.deviceId || undefined,
            },
          });
        } else {
          // Welcome allocates connection ID, does NOT establish user identity.
          // Missing credentials: do not claim authenticated or join rooms.
          this.logDebug('WS_AUTH_REQUIRED', 'No accessToken supplied; awaiting credentials');
          this.setState('AUTH_REQUIRED');
        }
        break;
      }

      case 'auth_ack': {
        const payload = envelope.payload as AuthAckPayload | undefined;
        if (payload?.authenticated === true) {
          this.logDebug('WS_AUTHENTICATED', `Server acknowledged auth binding (user: ${payload.userId || payload.username})`);
          this.lastPrecedingAuthError = null;
          this.onAuthenticatedSuccess();
        } else {
          this.logDebug('WS_AUTH_FAILED', 'Server auth_ack did not confirm authentication');
          this.setState('AUTH_REQUIRED');
        }
        break;
      }

      case 'heartbeat_ack': {
        this.lastHeartbeatAck = Date.now();
        break;
      }

      case 'channel.joined': {
        const payload = envelope.payload as { channelId?: string } | undefined;
        const channelId = payload?.channelId;
        if (channelId) {
          this.logDebug('CHANNEL_SUBSCRIBED', `Joined channel ${channelId}`);
          this.channelJoinedListeners.forEach(listener => {
            try { listener(channelId); } catch { /* ignore */ }
          });
        }
        break;
      }

      case 'channel.left': {
        const payload = envelope.payload as { channelId?: string } | undefined;
        if (payload?.channelId) {
          this.logDebug('CHANNEL_UNSUBSCRIBED', `Left channel ${payload.channelId}`);
        }
        break;
      }

      case 'message.created': {
        const payload = envelope.payload as MessageCreatedPayload | undefined;
        if (payload && payload.channelId && payload.message) {
          this.logDebug('MESSAGE_RECEIVED', `Created message in #${payload.channelId}`);
          this.dispatchChatMessage({ type: 'message.created', payload });
        }
        break;
      }

      case 'message.updated': {
        const payload = envelope.payload as MessageUpdatedPayload | undefined;
        if (payload && payload.channelId && payload.message) {
          this.logDebug('MESSAGE_RECEIVED', `Updated message ${payload.message.id} in #${payload.channelId}`);
          this.dispatchChatMessage({ type: 'message.updated', payload });
        }
        break;
      }

      case 'message.deleted': {
        const payload = envelope.payload as MessageDeletedPayload | undefined;
        if (payload && payload.channelId && (payload.message || payload.messageId)) {
          const msgId = payload.messageId || payload.message?.id || 'unknown';
          this.logDebug('MESSAGE_RECEIVED', `Deleted message ${msgId} in #${payload.channelId}`);
          this.dispatchChatMessage({ type: 'message.deleted', payload });
        }
        break;
      }

      case 'error': {
        const errCode = envelope.error?.code || envelope.code || '';
        const errMsg = envelope.error?.message || envelope.message || '';
        this.logDebug('WS_ERROR', `Error [${errCode}]: ${errMsg}`);

        if (errCode === 'AUTH_REQUIRED' || errCode === 'UNAUTHORIZED') {
          this.lastPrecedingAuthError = 'AUTH_REQUIRED';
          this.setState('AUTH_REQUIRED');
        } else if (errCode === 'SESSION_EXPIRED') {
          this.lastPrecedingAuthError = 'SESSION_EXPIRED';
          this.setState('SESSION_EXPIRED');
        } else if (errCode === 'SESSION_REVOKED') {
          this.lastPrecedingAuthError = 'SESSION_REVOKED';
          this.setState('SESSION_REVOKED');
        } else if (errCode === 'ACCOUNT_DISABLED') {
          this.lastPrecedingAuthError = 'ACCOUNT_DISABLED';
          this.setState('ACCOUNT_DISABLED');
        } else if (errCode === 'FORBIDDEN') {
          // Room access denied: remove from subscribed channels if channel join was rejected
          const channelId = (envelope.payload as any)?.channelId;
          if (channelId && this.subscribedChannels.has(channelId)) {
            this.subscribedChannels.delete(channelId);
            this.logDebug('CHANNEL_DENIED', `Access to channel ${channelId} denied (FORBIDDEN)`);
          }
        }
        break;
      }

      default:
        // Ignore other/unhandled event types (e.g. transfer signalling, echo)
        break;
    }
  }

  private onAuthenticatedSuccess() {
    const wasReconnecting = this.state === 'RECONNECTING' || this.reconnectAttempts > 0;
    this.setState('CONNECTED');
    this.reconnectAttempts = 0;

    // Resubscribe all active channels
    this.subscribedChannels.forEach(channelId => {
      this.sendJson({
        type: 'channel.join',
        requestId: `join-${channelId}-${Date.now()}`,
        payload: { channelId },
      });
    });

    if (wasReconnecting) {
      this.reconnectListeners.forEach(fn => {
        try { fn(); } catch { /* ignore */ }
      });
    }
  }

  private dispatchChatMessage(event: RealtimeChatEvent) {
    this.messageListeners.forEach(listener => {
      try {
        listener(event);
      } catch {
        /* ignore listener error */
      }
    });
  }

  private handleSocketClose(event: CloseEvent) {
    this.logDebug('WS_DISCONNECTED', `Socket closed code=${event.code} reason=${event.reason}`);
    this.stopHeartbeat();

    if (this.manualDisconnect) {
      this.setState('IDLE');
      return;
    }

    // Terminal administration disconnect
    if (event.code === 4001) {
      this.setState('DISCONNECTED');
      return;
    }

    // Account disabled termination
    if (event.code === 4003 || this.lastPrecedingAuthError === 'ACCOUNT_DISABLED') {
      this.setState('ACCOUNT_DISABLED');
      return;
    }

    // Code 4002: Authentication termination.
    // Server sends error envelope before closing (UNAUTHORIZED on expiry, SESSION_REVOKED/EXPIRED on revocation).
    // Preserve the preceding error classification!
    if (event.code === 4002) {
      if (this.lastPrecedingAuthError === 'AUTH_REQUIRED' || this.lastPrecedingAuthError === 'UNAUTHORIZED') {
        // Access expiry: preserve AUTH_REQUIRED to permit single-flight token refresh and fresh socket reconnect.
        this.setState('AUTH_REQUIRED');
        return;
      }
      if (this.lastPrecedingAuthError === 'SESSION_EXPIRED') {
        this.setState('SESSION_EXPIRED');
        return;
      }
      if (this.lastPrecedingAuthError === 'SESSION_REVOKED') {
        this.setState('SESSION_REVOKED');
        return;
      }
      // If code 4002 arrived without a preceding specific error, default to terminal SESSION_REVOKED
      this.setState('SESSION_REVOKED');
      return;
    }

    if (this.state === 'AUTH_REQUIRED' || this.state === 'SESSION_EXPIRED' || this.state === 'SESSION_REVOKED' || this.state === 'ACCOUNT_DISABLED') {
      // Do not auto-reconnect with invalid credentials
      return;
    }

    this.handleConnectionLost(true);
  }


  private handleSocketError() {
    this.logDebug('WS_DISCONNECTED', 'Socket error encountered');
  }

  private handleConnectionLost(scheduleReconnect: boolean) {
    this.stopHeartbeat();

    if (this.manualDisconnect) {
      this.setState('IDLE');
      return;
    }

    this.setState('RECONNECTING');

    if (scheduleReconnect) {
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);

    // Exponential backoff with jitter
    const delay = Math.min(1000 * Math.pow(1.5, this.reconnectAttempts), MAX_RECONNECT_DELAY_MS) + Math.random() * 500;
    this.reconnectAttempts++;

    this.logDebug('WS_RECONNECTING', `Attempt ${this.reconnectAttempts} in ${Math.round(delay)}ms`);

    this.reconnectTimer = setTimeout(() => {
      this.initiateSocketConnection();
    }, delay);
    if (this.reconnectTimer && typeof (this.reconnectTimer as any).unref === 'function') {
      (this.reconnectTimer as any).unref();
    }
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.lastHeartbeatAck = Date.now();

    this.heartbeatTimer = setInterval(() => {
      if (this.state !== 'CONNECTED' && this.state !== 'AUTHENTICATING') return;

      const timeSinceAck = Date.now() - this.lastHeartbeatAck;
      if (timeSinceAck > DEFAULT_HEARTBEAT_TIMEOUT_MS) {
        this.logDebug('HEARTBEAT_TIMEOUT', 'No heartbeat ack received within timeout, restarting socket');
        this.cleanupSocket();
        this.handleConnectionLost(true);
        return;
      }

      this.sendJson({
        type: 'heartbeat',
        requestId: `hb-${Date.now()}`,
      });
    }, this.heartbeatIntervalMs);
    if (this.heartbeatTimer && typeof (this.heartbeatTimer as any).unref === 'function') {
      (this.heartbeatTimer as any).unref();
    }
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private cleanupSocket() {
    this.stopHeartbeat();
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      try {
        if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
          this.ws.close(1000, 'Cleaning up');
        }
      } catch {
        /* ignore */
      }
      this.ws = null;
    }
    this.connectionId = null;
  }

  private sendJson(payload: object): boolean {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false;
    try {
      this.ws.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  }

  public subscribeChannel(channelId: string) {
    if (!channelId) return;
    const isNew = !this.subscribedChannels.has(channelId);
    this.subscribedChannels.add(channelId);

    if (this.state === 'CONNECTED' && isNew) {
      this.sendJson({
        type: 'channel.join',
        requestId: `join-${channelId}-${Date.now()}`,
        payload: { channelId },
      });
    }
  }

  public unsubscribeChannel(channelId: string) {
    if (!channelId) return;
    this.subscribedChannels.delete(channelId);

    if (this.state === 'CONNECTED') {
      this.sendJson({
        type: 'channel.leave',
        requestId: `leave-${channelId}-${Date.now()}`,
        payload: { channelId },
      });
    }
  }

  public disconnect() {
    this.manualDisconnect = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.subscribedChannels.clear();
    this.cleanupSocket();
    this.setState('IDLE');
  }

  public onStateChange(listener: StateListener): () => void {
    this.stateListeners.add(listener);
    listener(this.state);
    return () => this.stateListeners.delete(listener);
  }

  public onMessage(listener: ChatEventListener): () => void {
    this.messageListeners.add(listener);
    return () => this.messageListeners.delete(listener);
  }

  public onReconnected(listener: ReconnectListener): () => void {
    this.reconnectListeners.add(listener);
    return () => this.reconnectListeners.delete(listener);
  }

  public onChannelJoined(listener: ChannelJoinedListener): () => void {
    this.channelJoinedListeners.add(listener);
    return () => this.channelJoinedListeners.delete(listener);
  }

  public getConnectionId(): string | null {
    return this.connectionId;
  }

  public sendEnvelope(envelope: object): boolean {
    return this.sendJson(envelope);
  }

  public onEnvelope(listener: (envelope: RealtimeEnvelope) => void): () => void {
    this.envelopeListeners.add(listener);
    return () => this.envelopeListeners.delete(listener);
  }
}

// Global browser session singleton
let instance: RealtimeClient | null = null;

export function getRealtimeClient(): RealtimeClient {
  if (!instance) {
    instance = new RealtimeClient();
  }
  return instance;
}
