'use client';

import React, { createContext, useCallback, useContext, useEffect, useReducer, useRef, useState } from 'react';
import { useRealtime } from '../realtime/realtime-provider';
import { useSession } from '../session/session-provider';
import { WebRTCPeerService } from './webrtc-peer-service';
import type { IncomingTransferRequest, PeerTestResult, WebRTCDiagnostics, WebRTCPeerState, WebRTCRole, FileTransferProgress, ReceivedFileItem, QuickTextMessage, OnlinePeerItem, P2PChatMessage } from './types';
import type { P2PTask } from '../p2p/engine';

interface ConnectionParams {
  workspaceId: string; receiverConnectionId: string; targetUserId?: string; targetDeviceId?: string;
  targetDeviceName?: string; fileName?: string; fileSize?: number;
}
interface Entry { key: string; service: WebRTCPeerService; params: ConnectionParams; stop: boolean; approved: boolean; lastAttempt: number; attempts: number; subscriptions: (() => void)[]; }
interface WebRTCContextValue {
  service: WebRTCPeerService | null; state: WebRTCPeerState; role: WebRTCRole | null; diagnostics: WebRTCDiagnostics;
  incomingRequest: IncomingTransferRequest | null; testResult: PeerTestResult | null; localConnectionId: string | null;
  transferProgress: FileTransferProgress | null; receivedFiles: ReceivedFileItem[]; quickMessages: QuickTextMessage[];
  chatMessages: P2PChatMessage[]; peerTyping: { isTyping: boolean; senderName: string } | null; onlinePeers: OnlinePeerItem[];
  fileTasks: P2PTask[]; backgroundConnections: number;
  requestP2P: (params: ConnectionParams) => Promise<void>;
  acceptIncomingRequest: (transferId?: string) => Promise<void>; rejectIncomingRequest: (reason?: string, transferId?: string) => void;
  cancelConnection: (reason?: string) => void; resetToIdle: () => void;
  sendFile: (file: File) => Promise<void>; sendQuickText: (text: string) => void;
  sendChatMessage: (text: string, replyToId?: string) => Promise<P2PChatMessage>; sendChatTyping: (isTyping: boolean) => void;
  clearChatMessages: () => void; fetchOnlinePeers: () => void; clearReceivedFiles: () => void; clearQuickMessages: () => void;
  acceptFile: (id: string, destination?: FileSystemFileHandle) => Promise<void>; rejectFile: (id: string) => Promise<void>;
  pauseFile: (id: string) => Promise<void>; resumeFile: (id: string, file?: File) => Promise<void>; cancelFile: (id: string) => Promise<void>;
  removeFile: (id: string) => Promise<void>; retryMessage: (id: string) => Promise<void>; cancelMessage: (id: string) => Promise<void>;
  downloadFile: (id: string) => Promise<void>;
}
const Context = createContext<WebRTCContextValue | null>(null);
const DEFAULT_DIAGNOSTICS: WebRTCDiagnostics = {
  transferId: null, role: null, targetUserId: null, targetConnectionId: null, targetDeviceName: null,
  signalingState: 'IDLE', rtcSignalingState: 'none', iceConnectionState: 'none', peerConnectionState: 'none', dataChannelState: 'none',
  roundTripLatencyMs: null, lastError: null, lastUpdated: 0,
};

