'use client';

import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useRealtime } from '../realtime/realtime-provider';
import { WebRTCPeerService } from './webrtc-peer-service';
import type {
  IncomingTransferRequest,
  PeerTestResult,
  WebRTCDiagnostics,
  WebRTCPeerState,
  WebRTCRole,
} from './types';

interface WebRTCContextValue {
  service: WebRTCPeerService | null;
  state: WebRTCPeerState;
  role: WebRTCRole | null;
  diagnostics: WebRTCDiagnostics;
  incomingRequest: IncomingTransferRequest | null;
  testResult: PeerTestResult | null;
  localConnectionId: string | null;
  requestP2P: (params: {
    workspaceId: string;
    receiverConnectionId: string;
    targetUserId?: string;
    targetDeviceName?: string;
    fileName?: string;
    fileSize?: number;
  }) => Promise<void>;
  acceptIncomingRequest: (transferId?: string) => Promise<void>;
  rejectIncomingRequest: (reason?: string, transferId?: string) => void;
  cancelConnection: (reason?: string) => void;
  resetToIdle: () => void;
}

const WebRTCContext = createContext<WebRTCContextValue | null>(null);

const DEFAULT_DIAGNOSTICS: WebRTCDiagnostics = {
  transferId: null,
  role: null,
  targetUserId: null,
  targetConnectionId: null,
  targetDeviceName: null,
  signalingState: 'IDLE',
  rtcSignalingState: 'none',
  iceConnectionState: 'none',
  peerConnectionState: 'none',
  dataChannelState: 'none',
  roundTripLatencyMs: null,
  lastError: null,
  lastUpdated: 0,
};

export function WebRTCProvider({ children }: { children: React.ReactNode }) {
  const { client, connectionState } = useRealtime();

  const serviceRef = useRef<WebRTCPeerService | null>(null);
  const [peerState, setPeerState] = useState<WebRTCPeerState>('IDLE');
  const [diagnostics, setDiagnostics] = useState<WebRTCDiagnostics>(DEFAULT_DIAGNOSTICS);
  const [incomingRequest, setIncomingRequest] = useState<IncomingTransferRequest | null>(null);
  const [testResult, setTestResult] = useState<PeerTestResult | null>(null);
  const [localConnectionId, setLocalConnectionId] = useState<string | null>(() => client.getConnectionId());

  // Instantiate or update WebRTCPeerService bound to the single RealtimeClient
  useEffect(() => {
    const service = new WebRTCPeerService(client);
    serviceRef.current = service;

    const unsubState = service.onStateChange(st => setPeerState(st));
    const unsubDiag = service.onDiagnostics(dg => setDiagnostics(dg));
    const unsubReq = service.onIncomingRequest(req => setIncomingRequest(req));
    const unsubTest = service.onTestResult(res => setTestResult(res));

    return () => {
      unsubState();
      unsubDiag();
      unsubReq();
      unsubTest();
      service.destroy();
      serviceRef.current = null;
    };
  }, [client]);

  // Track local connection ID updates on socket connection changes
  useEffect(() => {
    setLocalConnectionId(client.getConnectionId());

    // When socket disconnects, cancel any active peer connection
    if (connectionState === 'DISCONNECTED' || connectionState === 'SESSION_REVOKED') {
      if (serviceRef.current && serviceRef.current.getState() !== 'IDLE') {
        serviceRef.current.cancelConnection('WebSocket disconnected from server');
      }
    }
  }, [client, connectionState]);

  const requestP2P = async (params: {
    workspaceId: string;
    receiverConnectionId: string;
    targetUserId?: string;
    targetDeviceName?: string;
    fileName?: string;
    fileSize?: number;
  }) => {
    if (!serviceRef.current) throw new Error('WebRTC service not initialized');
    await serviceRef.current.requestConnection(params);
  };

  const acceptIncomingRequest = async (transferId?: string) => {
    if (!serviceRef.current) throw new Error('WebRTC service not initialized');
    await serviceRef.current.acceptIncomingRequest(transferId);
  };

  const rejectIncomingRequest = (reason?: string, transferId?: string) => {
    serviceRef.current?.rejectIncomingRequest(reason, transferId);
  };

  const cancelConnection = (reason?: string) => {
    serviceRef.current?.cancelConnection(reason);
  };

  const resetToIdle = () => {
    serviceRef.current?.resetToIdle();
  };

  const value = useMemo<WebRTCContextValue>(() => ({
    service: serviceRef.current,
    state: peerState,
    role: serviceRef.current?.getRole() ?? null,
    diagnostics,
    incomingRequest,
    testResult,
    localConnectionId,
    requestP2P,
    acceptIncomingRequest,
    rejectIncomingRequest,
    cancelConnection,
    resetToIdle,
  }), [peerState, diagnostics, incomingRequest, testResult, localConnectionId]);

  return <WebRTCContext.Provider value={value}>{children}</WebRTCContext.Provider>;
}

export function useWebRTC(): WebRTCContextValue {
  const context = useContext(WebRTCContext);
  if (!context) {
    throw new Error('useWebRTC must be used within a WebRTCProvider');
  }
  return context;
}
