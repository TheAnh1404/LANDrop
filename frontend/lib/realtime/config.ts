/**
 * Resolves the WebSocket URL based on deployment configuration and HTTP server origin.
 * Does not hardcode IP addresses or port numbers in components.
 */
export function resolveWebSocketUrl(serverHttpUrl: string): string {
  if (typeof process !== 'undefined' && process.env.NEXT_PUBLIC_WS_URL) {
    return process.env.NEXT_PUBLIC_WS_URL;
  }

  try {
    const raw = serverHttpUrl.trim();
    const url = new URL(raw);
    const isSecure = url.protocol === 'https:';
    const wsProtocol = isSecure ? 'wss:' : 'ws:';
    // HTTPS is served through the secure LAN proxy on the same origin, including custom ports.
    if (isSecure) return `${wsProtocol}//${url.host}/ws`;

    // Reverse-proxy / same-origin production deployment without port
    if (!url.port || url.port === '80' || url.port === '443') {
      return `${wsProtocol}//${url.host}/ws`;
    }

    // Standard development deployment port mapping
    let wsPort = url.port;
    if (wsPort === '3100') {
      wsPort = '3101';
    } else if (wsPort === '3000') {
      wsPort = '3001';
    } else {
      const parsed = parseInt(wsPort, 10);
      if (!isNaN(parsed) && parsed > 0 && parsed < 65535) {
        wsPort = String(parsed + 1);
      }
    }

    return `${wsProtocol}//${url.hostname}:${wsPort}`;
  } catch {
    return 'ws://localhost:3101';
  }
}
