/**
 * WebRTC ICE and STUN configuration for LANDrop browser-to-browser P2P.
 * Strictly configured via environment variables or standardized public STUN endpoints.
 * Never hardcodes local IP addresses. TURN relay is deferred to future milestones.
 */

export interface WebRTCConfigurationOptions {
  iceServers?: RTCIceServer[];
  iceCandidatePoolSize?: number;
}

export function getDefaultIceServers(): RTCIceServer[] {
  const configuredStun =
    (typeof process !== 'undefined' && process.env?.NEXT_PUBLIC_STUN_SERVER) ||
    'stun:stun.l.google.com:19302';

  const urls = configuredStun
    .split(',')
    .map(s => s.trim())
    .filter(Boolean);

  return [{ urls }];
}

export function getRtcConfiguration(options?: WebRTCConfigurationOptions): RTCConfiguration {
  return {
    iceServers: options?.iceServers || getDefaultIceServers(),
    iceCandidatePoolSize: options?.iceCandidatePoolSize ?? 2,
  };
}