export function WebRTCProvider({ children }: { children: React.ReactNode }) {
  const { client, connectionState } = useRealtime();
  const { user, serverUrl } = useSession();
  const pool = useRef(new Map<string, Entry>());
  const focus = useRef<Entry | null>(null);
  const peers = useRef<OnlinePeerItem[]>([]);
  const pending = useRef(new Map<string, Entry>());
  const typing = useRef(new Map<string, { isTyping: boolean; senderName: string }>());
  const hiddenFiles = useRef(new Set<string>());
  const [, redraw] = useReducer(value => value + 1, 0);
  const [onlinePeers, setOnlinePeers] = useState<OnlinePeerItem[]>([]);
  const identity = `${serverUrl}|${user?.id || ''}`;
  const createEntry = useCallback((params: ConnectionParams): Entry => {
    if (!user) throw new Error('Đăng nhập trước khi kết nối P2P.');
    if (!params.targetUserId) throw new Error('Không tìm thấy danh tính thiết bị. Làm mới danh sách peer trước.');
    const key = JSON.stringify([params.workspaceId, params.targetUserId, params.targetDeviceId || params.targetUserId]);
    const existing = pool.current.get(key);
    if (existing) { existing.params = params; return existing; }
    if (pool.current.size >= 2) {
      const inactive = [...pool.current.values()].find(entry => ['IDLE', 'FAILED', 'CANCELLED', 'TIMEOUT', 'REJECTED', 'DISCONNECTED'].includes(entry.service.getState()) && !entry.service.getTasks().some(task => ['transferring', 'preparing', 'verifying', 'waiting'].includes(task.status)));
      if (!inactive) throw new Error('Hai kết nối đang hoạt động. Dừng một kết nối trước khi mở thiết bị thứ ba.');
      inactive.subscriptions.forEach(unsubscribe => unsubscribe()); inactive.service.destroy(); pool.current.delete(inactive.key);
    }
    const service = new WebRTCPeerService(client, { localUserId: user.id, localName: user.name, serverUrl, managed: true });
    const entry: Entry = { key, service, params, stop: false, approved: false, lastAttempt: 0, attempts: 0, subscriptions: [] };
    pool.current.set(key, entry);
    entry.subscriptions = [
      service.onStateChange(state => { if (state === 'CONNECTED') entry.attempts = 0; redraw(); if (state === 'DISCONNECTED' && !entry.stop) client.sendEnvelope({ type: 'peer.list', requestId: `recover-${Date.now()}` }); }),
      service.onDiagnostics(() => redraw()), service.onChatMessage(() => redraw()), service.onTasks(() => redraw()),
      service.onTransferProgress(() => redraw()), service.onFileReceived(() => redraw()), service.onQuickText(() => redraw()),
      service.onChatTyping(value => { typing.current.set(key, value); redraw(); }),
      service.onIncomingRequest(request => { if (!request) for (const [id, item] of pending.current) if (item === entry) pending.current.delete(id); redraw(); }),
    ];
    return entry;
  }, [client, user?.id, user?.name, serverUrl]);

  useEffect(() => {
    return () => {
      for (const entry of pool.current.values()) { entry.subscriptions.forEach(unsubscribe => unsubscribe()); entry.service.destroy(); }
      pool.current.clear(); pending.current.clear(); focus.current = null; peers.current = []; typing.current.clear(); hiddenFiles.current.clear();
    };
  }, [identity]);

  const fetchOnlinePeers = useCallback(() => { client.sendEnvelope({ type: 'peer.list', requestId: `peers-${Date.now()}` }); }, [client]);
  useEffect(() => client.onEnvelope(envelope => {
    const payload = (envelope.payload || {}) as Record<string, any>;
    if (envelope.type === 'peer.list' || envelope.type === 'peers.list') {
      peers.current = Array.isArray(payload.peers) ? payload.peers : []; setOnlinePeers(peers.current);
      for (const entry of pool.current.values()) {
        if (entry.stop || entry.attempts >= 5 || !['DISCONNECTED', 'TIMEOUT', 'CANCELLED'].includes(entry.service.getState())) continue;
        const target = peers.current.find(peer => peer.userId === entry.params.targetUserId && (!entry.params.targetDeviceId || peer.deviceId === entry.params.targetDeviceId));
        if (!target || Date.now() - entry.lastAttempt < Math.min(30000, 1000 * 2 ** entry.attempts)) continue;
        entry.lastAttempt = Date.now(); entry.attempts++;
        entry.params = { ...entry.params, receiverConnectionId: target.connectionId };
        void entry.service.requestConnection(entry.params).catch(() => redraw());
      }
    }
    if (envelope.type === 'transfer.request' && user) {
      try {
        const entry = createEntry({ workspaceId: payload.workspaceId, receiverConnectionId: payload.senderConnectionId, targetUserId: payload.senderUserId, targetDeviceId: payload.senderDeviceId, targetDeviceName: payload.senderDeviceName });
        const state = entry.service.getState();
        if (state === 'CONNECTED' || (['REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(state) && (client.getConnectionId() || '') < payload.senderConnectionId)) {
          client.sendEnvelope({ type: 'transfer.reject', payload: { transferId: payload.transferId, reason: 'An existing connection is being used.' } }); return;
        }
        if (['REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(state)) entry.service.cancelConnection('Resolve simultaneous connection requests');
        entry.service.receiveIncomingRequest(payload); pending.current.set(payload.transferId, entry);
        if (!focus.current) focus.current = entry;
        if (entry.approved && !entry.stop) void entry.service.acceptIncomingRequest(payload.transferId).catch(() => redraw());
        redraw();
      } catch (error) { client.sendEnvelope({ type: 'transfer.reject', payload: { transferId: payload.transferId, reason: error instanceof Error ? error.message : 'Connection limit reached.' } }); }
    }
  }), [client, createEntry, user?.id]);

  useEffect(() => {
    if (['DISCONNECTED', 'AUTH_REQUIRED', 'SESSION_REVOKED', 'IDLE'].includes(connectionState)) {
      for (const entry of pool.current.values()) entry.service.suspend();
      redraw();
    }
    if (connectionState === 'CONNECTED') {
      fetchOnlinePeers();
      const timer = setInterval(fetchOnlinePeers, 6000);
      return () => clearInterval(timer);
    }
  }, [connectionState, fetchOnlinePeers]);

  const requestP2P = async (params: ConnectionParams) => {
    const target = peers.current.find(peer => peer.connectionId === params.receiverConnectionId);
    const entry = createEntry({ ...params, targetUserId: params.targetUserId || target?.userId || undefined, targetDeviceId: params.targetDeviceId || target?.deviceId || undefined });
    focus.current = entry; entry.stop = false; entry.approved = true; redraw();
    if (['CONNECTED', 'REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(entry.service.getState())) return;
    await entry.service.requestConnection({ ...entry.params, fileName: 'p2p-chat-session', fileSize: 0 });
  };
  const requireService = () => { if (!focus.current) throw new Error('Chọn một thiết bị trước.'); return focus.current.service; };
  const requireCore = () => { const core = requireService().getReliable(); if (!core) throw new Error('Kết nối đang được chuẩn bị.'); return core; };
  const taskOwner = (id: string) => {
    const entry = [...pool.current.values()].find(item => item.service.getTasks().some(task => task.id === id));
    const core = entry?.service.getReliable(); if (!core) throw new Error('Không tìm thấy lượt truyền.'); return core;
  };
  const request = [...pending.current.values()].map(entry => entry.service.getIncomingRequest()).find(Boolean) || null;
  const service = focus.current?.service || null;
  const value: WebRTCContextValue = {
    service, state: service?.getState() || 'IDLE', role: service?.getRole() || null, diagnostics: service?.getDiagnostics() || DEFAULT_DIAGNOSTICS,
    incomingRequest: request, testResult: service?.getTestResult() || null, localConnectionId: client.getConnectionId(),
    transferProgress: service?.getTransferProgress() || null,
    receivedFiles: [...pool.current.values()].flatMap(entry => entry.service.getReceivedFiles()).filter(file => !hiddenFiles.current.has(file.id)),
    quickMessages: service?.getQuickMessages() || [], chatMessages: service?.getChatMessages() || [],
    peerTyping: focus.current ? typing.current.get(focus.current.key) || null : null, onlinePeers,
    fileTasks: [...pool.current.values()].flatMap(entry => entry.service.getTasks()), backgroundConnections: Math.max(0, [...pool.current.values()].filter(entry => entry.service.getState() === 'CONNECTED').length - (service?.getState() === 'CONNECTED' ? 1 : 0)),
    requestP2P,
    acceptIncomingRequest: async id => { const entry = pending.current.get(id || request?.transferId || ''); if (!entry) throw new Error('Yêu cầu không còn tồn tại.'); focus.current = entry; entry.approved = true; entry.stop = false; await entry.service.acceptIncomingRequest(id); redraw(); },
    rejectIncomingRequest: (reason, id) => { pending.current.get(id || request?.transferId || '')?.service.rejectIncomingRequest(reason, id); redraw(); },
    cancelConnection: reason => { if (focus.current) { focus.current.stop = true; focus.current.service.cancelConnection(reason); } },
    resetToIdle: () => { if (focus.current) { focus.current.stop = true; focus.current.service.resetToIdle(); } },
    sendFile: file => requireService().sendFile(file), sendQuickText: text => requireService().sendQuickText(text),
    sendChatMessage: async (text, reply) => requireService().sendChatMessage(text, reply), sendChatTyping: active => service?.sendChatTyping(active),
    clearChatMessages: () => { service?.clearChatMessages(); redraw(); }, fetchOnlinePeers,
    clearReceivedFiles: () => { for (const entry of pool.current.values()) for (const file of entry.service.getReceivedFiles()) hiddenFiles.current.add(file.id); redraw(); },
    clearQuickMessages: () => { service?.clearQuickMessages(); redraw(); },
    acceptFile: (id, destination) => taskOwner(id).acceptFile(id, destination), rejectFile: id => taskOwner(id).rejectFile(id),
    pauseFile: id => taskOwner(id).pause(id), resumeFile: async (id, file) => { await taskOwner(id).resume(id, file); }, cancelFile: id => taskOwner(id).cancel(id), removeFile: id => taskOwner(id).removeTask(id),
    retryMessage: id => requireCore().retryChat(id), cancelMessage: id => requireCore().cancelChat(id),
    downloadFile: async id => { const task = [...pool.current.values()].flatMap(entry => entry.service.getTasks()).find(task => task.id === id); const file = await taskOwner(id).savedFile(id); const link = document.createElement('a'); link.href = file.blobUrl; link.download = task?.name || 'download'; link.click(); },
  };
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
export function useWebRTC(): WebRTCContextValue { const value = useContext(Context); if (!value) throw new Error('useWebRTC requires WebRTCProvider.'); return value; }
