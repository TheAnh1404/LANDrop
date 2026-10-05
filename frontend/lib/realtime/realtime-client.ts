'use client';

type EventHandler = (data: any) => void;

export class RealtimeClient {
  private static instance: RealtimeClient;
  private ws: WebSocket | null = null;
  private listeners: Map<string, Set<EventHandler>> = new Map();
  private reconnectTimer: any = null;
  private heartbeatTimer: any = null;
  private isConnecting = false;
  private isConnected = false;
  private serverUrl = '';
  private connectionId: string | null = null;

  private constructor() {}

  public static getInstance(): RealtimeClient {
    if (!RealtimeClient.instance) {
      RealtimeClient.instance = new RealtimeClient();
    }
    return RealtimeClient.instance;
  }

  public getConnectionId(): string | null {
    return this.connectionId;
  }

  public connect(url: string = 'ws://localhost:3001'): void {
    if (typeof window === 'undefined') return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    this.serverUrl = url;
    this.isConnecting = true;

    try {
      this.ws = new WebSocket(url);

      this.ws.onopen = () => {
        this.isConnected = true;
        this.isConnecting = false;
        this.emit('connection.state', { status: 'connected' });

        const clientId = localStorage.getItem('landrop_client_instance_id') || `browser_${Math.random().toString(36).slice(2, 10)}`;
        localStorage.setItem('landrop_client_instance_id', clientId);

        this.send('hello', {
          protocolVersion: 1,
          clientInstanceId: clientId,
          deviceName: navigator.userAgent ? 'Browser Client' : 'Web Session'
        });

        this.startHeartbeat();
      };

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data);
          const type = message.type || message.event;
          if (type === 'welcome') {
            this.connectionId = message.connectionId || message.payload?.connectionId || null;
          }
          if (type) {
            this.emit(type, message.payload || message.data || message);
          }
        } catch {
          // non-json payload
        }
      };

      this.ws.onclose = () => {
        this.cleanup();
        this.scheduleReconnect();
      };

      this.ws.onerror = () => {
        // Fallback simulated mode for local offline demo
        this.emit('connection.state', { status: 'mock_fallback' });
      };
    } catch {
      this.scheduleReconnect();
    }
  }

  public send(type: string, payload: any): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type, payload, timestamp: Date.now() }));
    } else {
      // Loopback emit locally for offline prototype demonstration
      setTimeout(() => {
        this.emit(type, payload);
      }, 50);
    }
  }

  public on(event: string, handler: EventHandler): () => void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(handler);
    return () => this.off(event, handler);
  }

  public off(event: string, handler: EventHandler): void {
    const handlers = this.listeners.get(event);
    if (handlers) {
      handlers.delete(handler);
      if (handlers.size === 0) {
        this.listeners.delete(event);
      }
    }
  }

  public emit(event: string, data: any): void {
    const handlers = this.listeners.get(event);
    if (handlers) {
      handlers.forEach((h) => {
        try {
          h(data);
        } catch (e) {
          console.error(`Error in realtime event handler for ${event}:`, e);
        }
      });
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping' }));
      }
    }, 15000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.serverUrl) {
        this.connect(this.serverUrl);
      }
    }, 5000);
  }

  private cleanup(): void {
    this.isConnected = false;
    this.isConnecting = false;
    this.stopHeartbeat();
    this.emit('connection.state', { status: 'disconnected' });
  }

  public disconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.cleanup();
  }
}

export const realtime = RealtimeClient.getInstance();
